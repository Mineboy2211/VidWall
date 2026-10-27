//go:build windows

package main

// Choice of the sound the visualizer listens to:
//   ""            everything played on the default output (loopback)
//   "out:<id>"    everything played on one output device (loopback)
//   "in:<id>"     a microphone / input device
//   "app:<exe>"   one application only, e.g. "app:discord.exe" (process loopback, Windows 10 2004+)

import (
	"errors"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	pPropVariantClear     = ole32.NewProc("PropVariantClear")
	pActivateAudioIfAsync = windows.NewLazySystemDLL("mmdevapi.dll").NewProc("ActivateAudioInterfaceAsync")
	iidSessionManager2    = windows.GUID{Data1: 0x77AA99A0, Data2: 0x1BD6, Data3: 0x484F, Data4: [8]byte{0x8B, 0xC7, 0x2C, 0x65, 0x4C, 0x9A, 0x9B, 0x6F}}
	iidSessionControl2    = windows.GUID{Data1: 0xBFB7FF88, Data2: 0x7239, Data3: 0x4FC9, Data4: [8]byte{0x8F, 0xA2, 0x07, 0xC9, 0x50, 0xBE, 0x9C, 0x6D}}
	iidUnknown            = windows.GUID{Data1: 0, Data2: 0, Data3: 0, Data4: [8]byte{0xC0, 0, 0, 0, 0, 0, 0, 0x46}}
	iidCompletionHandler  = windows.GUID{Data1: 0x41D949AB, Data2: 0x9862, Data3: 0x444A, Data4: [8]byte{0x80, 0xF6, 0xC2, 0x61, 0x33, 0x4D, 0xA5, 0xEB}}
	iidAgileObject        = windows.GUID{Data1: 0x94EA2B94, Data2: 0xE9CC, Data3: 0x49E0, Data4: [8]byte{0xC0, 0xFF, 0xEE, 0x64, 0xCA, 0x8F, 0x5B, 0x90}}
	pkeyFriendlyName      = struct {
		fmtid windows.GUID
		pid   uint32
	}{windows.GUID{Data1: 0xA45C254E, Data2: 0xDF1C, Data3: 0x4EFD, Data4: [8]byte{0x80, 0x20, 0x67, 0xD1, 0x46, 0xA8, 0x50, 0xE0}}, 14}
)

type AudioDevice struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Default bool   `json:"default"`
}

type AudioApp struct {
	Exe     string `json:"exe"`
	Name    string `json:"name"`
	Playing bool   `json:"playing"`
}

type AudioSources struct {
	Outputs []AudioDevice `json:"outputs"`
	Inputs  []AudioDevice `json:"inputs"`
	Apps    []AudioApp    `json:"apps"`
	AppsOK  bool          `json:"appsOK"` // per-app capture available on this Windows
}

// onCOMThread runs f on a locked OS thread with COM initialized (MTA).
func onCOMThread(f func()) {
	done := make(chan struct{})
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		pCoInitializeEx.Call(0, 0)
		f()
		close(done)
	}()
	<-done
}

func newEnumerator() (uintptr, error) {
	var enum uintptr
	if hr, _, _ := pCoCreateInstance.Call(uintptr(unsafe.Pointer(&clsidMMDeviceEnum)), 0, 23,
		uintptr(unsafe.Pointer(&iidIMMDeviceEnum)), uintptr(unsafe.Pointer(&enum))); hr != 0 {
		return 0, errors.New("CoCreateInstance failed")
	}
	return enum, nil
}

func deviceID(dev uintptr) string {
	var p *uint16
	if comCall(dev, 5, uintptr(unsafe.Pointer(&p))) != 0 || p == nil {
		return ""
	}
	defer pCoTaskMemFree.Call(uintptr(unsafe.Pointer(p)))
	return windows.UTF16PtrToString(p)
}

func deviceName(dev uintptr) string {
	var store uintptr
	if comCall(dev, 4, 0, uintptr(unsafe.Pointer(&store))) != 0 { // OpenPropertyStore(STGM_READ)
		return ""
	}
	defer comRelease(store)
	var pv [24]byte // PROPVARIANT
	if comCall(store, 5, uintptr(unsafe.Pointer(&pkeyFriendlyName)), uintptr(unsafe.Pointer(&pv[0]))) != 0 {
		return ""
	}
	defer pPropVariantClear.Call(uintptr(unsafe.Pointer(&pv[0])))
	if *(*uint16)(unsafe.Pointer(&pv[0])) != 31 { // VT_LPWSTR
		return ""
	}
	return windows.UTF16PtrToString(*(**uint16)(unsafe.Pointer(&pv[8])))
}

func listDevices(enum uintptr, flow uintptr) []AudioDevice {
	var out []AudioDevice
	defID := ""
	var def uintptr
	if comCall(enum, 4, flow, 0, uintptr(unsafe.Pointer(&def))) == 0 {
		defID = deviceID(def)
		comRelease(def)
	}
	var coll uintptr
	if comCall(enum, 3, flow, 1, uintptr(unsafe.Pointer(&coll))) != 0 { // DEVICE_STATE_ACTIVE
		return out
	}
	defer comRelease(coll)
	var n uint32
	comCall(coll, 3, uintptr(unsafe.Pointer(&n)))
	for i := uint32(0); i < n; i++ {
		var dev uintptr
		if comCall(coll, 4, uintptr(i), uintptr(unsafe.Pointer(&dev))) != 0 {
			continue
		}
		id := deviceID(dev)
		out = append(out, AudioDevice{ID: id, Name: deviceName(dev), Default: id == defID})
		comRelease(dev)
	}
	return out
}

type procInfo struct {
	exe    string
	parent uint32
}

func processTable() map[uint32]procInfo {
	m := map[uint32]procInfo{}
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return m
	}
	defer windows.CloseHandle(snap)
	var e windows.ProcessEntry32
	e.Size = uint32(unsafe.Sizeof(e))
	for err = windows.Process32First(snap, &e); err == nil; err = windows.Process32Next(snap, &e) {
		m[e.ProcessID] = procInfo{strings.ToLower(windows.UTF16ToString(e.ExeFile[:])), e.ParentProcessID}
	}
	return m
}

var friendlyApps = map[string]string{
	"discord.exe": "Discord", "discordptb.exe": "Discord PTB", "discordcanary.exe": "Discord Canary",
	"spotify.exe": "Spotify", "chrome.exe": "Chrome", "msedge.exe": "Edge", "firefox.exe": "Firefox",
	"opera.exe": "Opera", "opera_gx.exe": "Opera GX", "brave.exe": "Brave", "vlc.exe": "VLC",
	"steam.exe": "Steam", "steamwebhelper.exe": "Steam", "robloxplayerbeta.exe": "Roblox",
	"applemusic.exe": "Apple Music", "deezer.exe": "Deezer", "obs64.exe": "OBS", "teams.exe": "Teams",
	"ms-teams.exe": "Teams", "whatsapp.exe": "WhatsApp", "zoom.exe": "Zoom",
}

func appName(exe string) string {
	if n, ok := friendlyApps[exe]; ok {
		return n
	}
	n := strings.TrimSuffix(exe, ".exe")
	if n == "" {
		return exe
	}
	return strings.ToUpper(n[:1]) + n[1:]
}

// listApps: applications that currently have an audio session (they play or recently played sound).
func listApps(enum uintptr) []AudioApp {
	procs := processTable()
	seen := map[string]bool{}
	var out []AudioApp
	var coll uintptr
	if comCall(enum, 3, 0, 1, uintptr(unsafe.Pointer(&coll))) != 0 { // all active outputs
		return out
	}
	defer comRelease(coll)
	var n uint32
	comCall(coll, 3, uintptr(unsafe.Pointer(&n)))
	for i := uint32(0); i < n; i++ {
		var dev, mgr, sessions uintptr
		if comCall(coll, 4, uintptr(i), uintptr(unsafe.Pointer(&dev))) != 0 {
			continue
		}
		if comCall(dev, 3, uintptr(unsafe.Pointer(&iidSessionManager2)), 23, 0, uintptr(unsafe.Pointer(&mgr))) == 0 &&
			comCall(mgr, 5, uintptr(unsafe.Pointer(&sessions))) == 0 { // GetSessionEnumerator
			var cnt int32
			comCall(sessions, 3, uintptr(unsafe.Pointer(&cnt)))
			for s := int32(0); s < cnt; s++ {
				var ctl, ctl2 uintptr
				if comCall(sessions, 4, uintptr(s), uintptr(unsafe.Pointer(&ctl))) != 0 {
					continue
				}
				if comCall(ctl, 0, uintptr(unsafe.Pointer(&iidSessionControl2)), uintptr(unsafe.Pointer(&ctl2))) == 0 {
					var pid uint32
					var state int32
					comCall(ctl2, 14, uintptr(unsafe.Pointer(&pid)))  // GetProcessId
					comCall(ctl2, 3, uintptr(unsafe.Pointer(&state))) // GetState: 1 = active
					if p, ok := procs[pid]; ok && pid != 0 {
						exe := p.exe
						if exe != "msedgewebview2.exe" && exe != "vidwallpc.exe" && exe != "audiodg.exe" {
							if !seen[exe] {
								seen[exe] = true
								out = append(out, AudioApp{Exe: exe, Name: appName(exe), Playing: state == 1})
							} else if state == 1 {
								for k := range out {
									if out[k].Exe == exe {
										out[k].Playing = true
									}
								}
							}
						}
					}
					comRelease(ctl2)
				}
				comRelease(ctl)
			}
			comRelease(sessions)
		}
		comRelease(mgr)
		comRelease(dev)
	}
	sort.Slice(out, func(a, b int) bool { return out[a].Name < out[b].Name })
	return out
}

func listAudioSources() AudioSources {
	var res AudioSources
	onCOMThread(func() {
		enum, err := newEnumerator()
		if err != nil {
			return
		}
		defer comRelease(enum)
		res.Outputs = listDevices(enum, 0)
		res.Inputs = listDevices(enum, 1)
		res.Apps = listApps(enum)
	})
	res.AppsOK = pActivateAudioIfAsync.Find() == nil && windowsBuild() >= 19041
	return res
}

func windowsBuild() uint32 {
	_, _, build := windows.RtlGetNtVersionNumbers()
	return build
}

// rootProcess returns the main process of an app (its parent is not the same exe): capturing its
// "process tree" includes all the helper processes (Discord plays sound from a child process).
func rootProcess(exe string) (uint32, bool) {
	procs := processTable()
	var best uint32
	for pid, p := range procs {
		if p.exe != exe {
			continue
		}
		if parent, ok := procs[p.parent]; ok && parent.exe == exe {
			continue
		}
		if best == 0 || pid < best {
			best = pid
		}
	}
	return best, best != 0
}

// ---------------------------------------------------------------- per-app capture (process loopback)

// A minimal COM object implementing IActivateAudioInterfaceCompletionHandler (+ IAgileObject).
type completionHandler struct {
	vtbl *[4]uintptr
}

var (
	chVtbl     [4]uintptr
	chObj      = &completionHandler{vtbl: &chVtbl}
	chResult   = make(chan uintptr, 1)
	chInitDone bool
)

func guidEq(p uintptr, g *windows.GUID) bool { return *(*windows.GUID)(unsafe.Pointer(p)) == *g }

func initCompletionHandler() {
	if chInitDone {
		return
	}
	chInitDone = true
	chVtbl[0] = syscall.NewCallback(func(this, riid, ppv uintptr) uintptr { // QueryInterface
		if guidEq(riid, &iidUnknown) || guidEq(riid, &iidCompletionHandler) || guidEq(riid, &iidAgileObject) {
			*(*uintptr)(unsafe.Pointer(ppv)) = this
			return 0
		}
		*(*uintptr)(unsafe.Pointer(ppv)) = 0
		return 0x80004002 // E_NOINTERFACE
	})
	chVtbl[1] = syscall.NewCallback(func(this uintptr) uintptr { return 1 }) // AddRef
	chVtbl[2] = syscall.NewCallback(func(this uintptr) uintptr { return 1 }) // Release
	chVtbl[3] = syscall.NewCallback(func(this, op uintptr) uintptr {         // ActivateCompleted
		var hr, unk uintptr
		comCall(op, 3, uintptr(unsafe.Pointer(&hr)), uintptr(unsafe.Pointer(&unk))) // GetActivateResult
		if int32(hr) < 0 {
			if unk != 0 {
				comRelease(unk)
			}
			unk = 0
		}
		select {
		case chResult <- unk:
		default:
			comRelease(unk)
		}
		return 0
	})
}

// activateProcessLoopback returns an IAudioClient capturing only the sound of pid's process tree.
func activateProcessLoopback(pid uint32) (uintptr, error) {
	if pActivateAudioIfAsync.Find() != nil {
		return 0, errors.New("son par application : Windows 10 (2004) ou plus récent nécessaire")
	}
	initCompletionHandler()
	for len(chResult) > 0 {
		comRelease(<-chResult)
	}
	params := struct {
		Type uint32 // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
		PID  uint32
		Mode uint32 // PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE
	}{1, pid, 0}
	var pv [24]byte // PROPVARIANT { VT_BLOB, BLOB{cbSize, pBlobData} }
	*(*uint16)(unsafe.Pointer(&pv[0])) = 65
	*(*uint32)(unsafe.Pointer(&pv[8])) = uint32(unsafe.Sizeof(params))
	*(*uintptr)(unsafe.Pointer(&pv[16])) = uintptr(unsafe.Pointer(&params))
	path, _ := windows.UTF16PtrFromString("VAD\\Process_Loopback")
	var op uintptr
	hr, _, _ := pActivateAudioIfAsync.Call(uintptr(unsafe.Pointer(path)), uintptr(unsafe.Pointer(&iidIAudioClient)),
		uintptr(unsafe.Pointer(&pv[0])), uintptr(unsafe.Pointer(chObj)), uintptr(unsafe.Pointer(&op)))
	runtime.KeepAlive(&params)
	if int32(hr) < 0 {
		return 0, errors.New("son par application indisponible sur ce PC")
	}
	defer comRelease(op)
	select {
	case unk := <-chResult:
		if unk == 0 {
			return 0, errors.New("impossible d'écouter cette application")
		}
		var client uintptr
		r := comCall(unk, 0, uintptr(unsafe.Pointer(&iidIAudioClient)), uintptr(unsafe.Pointer(&client)))
		comRelease(unk)
		if r != 0 {
			return 0, errors.New("impossible d'écouter cette application")
		}
		return client, nil
	case <-time.After(5 * time.Second):
		return 0, errors.New("Windows ne répond pas pour le son de l'application")
	}
}

// exeOf a full path -> "discord.exe"
func exeOf(p string) string { return strings.ToLower(filepath.Base(p)) }
