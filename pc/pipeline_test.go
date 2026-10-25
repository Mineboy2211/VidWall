//go:build !windows

package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

// Uses a stand-in "AI" (ffmpeg scaling, same CLI) to validate the whole pipeline on Linux.
func TestPipeline(t *testing.T) {
	dir := t.TempDir()
	tools := Tools{FFmpeg: "ffmpeg", FFprobe: "ffprobe", ESRGAN: os.Getenv("FAKE_ESRGAN"), Models: dir}
	for _, src := range []string{os.Getenv("SRC_VIDEO"), os.Getenv("SRC_GIF")} {
		var last string
		res, err := upscaleVideo(context.Background(), tools, src, filepath.Join(dir, "out"), 2560, 1440,
			"realesr-animevideov3", func(ph string, p float64) {
				if ph != last {
					t.Logf("phase %s %.2f", ph, p)
					last = ph
				}
			})
		if err != nil {
			t.Fatalf("%s: %v", src, err)
		}
		t.Logf("%s -> %+v", filepath.Base(src), res)
		pi, err := probe(context.Background(), tools, res.Out)
		if err != nil {
			t.Fatal(err)
		}
		t.Logf("output probe %+v", pi)
		_ = os.Rename(res.Out, filepath.Join(os.Getenv("KEEP_DIR"), filepath.Base(res.Out)))
	}
}
