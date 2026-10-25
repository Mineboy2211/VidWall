package main

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ---------- tools (downloaded on first use) ----------

var (
	esrganURL  = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-windows.zip"
	ffmpegURL  = "https://github.com/GyanD/codexffmpeg/releases/download/7.1/ffmpeg-7.1-essentials_build.zip"
	exeSuffix  = ".exe"
	chunkSize  = 90
	errAlready = errors.New("already at max resolution")
)

type Tools struct {
	FFmpeg, FFprobe, ESRGAN, Models string
}

func toolPaths(dir string) Tools {
	return Tools{
		FFmpeg:  filepath.Join(dir, "ffmpeg", "ffmpeg"+exeSuffix),
		FFprobe: filepath.Join(dir, "ffmpeg", "ffprobe"+exeSuffix),
		ESRGAN:  filepath.Join(dir, "realesrgan", "realesrgan-ncnn-vulkan"+exeSuffix),
		Models:  filepath.Join(dir, "realesrgan", "models"),
	}
}

func fileExists(p string) bool { _, err := os.Stat(p); return err == nil }

type progressFn func(phase string, p float64)

func download(ctx context.Context, url, dst string, prog func(p float64)) error {
	req, _ := http.NewRequestWithContext(ctx, "GET", url, nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("téléchargement impossible (%s)", resp.Status)
	}
	f, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer f.Close()
	total := resp.ContentLength
	var done int64
	buf := make([]byte, 256<<10)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, err := f.Write(buf[:n]); err != nil {
				return err
			}
			done += int64(n)
			if total > 0 {
				prog(float64(done) / float64(total))
			}
		}
		if rerr == io.EOF {
			return nil
		}
		if rerr != nil {
			return rerr
		}
	}
}

// unzip extracts entries; keep(name) returns the destination path (relative to dst) or "" to skip.
func unzip(src, dst string, keep func(name string) string) error {
	r, err := zip.OpenReader(src)
	if err != nil {
		return err
	}
	defer r.Close()
	for _, f := range r.File {
		if f.FileInfo().IsDir() {
			continue
		}
		rel := keep(f.Name)
		if rel == "" {
			continue
		}
		out := filepath.Join(dst, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(out), 0755); err != nil {
			return err
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		w, err := os.Create(out)
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
		_ = os.Chmod(out, 0755)
	}
	return nil
}

func ensureTools(ctx context.Context, dir string, prog progressFn) (Tools, error) {
	t, err := ensureFFmpeg(ctx, dir, prog)
	if err != nil {
		return t, err
	}
	if !fileExists(t.ESRGAN) {
		z := filepath.Join(dir, "esrgan.zip")
		if err := download(ctx, esrganURL, z, func(p float64) { prog("download_ai", p) }); err != nil {
			return t, fmt.Errorf("téléchargement de l'IA : %w", err)
		}
		err := unzip(z, filepath.Join(dir, "realesrgan"), func(n string) string {
			if strings.HasSuffix(n, ".mp4") || strings.HasSuffix(n, ".jpg") {
				return ""
			}
			return n
		})
		os.Remove(z)
		if err != nil {
			return t, err
		}
	}
	if !fileExists(t.ESRGAN) {
		return t, errors.New("IA introuvable après téléchargement")
	}
	return t, nil
}

func commandContext(ctx context.Context, name string, args ...string) *exec.Cmd {
	c := exec.CommandContext(ctx, name, args...)
	hideWindow(c)
	return c
}

// ---------- probing ----------

type probeInfo struct {
	W, H     int
	FPS      float64
	FPSStr   string
	Frames   int
	Duration float64
	Audio    bool
}

func run(ctx context.Context, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	hideWindow(cmd)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		msg := strings.TrimSpace(stderr.String())
		if len(msg) > 400 {
			msg = msg[len(msg)-400:]
		}
		return out, fmt.Errorf("%s: %v %s", filepath.Base(name), err, msg)
	}
	return out, nil
}

func parseRate(s string) float64 {
	p := strings.Split(s, "/")
	if len(p) == 2 {
		a, _ := strconv.ParseFloat(p[0], 64)
		b, _ := strconv.ParseFloat(p[1], 64)
		if b > 0 {
			return a / b
		}
	}
	f, _ := strconv.ParseFloat(s, 64)
	return f
}

func probe(ctx context.Context, t Tools, src string) (probeInfo, error) {
	var pi probeInfo
	out, err := run(ctx, t.FFprobe, "-v", "error", "-show_entries",
		"stream=codec_type,width,height,avg_frame_rate,r_frame_rate:format=duration", "-of", "json", src)
	if err != nil {
		return pi, err
	}
	var j struct {
		Streams []struct {
			CodecType string `json:"codec_type"`
			Width     int    `json:"width"`
			Height    int    `json:"height"`
			Avg       string `json:"avg_frame_rate"`
			R         string `json:"r_frame_rate"`
		} `json:"streams"`
		Format struct {
			Duration string `json:"duration"`
		} `json:"format"`
	}
	if err := json.Unmarshal(out, &j); err != nil {
		return pi, err
	}
	for _, s := range j.Streams {
		switch s.CodecType {
		case "video":
			if pi.W == 0 {
				pi.W, pi.H = s.Width, s.Height
				fps := parseRate(s.Avg)
				if fps <= 0 || fps > 240 {
					fps = parseRate(s.R)
				}
				if fps <= 0 || fps > 240 {
					fps = 30
				}
				pi.FPS = fps
			}
		case "audio":
			pi.Audio = true
		}
	}
	pi.Duration, _ = strconv.ParseFloat(j.Format.Duration, 64)
	if pi.W == 0 {
		return pi, errors.New("aucune piste vidéo trouvée")
	}
	// round the frame rate to something clean (e.g. 29.97 stays, 19.99 -> 20)
	pi.FPS = math.Round(pi.FPS*1000) / 1000
	pi.FPSStr = strconv.FormatFloat(pi.FPS, 'f', -1, 64)
	pi.Frames = int(math.Round(pi.Duration * pi.FPS))
	if pi.Frames < 1 {
		pi.Frames = 1
	}
	return pi, nil
}

func even(v float64) int {
	n := int(math.Round(v))
	if n%2 == 1 {
		n++
	}
	return n
}

// ---------- the upscale pipeline ----------

type UpscaleResult struct {
	Out          string
	SrcW, SrcH   int
	OutW, OutH   int
	AIScale      int
}

// upscaleVideo upscales src so that it covers targetW x targetH, chunk by chunk to keep disk use low.
func upscaleVideo(ctx context.Context, t Tools, src, outDir string, targetW, targetH int, model string, prog progressFn) (UpscaleResult, error) {
	var res UpscaleResult
	prog("probe", 0)
	pi, err := probe(ctx, t, src)
	if err != nil {
		return res, err
	}
	res.SrcW, res.SrcH = pi.W, pi.H
	need := math.Max(float64(targetW)/float64(pi.W), float64(targetH)/float64(pi.H))
	if need <= 1.05 {
		return res, errAlready
	}
	s := 4
	if model == "realesr-animevideov3" {
		if need <= 2 {
			s = 2
		} else if need <= 3 {
			s = 3
		}
	}
	res.AIScale = s
	fw, fh := even(float64(pi.W)*need), even(float64(pi.H)*need)
	res.OutW, res.OutH = fw, fh

	_ = os.MkdirAll(outDir, 0755)
	work := filepath.Join(outDir, fmt.Sprintf("work_%d", time.Now().UnixNano()))
	in, up := filepath.Join(work, "in"), filepath.Join(work, "up")
	defer os.RemoveAll(work)

	var segs []string
	done := 0
	for start := 0; ; start += chunkSize {
		if err := ctx.Err(); err != nil {
			return res, err
		}
		_ = os.RemoveAll(in)
		_ = os.RemoveAll(up)
		_ = os.MkdirAll(in, 0755)
		_ = os.MkdirAll(up, 0755)

		// 1. extract this chunk of frames (constant frame rate)
		vf := fmt.Sprintf("fps=%s,select='between(n\\,%d\\,%d)'", pi.FPSStr, start, start+chunkSize-1)
		if _, err := run(ctx, t.FFmpeg, "-v", "error", "-i", src, "-vf", vf, "-fps_mode", "passthrough",
			"-frames:v", strconv.Itoa(chunkSize), "-start_number", "0", filepath.Join(in, "%06d.png")); err != nil {
			return res, err
		}
		entries, _ := os.ReadDir(in)
		n := len(entries)
		if n == 0 {
			break
		}

		// 2. AI upscale (progress = number of finished output frames)
		stop := make(chan struct{})
		go func(base int) {
			tk := time.NewTicker(400 * time.Millisecond)
			defer tk.Stop()
			for {
				select {
				case <-stop:
					return
				case <-tk.C:
					e, _ := os.ReadDir(up)
					total := max(pi.Frames, base+n)
					prog("upscale", float64(base+len(e))/float64(total))
				}
			}
		}(done)
		_, err := run(ctx, t.ESRGAN, "-i", in, "-o", up, "-s", strconv.Itoa(s), "-n", model, "-m", t.Models, "-f", "png")
		close(stop)
		if err != nil {
			return res, fmt.Errorf("upscale IA : %w", err)
		}

		// 3. encode this chunk at the final size
		seg := filepath.Join(work, fmt.Sprintf("seg_%05d.mp4", len(segs)))
		if _, err := run(ctx, t.FFmpeg, "-v", "error", "-y", "-framerate", pi.FPSStr, "-start_number", "0",
			"-i", filepath.Join(up, "%06d.png"),
			"-vf", fmt.Sprintf("scale=%d:%d:flags=lanczos,format=yuv420p", fw, fh),
			"-c:v", "libx264", "-preset", "medium", "-crf", "16", seg); err != nil {
			return res, err
		}
		segs = append(segs, seg)
		done += n
		prog("upscale", float64(done)/float64(max(pi.Frames, done)))
		if n < chunkSize {
			break
		}
	}
	if len(segs) == 0 {
		return res, errors.New("aucune image extraite")
	}

	// 4. join chunks (+ original audio)
	prog("finish", 1)
	list := filepath.Join(work, "list.txt")
	var sb strings.Builder
	for _, s := range segs {
		sb.WriteString("file '" + strings.ReplaceAll(filepath.ToSlash(s), "'", "'\\''") + "'\n")
	}
	_ = os.WriteFile(list, []byte(sb.String()), 0644)

	base := strings.TrimSuffix(filepath.Base(src), filepath.Ext(src))
	out := filepath.Join(outDir, fmt.Sprintf("%s_HD_%dx%d.mp4", base, fw, fh))
	args := []string{"-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", list}
	if pi.Audio {
		args = append(args, "-i", src, "-map", "0:v:0", "-map", "1:a:0", "-c:a", "aac", "-b:a", "192k", "-shortest")
	}
	args = append(args, "-c:v", "copy", "-movflags", "+faststart", out)
	if _, err := run(ctx, t.FFmpeg, args...); err != nil {
		return res, err
	}
	res.Out = out
	return res, nil
}

// ---------- job state (shared with the UI) ----------

type UpscaleJob struct {
	mu       sync.Mutex
	Running  bool    `json:"running"`
	Kind     string  `json:"kind"`
	Phase    string  `json:"phase"`
	Progress float64 `json:"progress"`
	Src      string  `json:"src"`
	Err      string  `json:"err"`
	Done     string  `json:"done"`
	Started  int64   `json:"started"`
	cancel   context.CancelFunc
}

func (j *UpscaleJob) snapshot() map[string]any {
	j.mu.Lock()
	defer j.mu.Unlock()
	return map[string]any{"running": j.Running, "kind": j.Kind, "phase": j.Phase, "progress": j.Progress,
		"src": filepath.Base(j.Src), "err": j.Err, "done": filepath.Base(j.Done), "donePath": j.Done, "started": j.Started}
}

func (j *UpscaleJob) set(phase string, p float64) {
	j.mu.Lock()
	j.Phase, j.Progress = phase, p
	j.mu.Unlock()
}
