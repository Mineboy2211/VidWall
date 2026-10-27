//go:build windows

package main

// Loading screen shown while VidWall starts (instead of empty/white windows).
// It's a native layered window (no web engine), so it appears instantly.

import (
	"bytes"
	_ "embed"
	"image"
	"image/draw"
	"image/png"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

//go:embed splash_card.png
var splashCardPNG []byte

//go:embed splash_spin.png
var splashSpinPNG []byte

const (
	splashK  = 2   // the images are drawn at 2x
	splashCX = 436 // ring center in the card image (2x pixels)
	splashCY = 248
)

var (
	gdi32                = windows.NewLazySystemDLL("gdi32.dll")
	pCreateCompatibleDC  = gdi32.NewProc("CreateCompatibleDC")
	pCreateDIBSection    = gdi32.NewProc("CreateDIBSection")
	pSelectObject        = gdi32.NewProc("SelectObject")
	pDeleteObject        = gdi32.NewProc("DeleteObject")
	pDeleteDC            = gdi32.NewProc("DeleteDC")
	pUpdateLayeredWindow = windows.NewLazySystemDLL("user32.dll").NewProc("UpdateLayeredWindow")
	pGetDC               = windows.NewLazySystemDLL("user32.dll").NewProc("GetDC")
	pReleaseDC           = windows.NewLazySystemDLL("user32.dll").NewProc("ReleaseDC")
	pDestroyWindowS      = windows.NewLazySystemDLL("user32.dll").NewProc("DestroyWindow")
	pKillTimerS          = windows.NewLazySystemDLL("user32.dll").NewProc("KillTimer")
	pDefWindowProcS      = windows.NewLazySystemDLL("user32.dll").NewProc("DefWindowProcW")
)

type splashWin struct {
	hwnd       uintptr
	memDC, bmp uintptr
	bits       []byte // the window's pixels (premultiplied BGRA, top-down)
	card       []byte
	spin       [][]byte
	w, h       int
	sw         int // spinner frame size
	sx, sy     int // spinner position
	frame      int
	alpha      int
	closing    bool
	shownAt    time.Time
}

var splash *splashWin

// toBGRA converts an image to premultiplied BGRA, scaled by f (box filter).
func toBGRA(src image.Image, f float64) ([]byte, int, int) {
	rgba := image.NewNRGBA(src.Bounds())
	draw.Draw(rgba, rgba.Bounds(), src, src.Bounds().Min, draw.Src)
	sw, sh := rgba.Bounds().Dx(), rgba.Bounds().Dy()
	dw, dh := max(1, int(float64(sw)*f+0.5)), max(1, int(float64(sh)*f+0.5))
	out := make([]byte, dw*dh*4)
	for y := 0; y < dh; y++ {
		y0, y1 := int(float64(y)/f), int(float64(y+1)/f)
		y1 = max(y0+1, min(y1, sh))
		for x := 0; x < dw; x++ {
			x0, x1 := int(float64(x)/f), int(float64(x+1)/f)
			x1 = max(x0+1, min(x1, sw))
			var r, g, b, a, n float64
			for yy := y0; yy < y1 && yy < sh; yy++ {
				for xx := x0; xx < x1 && xx < sw; xx++ {
					i := yy*rgba.Stride + xx*4
					al := float64(rgba.Pix[i+3]) / 255
					r += float64(rgba.Pix[i]) * al
					g += float64(rgba.Pix[i+1]) * al
					b += float64(rgba.Pix[i+2]) * al
					a += al
					n++
				}
			}
			o := (y*dw + x) * 4
			if n > 0 {
				out[o], out[o+1], out[o+2], out[o+3] = byte(b/n+0.5), byte(g/n+0.5), byte(r/n+0.5), byte(a/n*255+0.5)
			}
		}
	}
	return out, dw, dh
}

func splashProc(h, msg, wp, lp uintptr) uintptr {
	if msg == 0x0113 && splash != nil && h == splash.hwnd { // WM_TIMER
		splash.tick()
		return 0
	}
	r, _, _ := pDefWindowProcS.Call(h, msg, wp, lp)
	return r
}

// showSplash displays the loading screen, centered on the primary screen.
func showSplash(dpiScale float64) {
	cardImg, err1 := png.Decode(bytes.NewReader(splashCardPNG))
	spinImg, err2 := png.Decode(bytes.NewReader(splashSpinPNG))
	if err1 != nil || err2 != nil {
		return
	}
	f := dpiScale / splashK
	s := &splashWin{shownAt: time.Now()}
	s.card, s.w, s.h = toBGRA(cardImg, f)
	fs := spinImg.Bounds().Dx()
	n := spinImg.Bounds().Dy() / fs
	for i := 0; i < n; i++ {
		sub := image.NewNRGBA(image.Rect(0, 0, fs, fs))
		draw.Draw(sub, sub.Bounds(), spinImg, image.Pt(0, i*fs), draw.Src)
		b, w, _ := toBGRA(sub, f)
		s.spin = append(s.spin, b)
		s.sw = w
	}
	s.sx, s.sy = int(float64(splashCX)*f)-s.sw/2, int(float64(splashCY)*f)-s.sw/2

	var hinst windows.Handle
	_ = windows.GetModuleHandleEx(0, nil, &hinst)
	cls := u16("VidWallSplash")
	wc := wndClassEx{WndProc: syscall.NewCallback(splashProc), Instance: hinst, ClassName: cls}
	wc.Size = uint32(unsafe.Sizeof(wc))
	pRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc)))
	ms := monitors()
	x, y := 100, 100
	if len(ms) > 0 {
		x, y = int(ms[0].X)+(int(ms[0].W)-s.w)/2, int(ms[0].Y)+(int(ms[0].H)-s.h)/2
	}
	// WS_EX_LAYERED | WS_EX_TOPMOST | WS_EX_TOOLWINDOW, WS_POPUP
	s.hwnd, _, _ = pCreateWindowExW.Call(0x80000|0x8|0x80, uintptr(unsafe.Pointer(cls)), uintptr(unsafe.Pointer(u16("VidWall"))),
		0x80000000, uintptr(x), uintptr(y), uintptr(s.w), uintptr(s.h), 0, 0, uintptr(hinst), 0)
	if s.hwnd == 0 {
		return
	}
	// 32-bit top-down DIB the window is drawn from
	type bmiHeader struct {
		Size                                uint32
		Width, Height                       int32
		Planes, BitCount                    uint16
		Compression, SizeImage              uint32
		XPels, YPels, ClrUsed, ClrImportant int32
	}
	bi := bmiHeader{Width: int32(s.w), Height: -int32(s.h), Planes: 1, BitCount: 32}
	bi.Size = uint32(unsafe.Sizeof(bi))
	screen, _, _ := pGetDC.Call(0)
	s.memDC, _, _ = pCreateCompatibleDC.Call(screen)
	pReleaseDC.Call(0, screen)
	var bits uintptr
	s.bmp, _, _ = pCreateDIBSection.Call(s.memDC, uintptr(unsafe.Pointer(&bi)), 0, uintptr(unsafe.Pointer(&bits)), 0, 0)
	if s.bmp == 0 || bits == 0 {
		pDestroyWindowS.Call(s.hwnd)
		return
	}
	pSelectObject.Call(s.memDC, s.bmp)
	s.bits = unsafe.Slice((*byte)(unsafe.Pointer(bits)), s.w*s.h*4)
	splash = s
	s.render()
	pShowWindow.Call(s.hwnd, 4) // SW_SHOWNOACTIVATE
	pSetTimer.Call(s.hwnd, 1, 33, 0)
}

func (s *splashWin) render() {
	copy(s.bits, s.card)
	sp := s.spin[s.frame%len(s.spin)]
	for y := 0; y < s.sw; y++ {
		ty := s.sy + y
		if ty < 0 || ty >= s.h {
			continue
		}
		for x := 0; x < s.sw; x++ {
			tx := s.sx + x
			if tx < 0 || tx >= s.w {
				continue
			}
			si, di := (y*s.sw+x)*4, (ty*s.w+tx)*4
			a := int(sp[si+3])
			if a == 0 {
				continue
			}
			for c := 0; c < 4; c++ { // premultiplied "over"
				s.bits[di+c] = byte(int(sp[si+c]) + int(s.bits[di+c])*(255-a)/255)
			}
		}
	}
	type blend struct{ Op, Flags, Alpha, Format byte }
	type pt struct{ X, Y int32 }
	type sz struct{ W, H int32 }
	bl := blend{0, 0, byte(s.alpha), 1} // AC_SRC_OVER, AC_SRC_ALPHA
	size := sz{int32(s.w), int32(s.h)}
	src := pt{}
	pUpdateLayeredWindow.Call(s.hwnd, 0, 0, uintptr(unsafe.Pointer(&size)), s.memDC, uintptr(unsafe.Pointer(&src)), 0, uintptr(unsafe.Pointer(&bl)), 2) // ULW_ALPHA
}

func (s *splashWin) tick() {
	s.frame++
	if s.closing {
		s.alpha -= 40
		if s.alpha <= 0 {
			s.destroy()
			return
		}
	} else if s.alpha < 255 {
		s.alpha = min(255, s.alpha+50)
	}
	s.render()
}

func (s *splashWin) destroy() {
	pKillTimerS.Call(s.hwnd, 1)
	pDestroyWindowS.Call(s.hwnd)
	pDeleteDC.Call(s.memDC)
	pDeleteObject.Call(s.bmp)
	if splash == s {
		splash = nil
	}
}

// closeSplash fades the loading screen out (it stays at least 0.8 s so it doesn't just blink).
func closeSplash() {
	s := splash
	if s == nil || s.closing {
		return
	}
	if wait := 800*time.Millisecond - time.Since(s.shownAt); wait > 0 {
		go func() { time.Sleep(wait); wv.Dispatch(closeSplash) }()
		return
	}
	s.closing = true
}
