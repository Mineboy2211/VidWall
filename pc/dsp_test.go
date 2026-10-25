package main

import (
	"math"
	"testing"
)

func TestSpectrum(t *testing.T) {
	for _, f := range []float64{60, 440, 5000} {
		ring := make([]float64, 2048)
		for i := range ring {
			ring[i] = 0.5 * math.Sin(2*math.Pi*f*float64(i)/48000)
		}
		b := spectrum(ring, 0, 48000, 48)
		best := 0
		for i := range b {
			if b[i] > b[best] {
				best = i
			}
		}
		lo := 35 * math.Pow(16000/35.0, float64(best)/48)
		t.Logf("%5.0f Hz -> band %d (starts %.0f Hz) level %.2f, band0 %.2f band47 %.2f", f, best, lo, b[best], b[0], b[47])
	}
	silent := spectrum(make([]float64, 2048), 0, 48000, 48)
	t.Logf("silence max %.2f", silent[10])
}
