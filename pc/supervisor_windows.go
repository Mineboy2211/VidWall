//go:build windows

package main

// The wallpaper window lives inside the desktop's WorkerW window. When Windows rebuilds the
// desktop (explorer restart, display/theme change…) it destroys that window — and ours with it,
// which ends the app. A tiny "guard" process starts the real app and restarts it when it ends
// without the user asking to quit.

import (
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

const childFlag = "--vw-child"

// exit codes of the app process
const (
	exitUserQuit   = 0 // quit from the menu, or replaced by an update
	exitUnexpected = 3 // window destroyed, run loop ended by itself
)

var userQuit bool

func isChild() bool {
	for _, a := range os.Args[1:] {
		if a == childFlag {
			return true
		}
	}
	return false
}

func runGuard() {
	dir := filepath.Join(os.Getenv("APPDATA"), "VidWallPC")
	_ = os.MkdirAll(dir, 0755)
	if f, err := os.OpenFile(filepath.Join(dir, "guard-log.txt"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644); err == nil {
		if st, _ := f.Stat(); st != nil && st.Size() > 1<<20 {
			_ = f.Truncate(0)
		}
		log.SetOutput(f)
	}
	updated := false
	var args []string
	for _, a := range os.Args[1:] {
		if a == "--updated" {
			updated = true
		}
		args = append(args, a)
	}

	// only one guard: a second launch just opens the settings of the running one
	name := u16("Local\\VidWallPC_guard")
	mh, _, merr := pCreateMutexW.Call(0, 0, uintptr(unsafe.Pointer(name)))
	for tries := 0; updated && merr == windows.ERROR_ALREADY_EXISTS && tries < 40; tries++ {
		windows.CloseHandle(windows.Handle(mh))
		time.Sleep(300 * time.Millisecond)
		mh, _, merr = pCreateMutexW.Call(0, 0, uintptr(unsafe.Pointer(name)))
	}
	if merr == windows.ERROR_ALREADY_EXISTS {
		if h, _, _ := pFindWindowW.Call(uintptr(unsafe.Pointer(u16("VidWallPCTray"))), 0); h != 0 {
			pPostMessageW.Call(h, wmShowUI, 0, 0)
		}
		return
	}

	exe, _ := os.Executable()
	var crashes []time.Time
	for {
		cmd := exec.Command(exe, append(args, childFlag)...)
		start := time.Now()
		err := cmd.Run()
		code := 0
		if cmd.ProcessState != nil {
			code = cmd.ProcessState.ExitCode()
		}
		log.Printf("app ended after %v: code %d (%v)", time.Since(start).Round(time.Second), code, err)
		if code == exitUserQuit {
			return
		}
		// don't loop forever if it crashes right away every time
		now := time.Now()
		recent := crashes[:0]
		for _, t := range crashes {
			if now.Sub(t) < 3*time.Minute {
				recent = append(recent, t)
			}
		}
		crashes = append(recent, now)
		if len(crashes) > 5 {
			msgBox("VidWall s'est arrêté plusieurs fois de suite.\n\nEnvoie le fichier %APPDATA%\\VidWallPC\\log-precedent.txt pour qu'on corrige.")
			return
		}
		time.Sleep(time.Duration(len(crashes)) * time.Second)
		// restart quietly: no settings window, no "--updated" wait
		args = args[:0]
		args = append(args, "--background")
	}
}
