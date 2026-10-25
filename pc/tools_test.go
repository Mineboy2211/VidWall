//go:build !windows

package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestRemoteUnzipRIFE(t *testing.T) {
	dir := t.TempDir()
	start := time.Now()
	var last float64
	exe, model, err := ensureRIFE(context.Background(), dir, func(ph string, p float64) { last = p })
	if err != nil {
		t.Fatal(err)
	}
	var total int64
	filepath.Walk(filepath.Join(dir, "rife"), func(p string, info os.FileInfo, err error) error {
		if !info.IsDir() {
			total += info.Size()
			t.Logf("%s %d", p[len(dir):], info.Size())
		}
		return nil
	})
	t.Logf("exe=%v model=%v total=%d MB in %s, last progress %.2f", fileExists(exe), fileExists(filepath.Join(model, "flownet.bin")), total>>20, time.Since(start), last)
}

func TestTools(t *testing.T) {
	keep := os.Getenv("KEEP_DIR")
	tools := Tools{FFmpeg: "ffmpeg", FFprobe: "ffprobe"}
	ctx := context.Background()
	lg := func(ph string, p float64) {}
	src := os.Getenv("SRC_VIDEO")
	gif := os.Getenv("SRC_GIF")

	out, err := interpolate60(ctx, tools, os.Getenv("RIFE"), os.Getenv("RIFE_MODEL"), gif, keep, lg)
	if err != nil {
		t.Fatal("interp:", err)
	}
	pi, _ := probe(ctx, tools, out)
	t.Logf("interp gif -> %s %+v", filepath.Base(out), pi)

	out, err = editVideo(ctx, tools, src, keep, EditOpts{Start: 0.5, End: 3.5, Brightness: 0.05, Contrast: 1.1, Saturation: 1.3, Logo: []int{600, 350, 120, 50}}, lg)
	if err != nil {
		t.Fatal("edit:", err)
	}
	pi, _ = probe(ctx, tools, out)
	t.Logf("edit -> %s %+v", filepath.Base(out), pi)

	out, err = editVideo(ctx, tools, src, keep, EditOpts{Loop: true, LoopSeconds: 1, Contrast: 1, Saturation: 1}, lg)
	if err != nil {
		t.Fatal("loop:", err)
	}
	pi, _ = probe(ctx, tools, out)
	t.Logf("loop -> %s %+v", filepath.Base(out), pi)

	out, err = exportPhone(ctx, tools, gif, keep, true, lg)
	if err != nil {
		t.Fatal("phone:", err)
	}
	pi, _ = probe(ctx, tools, out)
	t.Logf("phone -> %s %+v", filepath.Base(out), pi)

	out, err = exportGIF(ctx, tools, src, keep, lg)
	if err != nil {
		t.Fatal("gif:", err)
	}
	st, _ := os.Stat(out)
	t.Logf("gif -> %s %d KB", filepath.Base(out), st.Size()>>10)
}
