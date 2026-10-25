package main

import (
	"archive/zip"
	"context"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	rifeURL = "https://github.com/nihui/rife-ncnn-vulkan/releases/download/20221029/rife-ncnn-vulkan-20221029-windows.zip"
	rifeExe = "rife-ncnn-vulkan.exe"
)

// ---------- HTTP range reader: lets archive/zip read only the parts it needs ----------

type httpReaderAt struct {
	ctx   context.Context
	url   string
	size  int64
	mu    sync.Mutex
	cache map[int64][]byte
	order []int64
	read  int64
	prog  func(int64)
}

const blockSize = 1 << 20

func newHTTPReaderAt(ctx context.Context, url string) (*httpReaderAt, error) {
	req, _ := http.NewRequestWithContext(ctx, "GET", url, nil)
	req.Header.Set("Range", "bytes=0-0")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	resp.Body.Close()
	if resp.StatusCode != 206 {
		return nil, fmt.Errorf("le serveur ne permet pas le téléchargement partiel (%s)", resp.Status)
	}
	cr := resp.Header.Get("Content-Range") // bytes 0-0/12345
	i := strings.LastIndex(cr, "/")
	size, _ := strconv.ParseInt(cr[i+1:], 10, 64)
	if size <= 0 {
		return nil, errors.New("taille inconnue")
	}
	// keep the final (redirected) URL so later requests skip the redirect
	return &httpReaderAt{ctx: ctx, url: resp.Request.URL.String(), size: size, cache: map[int64][]byte{}}, nil
}

func (h *httpReaderAt) block(idx int64) ([]byte, error) {
	h.mu.Lock()
	if b, ok := h.cache[idx]; ok {
		h.mu.Unlock()
		return b, nil
	}
	h.mu.Unlock()
	start := idx * blockSize
	end := min(start+blockSize, h.size) - 1
	var b []byte
	var err error
	for try := 0; try < 3; try++ {
		req, _ := http.NewRequestWithContext(h.ctx, "GET", h.url, nil)
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, end))
		var resp *http.Response
		resp, err = http.DefaultClient.Do(req)
		if err != nil {
			continue
		}
		b, err = io.ReadAll(resp.Body)
		resp.Body.Close()
		if err == nil && int64(len(b)) == end-start+1 {
			break
		}
		if err == nil {
			err = errors.New("réponse incomplète")
		}
	}
	if err != nil {
		return nil, err
	}
	h.mu.Lock()
	h.cache[idx] = b
	h.order = append(h.order, idx)
	if len(h.order) > 8 {
		delete(h.cache, h.order[0])
		h.order = h.order[1:]
	}
	h.read += int64(len(b))
	if h.prog != nil {
		h.prog(h.read)
	}
	h.mu.Unlock()
	return b, nil
}

func (h *httpReaderAt) ReadAt(p []byte, off int64) (int, error) {
	n := 0
	for n < len(p) {
		pos := off + int64(n)
		if pos >= h.size {
			return n, io.EOF
		}
		b, err := h.block(pos / blockSize)
		if err != nil {
			return n, err
		}
		c := copy(p[n:], b[pos%blockSize:])
		n += c
	}
	return n, nil
}

// remoteUnzip extracts selected entries of a remote zip. keep returns destination path (relative) or "".
func remoteUnzip(ctx context.Context, url, dst string, keep func(string) string, prog func(float64)) error {
	h, err := newHTTPReaderAt(ctx, url)
	if err != nil {
		return err
	}
	zr, err := zip.NewReader(h, h.size)
	if err != nil {
		return err
	}
	var total int64
	var files []*zip.File
	for _, f := range zr.File {
		if !f.FileInfo().IsDir() && keep(f.Name) != "" {
			files = append(files, f)
			total += int64(f.CompressedSize64)
		}
	}
	if len(files) == 0 {
		return errors.New("fichiers introuvables dans l'archive")
	}
	h.prog = func(read int64) { prog(math.Min(1, float64(read)/float64(total+2*blockSize))) }
	for _, f := range files {
		out := filepath.Join(dst, filepath.FromSlash(keep(f.Name)))
		_ = os.MkdirAll(filepath.Dir(out), 0755)
		rc, err := f.Open()
		if err != nil {
			return err
		}
		w, err := os.Create(out + ".part")
		if err != nil {
			rc.Close()
			return err
		}
		_, err = io.Copy(w, rc)
		w.Close()
		rc.Close()
		if err != nil {
			return err
		}
		if err := os.Rename(out+".part", out); err != nil {
			return err
		}
		_ = os.Chmod(out, 0755)
	}
	return nil
}

func rifePaths(dir string) (exe, model string) {
	return filepath.Join(dir, "rife", rifeExe), filepath.Join(dir, "rife", "rife-v4.6")
}

func ensureRIFE(ctx context.Context, dir string, prog progressFn) (string, string, error) {
	exe, model := rifePaths(dir)
	if fileExists(exe) && fileExists(filepath.Join(model, "flownet.bin")) {
		return exe, model, nil
	}
	err := remoteUnzip(ctx, rifeURL, filepath.Join(dir, "rife"), func(n string) string {
		parts := strings.Split(n, "/")
		if len(parts) < 2 {
			return ""
		}
		rel := strings.Join(parts[1:], "/")
		b := parts[len(parts)-1]
		if len(parts) == 2 && (b == rifeExe || strings.HasSuffix(b, ".dll")) {
			return rel
		}
		if len(parts) == 3 && parts[1] == "rife-v4.6" {
			return rel
		}
		return ""
	}, func(p float64) { prog("download_rife", p) })
	if err != nil {
		return exe, model, fmt.Errorf("téléchargement de l'IA 60 fps : %w", err)
	}
	return exe, model, nil
}

func ensureFFmpeg(ctx context.Context, dir string, prog progressFn) (Tools, error) {
	t := toolPaths(dir)
	if fileExists(t.FFmpeg) && fileExists(t.FFprobe) {
		return t, nil
	}
	_ = os.MkdirAll(dir, 0755)
	z := filepath.Join(dir, "ffmpeg.zip")
	if err := download(ctx, ffmpegURL, z, func(p float64) { prog("download_ff", p) }); err != nil {
		return t, fmt.Errorf("téléchargement de ffmpeg : %w", err)
	}
	err := unzip(z, filepath.Join(dir, "ffmpeg"), func(n string) string {
		b := filepath.Base(n)
		if strings.Contains(n, "/bin/") && (b == "ffmpeg"+exeSuffix || b == "ffprobe"+exeSuffix) {
			return b
		}
		return ""
	})
	os.Remove(z)
	if err != nil {
		return t, err
	}
	if !fileExists(t.FFmpeg) {
		return t, errors.New("ffmpeg introuvable après téléchargement")
	}
	return t, nil
}

// ---------- AI 60 fps (RIFE), chunked ----------

func outName(dir, src, tag, ext string) string {
	base := strings.TrimSuffix(filepath.Base(src), filepath.Ext(src))
	_ = os.MkdirAll(dir, 0755)
	return filepath.Join(dir, fmt.Sprintf("%s_%s%s", base, tag, ext))
}

var errAlready60 = errors.New("already 60fps")

func interpolate60(ctx context.Context, t Tools, rife, model, src, outDir string, prog progressFn) (string, error) {
	prog("probe", 0)
	pi, err := probe(ctx, t, src)
	if err != nil {
		return "", err
	}
	if pi.FPS >= 47 {
		return "", errAlready60
	}
	f := int(math.Round(60 / pi.FPS))
	if f < 2 {
		f = 2
	}
	outFPS := strconv.FormatFloat(pi.FPS*float64(f), 'f', -1, 64)

	work := filepath.Join(outDir, fmt.Sprintf("work_%d", time.Now().UnixNano()))
	in, mid := filepath.Join(work, "in"), filepath.Join(work, "mid")
	defer os.RemoveAll(work)
	var segs []string
	done := 0
	for start := 0; ; start += chunkSize {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		_ = os.RemoveAll(in)
		_ = os.RemoveAll(mid)
		_ = os.MkdirAll(in, 0755)
		_ = os.MkdirAll(mid, 0755)
		// chunk + 1 overlap frame so each chunk interpolates up to the next one
		// RIFE breaks on some frame sizes: pad to a multiple of 32, crop back when encoding
		pw, ph := (pi.W+31)/32*32, (pi.H+31)/32*32
		vf := fmt.Sprintf("fps=%s,select='between(n\\,%d\\,%d)',pad=%d:%d:0:0:black", pi.FPSStr, start, start+chunkSize, pw, ph)
		if _, err := run(ctx, t.FFmpeg, "-v", "error", "-i", src, "-vf", vf, "-fps_mode", "passthrough",
			"-frames:v", strconv.Itoa(chunkSize+1), "-start_number", "0", filepath.Join(in, "%06d.png")); err != nil {
			return "", err
		}
		e, _ := os.ReadDir(in)
		n := len(e)
		if n == 0 || (n == 1 && start > 0) {
			break
		}
		last := n <= chunkSize // no overlap frame => final chunk
		var want int
		if last {
			want = f*(n-1) + 1
		} else {
			want = f*chunkSize + 1
		}
		if n == 1 {
			want = 1
		}
		stop := make(chan struct{})
		go func(base int) {
			tk := time.NewTicker(400 * time.Millisecond)
			defer tk.Stop()
			for {
				select {
				case <-stop:
					return
				case <-tk.C:
					m, _ := os.ReadDir(mid)
					prog("interp", math.Min(0.99, (float64(base)+float64(len(m))/float64(f))/float64(max(pi.Frames, 1))))
				}
			}
		}(done)
		var rerr error
		if n > 1 {
			_, rerr = run(ctx, rife, "-i", in, "-o", mid, "-n", strconv.Itoa(want), "-m", model, "-f", "%06d.png")
		} else {
			rerr = os.Rename(filepath.Join(in, "000000.png"), filepath.Join(mid, "000001.png"))
		}
		close(stop)
		if rerr != nil {
			return "", fmt.Errorf("IA 60 fps : %w", rerr)
		}
		keep := want
		if !last {
			keep = want - 1 // drop the overlap frame (it starts the next chunk)
		}
		seg := filepath.Join(work, fmt.Sprintf("seg_%05d.mp4", len(segs)))
		if _, err := run(ctx, t.FFmpeg, "-v", "error", "-y", "-framerate", outFPS, "-start_number", "1",
			"-i", filepath.Join(mid, "%06d.png"), "-frames:v", strconv.Itoa(keep),
			"-vf", fmt.Sprintf("crop=%d:%d:0:0,scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p", pi.W, pi.H),
			"-c:v", "libx264", "-preset", "medium", "-crf", "16", seg); err != nil {
			return "", err
		}
		segs = append(segs, seg)
		if last {
			done += n
			break
		}
		done += chunkSize
	}
	if len(segs) == 0 {
		return "", errors.New("aucune image extraite")
	}
	prog("finish", 1)
	out := outName(outDir, src, fmt.Sprintf("%sfps", strings.Split(outFPS, ".")[0]), ".mp4")
	if err := concatSegs(ctx, t, work, segs, src, pi.Audio, out); err != nil {
		return "", err
	}
	return out, nil
}

func concatSegs(ctx context.Context, t Tools, work string, segs []string, audioSrc string, audio bool, out string) error {
	list := filepath.Join(work, "list.txt")
	var sb strings.Builder
	for _, s := range segs {
		sb.WriteString("file '" + strings.ReplaceAll(filepath.ToSlash(s), "'", "'\\''") + "'\n")
	}
	_ = os.WriteFile(list, []byte(sb.String()), 0644)
	args := []string{"-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", list}
	if audio {
		args = append(args, "-i", audioSrc, "-map", "0:v:0", "-map", "1:a:0", "-c:a", "aac", "-b:a", "192k", "-shortest")
	}
	args = append(args, "-c:v", "copy", "-movflags", "+faststart", out)
	_, err := run(ctx, t.FFmpeg, args...)
	return err
}

// ---------- editor: trim, colours, watermark removal, seamless loop ----------

type EditOpts struct {
	Start, End  float64 // seconds; End<=0 means until the end
	Brightness  float64 // -1..1 (0 = unchanged)
	Contrast    float64 // 0..2 (1 = unchanged)
	Saturation  float64 // 0..3 (1 = unchanged)
	Logo        []int   // x,y,w,h in source pixels (empty = none)
	Loop        bool    // blend the end into the start
	LoopSeconds float64
}

func editVideo(ctx context.Context, t Tools, src, outDir string, o EditOpts, prog progressFn) (string, error) {
	prog("probe", 0)
	pi, err := probe(ctx, t, src)
	if err != nil {
		return "", err
	}
	end := o.End
	if end <= 0 || end > pi.Duration {
		end = pi.Duration
	}
	start := math.Max(0, o.Start)
	dur := end - start
	if dur <= 0.1 {
		return "", errors.New("la coupe est trop courte")
	}
	var filters []string
	if len(o.Logo) == 4 {
		x, y, w, h := o.Logo[0], o.Logo[1], o.Logo[2], o.Logo[3]
		// delogo needs the box strictly inside the frame
		x, y = max(1, x), max(1, y)
		w, h = min(w, pi.W-x-1), min(h, pi.H-y-1)
		if w > 2 && h > 2 {
			filters = append(filters, fmt.Sprintf("delogo=x=%d:y=%d:w=%d:h=%d", x, y, w, h))
		}
	}
	if o.Brightness != 0 || o.Contrast != 1 || o.Saturation != 1 {
		filters = append(filters, fmt.Sprintf("eq=brightness=%.3f:contrast=%.3f:saturation=%.3f", o.Brightness, o.Contrast, o.Saturation))
	}
	if pi.W%2 == 1 || pi.H%2 == 1 {
		filters = append(filters, "scale=trunc(iw/2)*2:trunc(ih/2)*2")
	}
	base := "null"
	if len(filters) > 0 {
		base = strings.Join(filters, ",")
	}
	ss := strconv.FormatFloat(start, 'f', 3, 64)
	to := strconv.FormatFloat(dur, 'f', 3, 64)
	args := []string{"-v", "error", "-y", "-ss", ss, "-t", to, "-i", src}
	tag := "edit"
	if o.Loop {
		fade := o.LoopSeconds
		if fade <= 0 {
			fade = 1
		}
		fade = math.Min(fade, dur/3)
		off := dur - 2*fade
		fc := fmt.Sprintf("[0:v]%s,fps=%s,format=yuv420p,split[a0][a1];"+
			"[a1]trim=start=%.3f,setpts=PTS-STARTPTS[A];[a0]trim=0:%.3f,setpts=PTS-STARTPTS[H];"+
			"[A][H]xfade=transition=fade:duration=%.3f:offset=%.3f[v]", base, pi.FPSStr, fade, fade, fade, off)
		args = append(args, "-filter_complex", fc, "-map", "[v]", "-an")
		tag = "loop"
	} else {
		args = append(args, "-vf", base+",format=yuv420p", "-map", "0:v:0")
		if pi.Audio {
			args = append(args, "-map", "0:a:0", "-c:a", "aac", "-b:a", "192k")
		}
	}
	out := outName(outDir, src, tag, ".mp4")
	args = append(args, "-c:v", "libx264", "-preset", "medium", "-crf", "17", "-movflags", "+faststart",
		"-progress", "pipe:1", "-nostats", out)
	if err := runProgress(ctx, t.FFmpeg, args, dur, func(p float64) { prog("edit", p) }); err != nil {
		return "", err
	}
	return out, nil
}

// runProgress runs ffmpeg with -progress pipe:1 and reports progress in 0..1
func runProgress(ctx context.Context, ffmpeg string, args []string, total float64, prog func(float64)) error {
	cmd := commandContext(ctx, ffmpeg, args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	var stderr strings.Builder
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		return err
	}
	buf := make([]byte, 4096)
	var line strings.Builder
	for {
		n, rerr := stdout.Read(buf)
		for _, c := range string(buf[:n]) {
			if c == '\n' {
				l := line.String()
				line.Reset()
				if strings.HasPrefix(l, "out_time_us=") || strings.HasPrefix(l, "out_time_ms=") {
					us, _ := strconv.ParseFloat(l[strings.Index(l, "=")+1:], 64)
					if total > 0 {
						prog(math.Min(0.99, us/1e6/total))
					}
				}
			} else {
				line.WriteRune(c)
			}
		}
		if rerr != nil {
			break
		}
	}
	if err := cmd.Wait(); err != nil {
		msg := strings.TrimSpace(stderr.String())
		if len(msg) > 400 {
			msg = msg[len(msg)-400:]
		}
		return fmt.Errorf("ffmpeg: %v %s", err, msg)
	}
	prog(1)
	return nil
}

// ---------- sharing ----------

// exportPhone makes a vertical 1440x3120 version (blurred background), optionally limited to 15 s.
func exportPhone(ctx context.Context, t Tools, src, outDir string, short bool, prog progressFn) (string, error) {
	prog("probe", 0)
	pi, err := probe(ctx, t, src)
	if err != nil {
		return "", err
	}
	dur := pi.Duration
	args := []string{"-v", "error", "-y"}
	if pi.Duration < 3 {
		// very short clips (GIFs): repeat them so the phone gets a longer loop
		args = append(args, "-stream_loop", strconv.Itoa(int(math.Ceil(6/math.Max(pi.Duration, 0.1)))))
		dur = pi.Duration * math.Ceil(6/math.Max(pi.Duration, 0.1)+1)
	}
	args = append(args, "-i", src)
	tag := "phone"
	if short && dur > 14.5 {
		args = append(args, "-t", "14.5")
		dur = 14.5
		tag = "phone_15s"
	}
	fc := "[0:v]split[x][y];[x]scale=-2:3120,crop=1440:3120,boxblur=40:2,eq=brightness=-0.08[bg];" +
		"[y]scale=1440:-2:flags=lanczos[fg];[bg][fg]overlay=0:(H-h)/2,format=yuv420p[v]"
	args = append(args, "-filter_complex", fc, "-map", "[v]")
	if pi.Audio {
		args = append(args, "-map", "0:a:0", "-c:a", "aac")
	}
	out := outName(outDir, src, tag, ".mp4")
	args = append(args, "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-movflags", "+faststart",
		"-progress", "pipe:1", "-nostats", out)
	if err := runProgress(ctx, t.FFmpeg, args, dur, func(p float64) { prog("export", p) }); err != nil {
		return "", err
	}
	return out, nil
}

// exportGIF makes a shareable GIF (max 720px wide, 20 fps, max 10 s).
func exportGIF(ctx context.Context, t Tools, src, outDir string, prog progressFn) (string, error) {
	prog("probe", 0)
	pi, err := probe(ctx, t, src)
	if err != nil {
		return "", err
	}
	dur := math.Min(pi.Duration, 10)
	w := min(720, pi.W)
	out := outName(outDir, src, "share", ".gif")
	args := []string{"-v", "error", "-y", "-t", strconv.FormatFloat(dur, 'f', 2, 64), "-i", src,
		"-vf", fmt.Sprintf("fps=20,scale=%d:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4", w),
		"-loop", "0", "-progress", "pipe:1", "-nostats", out}
	if err := runProgress(ctx, t.FFmpeg, args, dur, func(p float64) { prog("export", p) }); err != nil {
		return "", err
	}
	return out, nil
}
