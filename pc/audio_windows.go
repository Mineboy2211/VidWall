//go:build windows

package main

// System audio capture (WASAPI loopback) for the audio visualizer.
// Pure Go COM calls through vtables, no cgo.

import (
	"errors"
	"log"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	ole32              = windows.NewLazySystemDLL("ole32.dll")
	pCoInitializeEx    = ole32.NewProc("CoInitializeEx")
	pCoCreateInstance  = ole32.NewProc("CoCreateInstance")
	pCoTaskMemFree     = ole32.NewProc("CoTaskMemFree")
	clsidMMDeviceEnum  = windows.GUID{Data1: 0xBCDE0395, Data2: 0xE52F, Data3: 0x467C, Data4: [8]byte{0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E}}
	iidIMMDeviceEnum   = windows.GUID{Data1: 0xA95664D2, Data2: 0x9614, Data3: 0x4F35, Data4: [8]byte{0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6}}
	iidIAudioClient    = windows.GUID{Data1: 0x1CB9AD4C, Data2: 0xDBFA, Data3: 0x4C32, Data4: [8]byte{0xB1, 0x78, 0xC2, 0xF5, 0x68, 0xA7, 0x03, 0xB2}}
	iidIAudioCapture   = windows.GUID{Data1: 0xC8ADBD64, Data2: 0xE71E, Data3: 0x48A0, Data4: [8]byte{0xA4, 0xDE, 0x18, 0x5C, 0x39, 0x5C, 0xD3, 0x17}}
	errAudioInvalidate = errors.New("device invalidated")
)

type waveFormatEx struct {
	FormatTag      uint16
	Channels       uint16
	SamplesPerSec  uint32
	AvgBytesPerSec uint32
	BlockAlign     uint16
	BitsPerSample  uint16
	CbSize         uint16
}

// call method #idx of a COM object
func comCall(obj uintptr, idx int, args ...uintptr) uintptr {
	vtbl := *(*uintptr)(unsafe.Pointer(obj))
	fn := *(*uintptr)(unsafe.Pointer(vtbl + uintptr(idx)*unsafe.Sizeof(uintptr(0))))
	r, _, _ := syscall.SyscallN(fn, append([]uintptr{obj}, args...)...)
	return r
}

func comRelease(obj uintptr) {
	if obj != 0 {
		comCall(obj, 2)
	}
}

type audioViz struct {
	mu      sync.Mutex
	enabled bool
	levels  []float64
	source  string // see audiosrc_windows.go
	restart bool   // source changed: reopen the capture
	status  string // shown in the settings ("Écoute : Discord", or an error)
}

func (a *audioViz) setSource(src string) {
	a.mu.Lock()
	if src != a.source {
		a.source, a.restart = src, true
	}
	a.mu.Unlock()
}

func (a *audioViz) getSource() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.restart = false
	return a.source
}

func (a *audioViz) mustRestart() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.restart
}

func (a *audioViz) setStatus(s string) {
	a.mu.Lock()
	a.status = s
	a.mu.Unlock()
}

func (a *audioViz) getStatus() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.status
}

var viz = &audioViz{levels: make([]float64, 64)}

func (a *audioViz) setEnabled(on bool) {
	a.mu.Lock()
	a.enabled = on
	a.mu.Unlock()
}

func (a *audioViz) isEnabled() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.enabled
}

// startAudio runs forever in its own OS thread; capture only happens while enabled.
func startAudio(send func([]float64)) {
	go func() {
		runtime.LockOSThread()
		pCoInitializeEx.Call(0, 0) // COINIT_MULTITHREADED
		for {
			if !viz.isEnabled() {
				time.Sleep(500 * time.Millisecond)
				continue
			}
			err := captureLoop(send)
			if err != nil {
				log.Println("audio:", err)
				viz.setStatus("⚠ " + err.Error())
			}
			for i := 0; i < 15 && !viz.mustRestart(); i++ { // retry in 1.5 s, or right away if the source changed
				time.Sleep(100 * time.Millisecond)
			}
		}
	}()
}

func captureLoop(send func([]float64)) error {
	var enum, dev, client, capture uintptr
	var event windows.Handle
	defer func() {
		comRelease(capture)
		comRelease(client)
		comRelease(dev)
		comRelease(enum)
		if event != 0 {
			windows.CloseHandle(event)
		}
	}()

	if hr, _, _ := pCoCreateInstance.Call(uintptr(unsafe.Pointer(&clsidMMDeviceEnum)), 0, 23,
		uintptr(unsafe.Pointer(&iidIMMDeviceEnum)), uintptr(unsafe.Pointer(&enum))); hr != 0 {
		return errors.New("CoCreateInstance failed")
	}
	src := viz.getSource()
	const loopback, eventCB, autoConvert, srcDefault = 0x00020000, 0x00040000, 0x80000000, 0x08000000
	flags := uintptr(loopback)
	label := "tout le son du PC"
	var pwfx *waveFormatEx
	appMode := strings.HasPrefix(src, "app:")
	getDev := func(id string, flow uintptr) error {
		if id != "" {
			p, _ := windows.UTF16PtrFromString(id)
			if comCall(enum, 5, uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(&dev))) == 0 { // GetDevice
				return nil
			}
		}
		if hr := comCall(enum, 4, flow, 0, uintptr(unsafe.Pointer(&dev))); hr != 0 { // GetDefaultAudioEndpoint
			return errors.New("aucun périphérique audio")
		}
		return nil
	}
	switch {
	case appMode:
		exe := strings.TrimPrefix(src, "app:")
		label = appName(exe)
		pid, ok := rootProcess(exe)
		if !ok {
			return errors.New(label + " n'est pas lancé")
		}
		c, err := activateProcessLoopback(pid)
		if err != nil {
			return err
		}
		client = c
		// process loopback has no mix format: ask for 48 kHz stereo 16-bit
		pwfx = &waveFormatEx{FormatTag: 1, Channels: 2, SamplesPerSec: 48000, AvgBytesPerSec: 48000 * 4, BlockAlign: 4, BitsPerSample: 16}
		flags = loopback | eventCB | autoConvert | srcDefault
	case strings.HasPrefix(src, "in:"):
		if err := getDev(strings.TrimPrefix(src, "in:"), 1); err != nil {
			return err
		}
		flags = 0
		label = "micro : " + deviceName(dev)
	default:
		if err := getDev(strings.TrimPrefix(src, "out:"), 0); err != nil {
			return err
		}
		if src != "" {
			label = "sortie : " + deviceName(dev)
		}
	}
	if !appMode {
		if hr := comCall(dev, 3, uintptr(unsafe.Pointer(&iidIAudioClient)), 23, 0, uintptr(unsafe.Pointer(&client))); hr != 0 {
			return errors.New("Activate failed")
		}
		if hr := comCall(client, 8, uintptr(unsafe.Pointer(&pwfx))); hr != 0 || pwfx == nil { // GetMixFormat
			return errors.New("GetMixFormat failed")
		}
		defer pCoTaskMemFree.Call(uintptr(unsafe.Pointer(pwfx)))
	}
	ch := int(pwfx.Channels)
	rate := float64(pwfx.SamplesPerSec)
	bits := int(pwfx.BitsPerSample)
	isFloat := pwfx.FormatTag == 3
	if pwfx.FormatTag == 0xFFFE { // WAVE_FORMAT_EXTENSIBLE: subformat GUID at offset 24
		sub := *(*uint32)(unsafe.Pointer(uintptr(unsafe.Pointer(pwfx)) + 24))
		isFloat = sub == 3
	}
	if hr := comCall(client, 3, 0, flags, uintptr(2000000), 0, uintptr(unsafe.Pointer(pwfx)), 0); hr != 0 { // Initialize (200 ms buffer)
		return errors.New("impossible d'ouvrir la source du son")
	}
	if flags&eventCB != 0 {
		event, _ = windows.CreateEvent(nil, 0, 0, nil)
		comCall(client, 13, uintptr(event)) // SetEventHandle
	}
	if hr := comCall(client, 14, uintptr(unsafe.Pointer(&iidIAudioCapture)), uintptr(unsafe.Pointer(&capture))); hr != 0 {
		return errors.New("GetService failed")
	}
	if hr := comCall(client, 10); hr != 0 { // Start
		return errors.New("Start failed")
	}
	defer comCall(client, 11) // Stop
	log.Printf("audio: %s, %d ch, %.0f Hz, %d bit, float=%v", label, ch, rate, bits, isFloat)
	viz.setStatus("🎧 Écoute : " + label)

	const N = 2048
	ring := make([]float64, N)
	pos := 0
	smooth := make([]float64, len(viz.levels))
	lastSend := time.Now()
	lastAudio := time.Now()

	for viz.isEnabled() && !viz.mustRestart() {
		var packet uint32
		if hr := comCall(capture, 5, uintptr(unsafe.Pointer(&packet))); hr != 0 { // GetNextPacketSize
			return errAudioInvalidate
		}
		for packet > 0 {
			var data uintptr
			var frames, flags uint32
			if hr := comCall(capture, 3, uintptr(unsafe.Pointer(&data)), uintptr(unsafe.Pointer(&frames)),
				uintptr(unsafe.Pointer(&flags)), 0, 0); hr != 0 { // GetBuffer
				return errAudioInvalidate
			}
			silent := flags&2 != 0
			for i := 0; i < int(frames); i++ {
				var v float64
				if !silent && data != 0 {
					for c := 0; c < ch; c++ {
						off := uintptr((i*ch + c) * bits / 8)
						switch {
						case isFloat && bits == 32:
							v += float64(*(*float32)(unsafe.Pointer(data + off)))
						case bits == 16:
							v += float64(*(*int16)(unsafe.Pointer(data + off))) / 32768
						case bits == 32:
							v += float64(*(*int32)(unsafe.Pointer(data + off))) / 2147483648
						}
					}
					v /= float64(ch)
				}
				ring[pos] = v
				pos = (pos + 1) % N
			}
			if !silent {
				lastAudio = time.Now()
			}
			comCall(capture, 4, uintptr(frames)) // ReleaseBuffer
			if hr := comCall(capture, 5, uintptr(unsafe.Pointer(&packet))); hr != 0 {
				return errAudioInvalidate
			}
		}

		if time.Since(lastSend) >= 33*time.Millisecond {
			lastSend = time.Now()
			bands := spectrum(ring, pos, rate, len(smooth))
			quiet := time.Since(lastAudio) > 300*time.Millisecond
			// raw bands: smoothing, auto-gain and beats are done in the page at full frame rate
			out := make([]float64, len(bands))
			if !quiet {
				copy(out, bands)
			}
			send(out)
		}
		if event != 0 {
			windows.WaitForSingleObject(event, 50)
		} else {
			time.Sleep(8 * time.Millisecond)
		}
	}
	return nil
}
