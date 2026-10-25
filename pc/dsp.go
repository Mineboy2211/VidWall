package main

import (
	"math"
	"math/cmplx"
)

// spectrum: Hann window + FFT, mapped to log-spaced bands, 0..1
func spectrum(ring []float64, pos int, rate float64, nb int) []float64 {
	N := len(ring)
	x := make([]complex128, N)
	for i := 0; i < N; i++ {
		w := 0.5 - 0.5*math.Cos(2*math.Pi*float64(i)/float64(N-1))
		x[i] = complex(ring[(pos+i)%N]*w, 0)
	}
	fft(x)
	mag := make([]float64, N/2)
	for i := range mag {
		mag[i] = cmplx.Abs(x[i]) / float64(N) * 4
	}
	out := make([]float64, nb)
	fmin, fmax := 35.0, 16000.0
	for b := 0; b < nb; b++ {
		f0 := fmin * math.Pow(fmax/fmin, float64(b)/float64(nb))
		f1 := fmin * math.Pow(fmax/fmin, float64(b+1)/float64(nb))
		i0 := int(f0 / rate * float64(N))
		i1 := max(i0+1, int(f1/rate*float64(N)))
		var m float64
		for i := i0; i < i1 && i < len(mag); i++ {
			m = math.Max(m, mag[i])
		}
		// louder treble compensation + dB scale
		m *= 1 + float64(b)/float64(nb)*2.5
		db := 20 * math.Log10(m+1e-9)
		out[b] = math.Max(0, math.Min(1, (db+60)/56))
	}
	return out
}

func fft(a []complex128) {
	n := len(a)
	for i, j := 1, 0; i < n; i++ {
		bit := n >> 1
		for ; j&bit != 0; bit >>= 1 {
			j ^= bit
		}
		j ^= bit
		if i < j {
			a[i], a[j] = a[j], a[i]
		}
	}
	for length := 2; length <= n; length <<= 1 {
		ang := -2 * math.Pi / float64(length)
		wl := complex(math.Cos(ang), math.Sin(ang))
		for i := 0; i < n; i += length {
			w := complex(1, 0)
			for k := 0; k < length/2; k++ {
				u, v := a[i+k], a[i+k+length/2]*w
				a[i+k], a[i+k+length/2] = u+v, u-v
				w *= wl
			}
		}
	}
}
