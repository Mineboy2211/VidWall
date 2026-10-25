//go:build windows

package main

import (
	"os/exec"
	"strconv"
	"strings"
	"unicode/utf16"
	"io"
	"log"
	"runtime/debug"
	_ "embed"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"syscall"
	"time"
	"unsafe"

	webview2 "github.com/jchv/go-webview2"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

//go:embed icon.ico
var iconData []byte

//go:embed page.html
var pageHTML string

//go:embed ui.html
var uiHTML string

var (
	user32   = windows.NewLazySystemDLL("user32.dll")
	shell32  = windows.NewLazySystemDLL("shell32.dll")
	comdlg32 = windows.NewLazySystemDLL("comdlg32.dll")
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")

	pFindWindowW          = user32.NewProc("FindWindowW")
	pFindWindowExW        = user32.NewProc("FindWindowExW")
	pSendMessageTimeoutW  = user32.NewProc("SendMessageTimeoutW")
	pEnumWindows          = user32.NewProc("EnumWindows")
	pSetParent            = user32.NewProc("SetParent")
	pSetWindowLongPtrW    = user32.NewProc("SetWindowLongPtrW")
	pGetWindowLongPtrW    = user32.NewProc("GetWindowLongPtrW")
	pSetWindowPos         = user32.NewProc("SetWindowPos")
	pGetWindowRect        = user32.NewProc("GetWindowRect")
	pShowWindow           = user32.NewProc("ShowWindow")
	pRegisterClassExW     = user32.NewProc("RegisterClassExW")
	pCreateWindowExW      = user32.NewProc("CreateWindowExW")
	pDefWindowProcW       = user32.NewProc("DefWindowProcW")
	pCreatePopupMenu      = user32.NewProc("CreatePopupMenu")
	pAppendMenuW          = user32.NewProc("AppendMenuW")
	pTrackPopupMenu       = user32.NewProc("TrackPopupMenu")
	pDestroyMenu          = user32.NewProc("DestroyMenu")
	pGetCursorPos         = user32.NewProc("GetCursorPos")
	pSetForegroundWindow  = user32.NewProc("SetForegroundWindow")
	pPostMessageW         = user32.NewProc("PostMessageW")
	pLoadImageW           = user32.NewProc("LoadImageW")
	pSetTimer             = user32.NewProc("SetTimer")
	pGetForegroundWindow  = user32.NewProc("GetForegroundWindow")
	pGetClassNameW        = user32.NewProc("GetClassNameW")
	pMonitorFromWindow    = user32.NewProc("MonitorFromWindow")
	pGetMonitorInfoW      = user32.NewProc("GetMonitorInfoW")
	pEnumDisplayMonitors  = user32.NewProc("EnumDisplayMonitors")
	pSystemParametersInfo = user32.NewProc("SystemParametersInfoW")
	pRegisterWindowMsg    = user32.NewProc("RegisterWindowMessageW")
	pMessageBoxW          = user32.NewProc("MessageBoxW")
	pSetDpiCtx            = user32.NewProc("SetProcessDpiAwarenessContext")
	pIsWindow             = user32.NewProc("IsWindow")
	pCallWindowProcW      = user32.NewProc("CallWindowProcW")
	pSendMessageW         = user32.NewProc("SendMessageW")
	pGetDpiForSystem      = user32.NewProc("GetDpiForSystem")
	pIsIconic             = user32.NewProc("IsIconic")
	pIsWindowVisible      = user32.NewProc("IsWindowVisible")
	pDwmSetWindowAttr     = windows.NewLazySystemDLL("dwmapi.dll").NewProc("DwmSetWindowAttribute")
	pShellNotifyIconW     = shell32.NewProc("Shell_NotifyIconW")
	pGetOpenFileNameW     = comdlg32.NewProc("GetOpenFileNameW")
	pCreateMutexW         = kernel32.NewProc("CreateMutexW")
)

type rect struct{ L, T, R, B int32 }
type point struct{ X, Y int32 }

type monitorInfo struct {
	Size    uint32
	Monitor rect
	Work    rect
	Flags   uint32
}

type wndClassEx struct {
	Size       uint32
	Style      uint32
	WndProc    uintptr
	ClsExtra   int32
	WndExtra   int32
	Instance   windows.Handle
	Icon       windows.Handle
	Cursor     windows.Handle
	Background windows.Handle
	MenuName   *uint16
	ClassName  *uint16
	IconSm     windows.Handle
}

type notifyIconData struct {
	Size            uint32
	Wnd             uintptr
	ID              uint32
	Flags           uint32
	CallbackMessage uint32
	Icon            uintptr
	Tip             [128]uint16
	State           uint32
	StateMask       uint32
	Info            [256]uint16
	Version         uint32
	InfoTitle       [64]uint16
	InfoFlags       uint32
	GuidItem        windows.GUID
	BalloonIcon     uintptr
}

type openFileName struct {
	StructSize    uint32
	Owner         uintptr
	Instance      uintptr
	Filter        *uint16
	CustomFilter  *uint16
	MaxCustFilter uint32
	FilterIndex   uint32
	File          *uint16
	MaxFile       uint32
	FileTitle     *uint16
	MaxFileTitle  uint32
	InitialDir    *uint16
	Title         *uint16
	Flags         uint32
	FileOffset    uint16
	FileExtension uint16
	DefExt        *uint16
	CustData      uintptr
	FnHook        uintptr
	TemplateName  *uint16
	Reserved      uintptr
	Reserved2     uint32
	FlagsEx       uint32
}

const (
	wmUser        = 0x0400
	wmTray        = wmUser + 77
	wmShowUI      = wmUser + 78
	wmTimer       = 0x0113
	wmCommand     = 0x0111
	wmLButtonUp   = 0x0202
	wmRButtonUp   = 0x0205
	nimAdd        = 0
	nimModify     = 1
	nimDelete     = 2
	nifMessage    = 1
	nifIcon       = 2
	nifTip        = 4
	mfString      = 0
	mfSeparator   = 0x800
	mfChecked     = 0x8
	tpmReturnCmd  = 0x100
	tpmRightBtn   = 0x2
	wsChild       = 0x40000000
	wsVisible     = 0x10000000
	wsExToolWin   = 0x80
	wsExNoActive  = 0x08000000
	swpNoZOrder   = 0x4
	swpNoActivate = 0x10
	swpShow       = 0x40
	swpFrameChg   = 0x20
)

const (
	cmdChoose = iota + 1
	cmdPause
	cmdSound
	cmdFill
	cmdFit
	cmdAllScreens
	cmdAutoPause
	cmdStartup
	cmdExit
	cmdOpenUI
)

type Config struct {
	Video      string `json:"video"`
	Fit        string `json:"fit"`
	Sound      bool   `json:"sound"`
	Paused     bool   `json:"paused"`
	AutoPause  bool   `json:"autoPause"`
	AllScreens bool     `json:"allScreens"`
	Volume     float64  `json:"volume"`
	Speed      float64  `json:"speed"`
	Recent     []string `json:"recent"`
	AutoHD     bool     `json:"autoHD"`
	HDStyle    string   `json:"hdStyle"`

	DayNight     DayNightCfg `json:"dayNight"`
	Widgets      WidgetsCfg  `json:"widgets"`
	Viz          VizCfg      `json:"viz"`
	Hotkeys      bool        `json:"hotkeys"`
	BatteryPause bool        `json:"batteryPause"`
	CoveredPause bool        `json:"coveredPause"`
	FPSLimit     int         `json:"fpsLimit"`
	NoAutoUpdate bool        `json:"noAutoUpdate"`
}

var (
	cfg        Config
	cfgPath    string
	wv         webview2.WebView
	wvHwnd     uintptr
	trayHwnd   uintptr
	trayIcon   uintptr
	port       int
	autoPaused bool
	taskbarMsg uintptr
	videoVer   int64
	origWall   [520]uint16
	ui         webview2.WebView
	uiHwnd     uintptr
	uiOrigProc uintptr
	background bool
)

var gwlStyleU, gwlExStyleU = ^uintptr(15), ^uintptr(19)

func u16(s string) *uint16 { p, _ := windows.UTF16PtrFromString(s); return p }

func loadConfig() {
	dir := filepath.Join(os.Getenv("APPDATA"), "VidWallPC")
	_ = os.MkdirAll(dir, 0755)
	cfgPath = filepath.Join(dir, "config.json")
	cfg = Config{Fit: "cover", AutoPause: true, AllScreens: true, Volume: 0.6, Speed: 1}
	if b, err := os.ReadFile(cfgPath); err == nil {
		_ = json.Unmarshal(b, &cfg)
	}
	if cfg.Fit == "" {
		cfg.Fit = "cover"
	}
	if cfg.Speed <= 0 {
		cfg.Speed = 1
	}
	if cfg.HDStyle == "" {
		cfg.HDStyle = "anime"
	}
	cfg.defaults()
}

func isGif(p string) bool { return strings.EqualFold(filepath.Ext(p), ".gif") }

func addRecent(p string) {
	out := []string{p}
	for _, r := range cfg.Recent {
		if !strings.EqualFold(r, p) {
			out = append(out, r)
		}
	}
	if len(out) > 12 {
		out = out[:12]
	}
	cfg.Recent = out
}

func saveConfig() {
	b, _ := json.MarshalIndent(cfg, "", "  ")
	_ = os.WriteFile(cfgPath, b, 0644)
}

// ---------- desktop (WorkerW) ----------

func findWorkerW() uintptr {
	progman, _, _ := pFindWindowW.Call(uintptr(unsafe.Pointer(u16("Progman"))), 0)
	var res uintptr
	pSendMessageTimeoutW.Call(progman, 0x052C, 0xD, 0x1, 0, 1000, uintptr(unsafe.Pointer(&res)))
	pSendMessageTimeoutW.Call(progman, 0x052C, 0, 0, 0, 1000, uintptr(unsafe.Pointer(&res)))
	time.Sleep(150 * time.Millisecond)

	var worker uintptr
	cb := syscall.NewCallback(func(h, _ uintptr) uintptr {
		def, _, _ := pFindWindowExW.Call(h, 0, uintptr(unsafe.Pointer(u16("SHELLDLL_DefView"))), 0)
		if def != 0 {
			w, _, _ := pFindWindowExW.Call(0, h, uintptr(unsafe.Pointer(u16("WorkerW"))), 0)
			if w != 0 {
				worker = w
			}
		}
		return 1
	})
	pEnumWindows.Call(cb, 0)
	if worker == 0 {
		// Windows 11 24H2+: WorkerW lives inside Progman
		worker, _, _ = pFindWindowExW.Call(progman, 0, uintptr(unsafe.Pointer(u16("WorkerW"))), 0)
	}
	if worker == 0 {
		worker = progman
	}
	return worker
}

type mon struct {
	X, Y, W, H int32
}

func monitors() []mon {
	var list []mon
	cb := syscall.NewCallback(func(hm, hdc, lprc, _ uintptr) uintptr {
		var mi monitorInfo
		mi.Size = uint32(unsafe.Sizeof(mi))
		pGetMonitorInfoW.Call(hm, uintptr(unsafe.Pointer(&mi)))
		r := mi.Monitor
		m := mon{r.L, r.T, r.R - r.L, r.B - r.T}
		if mi.Flags&1 != 0 { // primary first
			list = append([]mon{m}, list...)
		} else {
			list = append(list, m)
		}
		return 1
	})
	pEnumDisplayMonitors.Call(0, 0, cb, 0)
	return list
}

func attach() {
	worker := findWorkerW()
	log.Println("attach worker", worker)
	pSetParent.Call(wvHwnd, worker)
	pSetWindowLongPtrW.Call(wvHwnd, gwlStyleU, wsChild|wsVisible)
	ex, _, _ := pGetWindowLongPtrW.Call(wvHwnd, gwlExStyleU)
	pSetWindowLongPtrW.Call(wvHwnd, gwlExStyleU, ex|wsExToolWin|wsExNoActive)
	layout(worker)
}

func layout(worker uintptr) {
	var wr rect
	pGetWindowRect.Call(worker, uintptr(unsafe.Pointer(&wr)))
	ms := monitors()
	if len(ms) == 0 {
		return
	}
	if !cfg.AllScreens {
		ms = ms[:1]
	}
	minX, minY, maxX, maxY := ms[0].X, ms[0].Y, ms[0].X+ms[0].W, ms[0].Y+ms[0].H
	for _, m := range ms {
		minX, minY = min(minX, m.X), min(minY, m.Y)
		maxX, maxY = max(maxX, m.X+m.W), max(maxY, m.Y+m.H)
	}
	pSetWindowPos.Call(wvHwnd, 0, uintptr(minX-wr.L), uintptr(minY-wr.T),
		uintptr(maxX-minX), uintptr(maxY-minY), swpNoZOrder|swpNoActivate|swpShow|swpFrameChg)
	rel := make([]mon, len(ms))
	for i, m := range ms {
		rel[i] = mon{m.X - minX, m.Y - minY, m.W, m.H}
	}
	b, _ := json.Marshal(rel)
	wv.Eval(fmt.Sprintf("window.setScreens && setScreens(%s)", b))
}

func restoreWallpaper() {
	if origWall[0] == 0 {
		return
	}
	pSystemParametersInfo.Call(0x0014, 0, uintptr(unsafe.Pointer(&origWall[0])), 0)
}

// ---------- page control ----------

func applyState() {
	src := ""
	if cfg.Video != "" {
		src = fmt.Sprintf("/video?v=%d", videoVer)
	}
	paused := cfg.Paused || autoPaused
	st := map[string]any{"src": src, "fit": cfg.Fit, "sound": cfg.Sound, "paused": paused,
		"volume": cfg.Volume, "speed": cfg.Speed, "gif": isGif(cfg.Video),
		"widgets": cfg.Widgets, "weather": weather.snapshot(), "viz": cfg.Viz, "fpsLimit": cfg.FPSLimit}
	viz.setEnabled(cfg.Viz.Enabled && !paused)
	b, _ := json.Marshal(st)
	wv.Eval(fmt.Sprintf("window.applyState && applyState(%s)", b))
	pushUI()
}

// ---------- tray ----------

func addTray(mode uintptr) {
	var nid notifyIconData
	nid.Size = uint32(unsafe.Sizeof(nid))
	nid.Wnd = trayHwnd
	nid.ID = 1
	nid.Flags = nifMessage | nifIcon | nifTip
	nid.CallbackMessage = wmTray
	nid.Icon = trayIcon
	copy(nid.Tip[:], windows.StringToUTF16("VidWall — fond d'écran vidéo"))
	pShellNotifyIconW.Call(mode, uintptr(unsafe.Pointer(&nid)))
}

func removeTray() {
	var nid notifyIconData
	nid.Size = uint32(unsafe.Sizeof(nid))
	nid.Wnd = trayHwnd
	nid.ID = 1
	pShellNotifyIconW.Call(nimDelete, uintptr(unsafe.Pointer(&nid)))
}

func startupEnabled() bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	_, _, err = k.GetStringValue("VidWallPC")
	return err == nil
}

func setStartup(on bool) {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`, registry.SET_VALUE)
	if err != nil {
		return
	}
	defer k.Close()
	if on {
		exe, _ := os.Executable()
		_ = k.SetStringValue("VidWallPC", `"`+exe+`" --background`)
	} else {
		_ = k.DeleteValue("VidWallPC")
	}
}

func showMenu() {
	m, _, _ := pCreatePopupMenu.Call()
	add := func(id int, text string, checked bool) {
		f := uintptr(mfString)
		if checked {
			f |= mfChecked
		}
		pAppendMenuW.Call(m, f, uintptr(id), uintptr(unsafe.Pointer(u16(text))))
	}
	sep := func() { pAppendMenuW.Call(m, mfSeparator, 0, 0) }
	add(cmdOpenUI, "Ouvrir VidWall", false)
	sep()
	add(cmdChoose, "Choisir une vidéo…", false)
	if cfg.Paused {
		add(cmdPause, "▶ Reprendre", false)
	} else {
		add(cmdPause, "⏸ Pause", false)
	}
	sep()
	add(cmdSound, "Son", cfg.Sound)
	add(cmdFill, "Remplir l'écran (recadrer)", cfg.Fit == "cover")
	add(cmdFit, "Ajuster (bandes noires)", cfg.Fit == "contain")
	add(cmdAllScreens, "Tous les écrans", cfg.AllScreens)
	add(cmdAutoPause, "Pause quand un jeu / appli est en plein écran", cfg.AutoPause)
	add(cmdStartup, "Lancer au démarrage de Windows", startupEnabled())
	sep()
	add(cmdExit, "Quitter", false)

	var p point
	pGetCursorPos.Call(uintptr(unsafe.Pointer(&p)))
	pSetForegroundWindow.Call(trayHwnd)
	cmd, _, _ := pTrackPopupMenu.Call(m, tpmReturnCmd|tpmRightBtn, uintptr(p.X), uintptr(p.Y), 0, trayHwnd, 0)
	pDestroyMenu.Call(m)
	pPostMessageW.Call(trayHwnd, 0, 0, 0)
	handleCmd(int(cmd))
}

func handleCmd(cmd int) {
	switch cmd {
	case cmdChoose:
		f := pickFile()
		log.Println("picked", f)
		if f != "" {
			cfg.Video = f
			addRecent(f)
			cfg.Paused = false
			videoVer = time.Now().UnixNano()
			saveConfig()
			applyState()
			if cfg.AutoHD && !isHD(f) {
				startUpscale(f)
			}
		}
	case cmdPause:
		cfg.Paused = !cfg.Paused
		saveConfig()
		applyState()
	case cmdSound:
		cfg.Sound = !cfg.Sound
		saveConfig()
		applyState()
	case cmdFill:
		cfg.Fit = "cover"
		saveConfig()
		applyState()
	case cmdFit:
		cfg.Fit = "contain"
		saveConfig()
		applyState()
	case cmdAllScreens:
		cfg.AllScreens = !cfg.AllScreens
		saveConfig()
		attach()
	case cmdAutoPause:
		cfg.AutoPause = !cfg.AutoPause
		if !cfg.AutoPause && autoPaused {
			autoPaused = false
			applyState()
		}
		saveConfig()
	case cmdStartup:
		setStartup(!startupEnabled())
	case cmdOpenUI:
		showUI()
	case cmdExit:
		log.Println("exit by user")
		removeTray()
		wv.Destroy()
	}
}

func pickFile() string {
	buf := make([]uint16, 4096)
	filter := utf16.Encode([]rune("Vidéos et GIF\x00*.mp4;*.webm;*.mov;*.m4v;*.mkv;*.gif\x00Tous les fichiers\x00*.*\x00"))
	filter = append(filter, 0)
	var ofn openFileName
	ofn.StructSize = uint32(unsafe.Sizeof(ofn))
	ofn.Owner = trayHwnd
	if ui != nil {
		if vis, _, _ := pIsWindowVisible.Call(uiHwnd); vis != 0 {
			ofn.Owner = uiHwnd
		}
	}
	ofn.Filter = &filter[0]
	ofn.File = &buf[0]
	ofn.MaxFile = uint32(len(buf))
	ofn.Title = u16("Choisis ta vidéo de fond d'écran")
	ofn.Flags = 0x00001000 | 0x00000800 | 0x00080000 | 0x00000008 // FILEMUSTEXIST|PATHMUSTEXIST|EXPLORER|NOCHANGEDIR
	r, _, _ := pGetOpenFileNameW.Call(uintptr(unsafe.Pointer(&ofn)))
	if r == 0 {
		return ""
	}
	return windows.UTF16ToString(buf)
}

// ---------- fullscreen detection ----------

func fullscreenAppActive() bool {
	fg, _, _ := pGetForegroundWindow.Call()
	if fg == 0 || fg == wvHwnd {
		return false
	}
	cls := make([]uint16, 256)
	pGetClassNameW.Call(fg, uintptr(unsafe.Pointer(&cls[0])), 256)
	switch windows.UTF16ToString(cls) {
	case "Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd":
		return false
	}
	var r rect
	pGetWindowRect.Call(fg, uintptr(unsafe.Pointer(&r)))
	hm, _, _ := pMonitorFromWindow.Call(fg, 2)
	var mi monitorInfo
	mi.Size = uint32(unsafe.Sizeof(mi))
	pGetMonitorInfoW.Call(hm, uintptr(unsafe.Pointer(&mi)))
	return r.L <= mi.Monitor.L && r.T <= mi.Monitor.T && r.R >= mi.Monitor.R && r.B >= mi.Monitor.B
}

func trayProc(h, msg, wp, lp uintptr) uintptr {
	switch {
	case msg == wmTray:
		if lp == wmRButtonUp {
			showMenu()
		} else if lp == wmLButtonUp {
			showUI()
		}
		return 0
	case msg == wmShowUI:
		showUI()
		return 0
	case msg == wmTimer:
		if ok, _, _ := pIsWindow.Call(wvHwnd); ok == 0 {
			return 0
		}
		perfTick()
		dayNightTick()
		return 0
	case msg == wmHotkey:
		onHotkey(wp)
		return 0
	case taskbarMsg != 0 && msg == taskbarMsg: // explorer restarted
		addTray(nimAdd)
		attach()
		return 0
	}
	r, _, _ := pDefWindowProcW.Call(h, msg, wp, lp)
	return r
}

func createTrayWindow() {
	var hinst windows.Handle
	_ = windows.GetModuleHandleEx(0, nil, &hinst)
	cls := u16("VidWallPCTray")
	wc := wndClassEx{WndProc: syscall.NewCallback(trayProc), Instance: hinst, ClassName: cls}
	wc.Size = uint32(unsafe.Sizeof(wc))
	pRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc)))
	trayHwnd, _, _ = pCreateWindowExW.Call(0, uintptr(unsafe.Pointer(cls)), uintptr(unsafe.Pointer(u16("VidWall"))),
		0, 0, 0, 0, 0, 0, 0, uintptr(hinst), 0)

	icoPath := filepath.Join(filepath.Dir(cfgPath), "icon.ico")
	_ = os.WriteFile(icoPath, iconData, 0644)
	trayIcon, _, _ = pLoadImageW.Call(0, uintptr(unsafe.Pointer(u16(icoPath))), 1, 0, 0, 0x10|0x40) // LR_LOADFROMFILE|LR_DEFAULTSIZE
	taskbarMsg, _, _ = pRegisterWindowMsg.Call(uintptr(unsafe.Pointer(u16("TaskbarCreated"))))
	addTray(nimAdd)
	pSetTimer.Call(trayHwnd, 1, 1500, 0)
}

// ---------- local server ----------

func startServer() {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		panic(err)
	}
	port = ln.Addr().(*net.TCPAddr).Port
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte(pageHTML))
	})
	mux.HandleFunc("/video", func(w http.ResponseWriter, r *http.Request) {
		if cfg.Video == "" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		http.ServeFile(w, r, cfg.Video)
	})
	mux.HandleFunc("/ui", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write([]byte(uiHTML))
	})
	mux.HandleFunc("/file", func(w http.ResponseWriter, r *http.Request) {
		i, _ := strconv.Atoi(r.URL.Query().Get("i"))
		p, _ := onUI(func() any {
			if i >= 0 && i < len(cfg.Recent) {
				return cfg.Recent[i]
			}
			return ""
		}).(string)
		if p == "" {
			http.NotFound(w, r)
			return
		}
		http.ServeFile(w, r, p)
	})
	mux.HandleFunc("/api/state", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		b, ok := onUI(func() any { return stateJSON() }).([]byte)
		if !ok {
			b = []byte("{}")
		}
		_, _ = w.Write(b)
	})
	mux.HandleFunc("/api/do", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		a, k, v := q.Get("a"), q.Get("k"), q.Get("v")
		i, _ := strconv.Atoi(q.Get("i"))
		wv.Dispatch(func() { apiDo(a, k, v, i) })
		w.WriteHeader(204)
	})
	go func() { _ = http.Serve(ln, mux) }()
}

func msgBox(text string) {
	pMessageBoxW.Call(0, uintptr(unsafe.Pointer(u16(text))), uintptr(unsafe.Pointer(u16("VidWall"))), 0x40)
}

func setupLog() {
	dir := filepath.Join(os.Getenv("APPDATA"), "VidWallPC")
	_ = os.MkdirAll(dir, 0755)
	f, err := os.OpenFile(filepath.Join(dir, "log.txt"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
	if err == nil {
		log.SetOutput(io.MultiWriter(os.Stderr, f))
		_ = debug.SetCrashOutput(f, debug.CrashOptions{})
	}
	log.Println("VidWallPC start", runtime.GOOS, runtime.GOARCH)
}

func main() {
	runtime.LockOSThread()
	setupLog()
	defer func() {
		if c, _, _ := kernel32.NewProc("GetConsoleWindow").Call(); c != 0 {
			fmt.Print("\nAppuie sur Entree pour fermer...")
			_, _ = fmt.Scanln()
		}
	}()
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC: %v\n%s", r, debug.Stack())
			msgBox(fmt.Sprintf("VidWall a planté :\n%v\n\nLog : %%APPDATA%%\\VidWallPC\\log.txt", r))
		}
	}()
	updated := false
	for _, a := range os.Args[1:] {
		if a == "--updated" {
			updated = true
		}
	}
	mh, _, merr := pCreateMutexW.Call(0, 0, uintptr(unsafe.Pointer(u16("Local\\VidWallPC_single"))))
	// right after an update the old version may still be closing: wait for it
	for tries := 0; updated && merr == windows.ERROR_ALREADY_EXISTS && tries < 20; tries++ {
		windows.CloseHandle(windows.Handle(mh))
		time.Sleep(300 * time.Millisecond)
		mh, _, merr = pCreateMutexW.Call(0, 0, uintptr(unsafe.Pointer(u16("Local\\VidWallPC_single"))))
	}
	if merr == windows.ERROR_ALREADY_EXISTS {
		if h, _, _ := pFindWindowW.Call(uintptr(unsafe.Pointer(u16("VidWallPCTray"))), 0); h != 0 {
			pPostMessageW.Call(h, wmShowUI, 0, 0)
		} else {
			msgBox("VidWall tourne déjà : clique sur son icône près de l'horloge.")
		}
		return
	}
	for _, a := range os.Args[1:] {
		if a == "--background" {
			background = true
		}
	}
	log.Println("mutex ok, dpi")
	pSetDpiCtx.Call(^uintptr(3)) // PER_MONITOR_AWARE_V2 (-4)
	pSystemParametersInfo.Call(0x0073, uintptr(len(origWall)), uintptr(unsafe.Pointer(&origWall[0])), 0)

	loadConfig()
	log.Println("config", cfgPath, cfg.Video)
	if cfg.Video != "" {
		if _, err := os.Stat(cfg.Video); err != nil {
			cfg.Video = ""
		}
	}
	videoVer = time.Now().UnixNano()
	startServer()
	log.Println("server port", port)

	os.Setenv("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS",
		"--autoplay-policy=no-user-gesture-required --disable-features=CalculateNativeWinOcclusion --disable-background-media-suspend --disable-renderer-backgrounding --disable-background-timer-throttling")

	wv = webview2.NewWithOptions(webview2.WebViewOptions{
		Debug:     false,
		AutoFocus: false,
		DataPath:  filepath.Join(os.Getenv("LOCALAPPDATA"), "VidWallPC"),
		WindowOptions: webview2.WindowOptions{
			Title: "VidWall", Width: 320, Height: 200,
		},
	})
	log.Println("webview created", wv != nil)
	if wv == nil {
		msgBox("Impossible de démarrer : installe « Microsoft Edge WebView2 Runtime » (gratuit, sur le site de Microsoft).")
		return
	}
	wvHwnd = uintptr(wv.Window())
	pShowWindow.Call(wvHwnd, 0)

	if err := wv.Bind("ready", func() {
		log.Println("page ready")
		wv.Dispatch(func() {
			attach()
			applyState()
		})
	}); err != nil {
		_ = err
	}
	wv.Navigate(fmt.Sprintf("http://127.0.0.1:%d/", port))

	createTrayWindow()
	log.Println("tray", trayHwnd, trayIcon)
	registerHotkeys(cfg.Hotkeys)
	startAudio(func(levels []float64) {
		// compact message: integers 0..1000
		var sb strings.Builder
		sb.WriteString("window.setAudio && setAudio([")
		for i, l := range levels {
			if i > 0 {
				sb.WriteByte(',')
			}
			sb.WriteString(strconv.Itoa(int(l * 1000)))
		}
		sb.WriteString("])")
		js := sb.String()
		wv.Dispatch(func() { wv.Eval(js) })
	})
	go weatherLoop()
	go cleanupOldExe()
	go func() {
		time.Sleep(8 * time.Second)
		for {
			if !cfg.NoAutoUpdate {
				checkUpdate()
				wv.Dispatch(pushUI)
			}
			time.Sleep(6 * time.Hour)
		}
	}()
	if cfg.Video != "" && len(cfg.Recent) == 0 {
		addRecent(cfg.Video)
	}
	createUI()
	if !background || cfg.Video == "" {
		showUI()
	}

	log.Println("run loop")
	wv.Run()
	log.Println("run loop ended")
	removeTray()
	if !restarting {
		restoreWallpaper()
	}
}


// ---------- settings window ----------

// onUI runs f on the UI thread and returns its result.
func onUI(f func() any) any {
	ch := make(chan any, 1)
	wv.Dispatch(func() { ch <- f() })
	select {
	case v := <-ch:
		return v
	case <-time.After(3 * time.Second):
		return nil
	}
}

func stateJSON() []byte {
	tw, th := targetRes()
	db0, db1 := dayBounds()
	onBat, hasBat := onBattery()
	type item struct {
		I      int    `json:"i"`
		Name   string `json:"name"`
		Path   string `json:"path"`
		Exists bool   `json:"exists"`
		Active bool   `json:"active"`
		Gif    bool   `json:"gif"`
		HD     bool   `json:"hd"`
	}
	items := []item{}
	for i, p := range cfg.Recent {
		_, err := os.Stat(p)
		items = append(items, item{i, filepath.Base(p), p, err == nil, strings.EqualFold(p, cfg.Video), isGif(p), isHD(p)})
	}
	b, _ := json.Marshal(map[string]any{
		"video": cfg.Video, "videoName": filepath.Base(cfg.Video), "ver": videoVer, "isGif": isGif(cfg.Video),
		"fit": cfg.Fit, "sound": cfg.Sound, "volume": cfg.Volume, "speed": cfg.Speed,
		"paused": cfg.Paused, "autoPaused": autoPaused, "autoPause": cfg.AutoPause,
		"allScreens": cfg.AllScreens, "startup": startupEnabled(), "recent": items,
		"screens": len(monitors()),
		"autoHD": cfg.AutoHD, "hdStyle": cfg.HDStyle, "isHD": isHD(cfg.Video),
		"targetW": tw, "targetH": th, "job": job.snapshot(),
		"dayNight": cfg.DayNight, "period": currentPeriod(), "dayBounds": []int{db0, db1},
		"widgets": cfg.Widgets, "weather": weather.snapshot(), "viz": cfg.Viz,
		"hotkeys": cfg.Hotkeys, "hotkeyList": hotkeyNames(),
		"batteryPause": cfg.BatteryPause, "coveredPause": cfg.CoveredPause, "fpsLimit": cfg.FPSLimit,
		"pauseReason": pauseReason, "hasBattery": hasBat, "onBattery": onBat,
		"update": upd.snapshot(), "autoUpdate": !cfg.NoAutoUpdate,
		"folders": map[string]string{"hd": videosDir("HD"), "exports": videosDir("Exports")},
	})
	return b
}

func apiDo(a, k, v string, i int) {
	switch a {
	case "pick":
		handleCmd(cmdChoose)
	case "pause":
		handleCmd(cmdPause)
	case "quit":
		handleCmd(cmdExit)
		return
	case "use":
		if i >= 0 && i < len(cfg.Recent) {
			if _, err := os.Stat(cfg.Recent[i]); err == nil {
				cfg.Video = cfg.Recent[i]
				addRecent(cfg.Video)
				cfg.Paused = false
				videoVer = time.Now().UnixNano()
				saveConfig()
				applyState()
			}
		}
	case "upscale":
		p := cfg.Video
		if i >= 0 && i < len(cfg.Recent) && k == "item" {
			p = cfg.Recent[i]
		}
		if p != "" {
			startUpscale(p)
		}
	case "fps60":
		p := cfg.Video
		if i >= 0 && i < len(cfg.Recent) && k == "item" {
			p = cfg.Recent[i]
		}
		if p != "" {
			start60(p)
		}
	case "edit":
		var o EditOpts
		if err := json.Unmarshal([]byte(v), &o); err == nil && cfg.Video != "" {
			startEdit(cfg.Video, o)
		}
	case "phone", "gif":
		if cfg.Video != "" {
			startExport(cfg.Video, a, v == "short")
		}
	case "openFolder":
		p := v
		if p == "" {
			job.mu.Lock()
			p = job.Done
			job.mu.Unlock()
		}
		openInExplorer(p)
	case "dnSet":
		p := ""
		if i >= 0 && i < len(cfg.Recent) {
			p = cfg.Recent[i]
		} else if i == -2 {
			p = ""
		} else {
			p = pickFile()
			if p == "" {
				break
			}
			addRecent(p)
		}
		if k == "day" {
			cfg.DayNight.DayVideo = p
		} else {
			cfg.DayNight.NightVideo = p
		}
		lastPeriod = ""
		saveConfig()
		dayNightTick()
	case "checkUpdate":
		go func() { checkUpdate(); wv.Dispatch(pushUI) }()
	case "installUpdate":
		go installUpdate(func(exe string) error {
			cmd := exec.Command(exe, "--updated")
			if err := cmd.Start(); err != nil {
				return err
			}
			wv.Dispatch(func() {
				restarting = true
				handleCmd(cmdExit)
			})
			return nil
		})
	case "next":
		stepVideo(1)
	case "prev":
		stepVideo(-1)
	case "cancelUpscale":
		job.mu.Lock()
		if job.cancel != nil {
			job.cancel()
		}
		job.mu.Unlock()
	case "dismissUpscale":
		job.mu.Lock()
		if !job.Running {
			job.Err, job.Done, job.Phase, job.Kind = "", "", "", ""
		}
		job.mu.Unlock()
	case "remove":
		if i >= 0 && i < len(cfg.Recent) {
			cfg.Recent = append(cfg.Recent[:i:i], cfg.Recent[i+1:]...)
			saveConfig()
		}
	case "set":
		on := v == "true"
		f, _ := strconv.ParseFloat(v, 64)
		switch k {
		case "sound":
			cfg.Sound = on
		case "volume":
			cfg.Volume = max(0, min(1, f))
		case "speed":
			if f > 0 {
				cfg.Speed = f
			}
		case "fit":
			if v == "cover" || v == "contain" {
				cfg.Fit = v
			}
		case "autoPause":
			cfg.AutoPause = on
			if !on && autoPaused {
				autoPaused = false
			}
		case "allScreens":
			if cfg.AllScreens != on {
				cfg.AllScreens = on
				saveConfig()
				attach()
			}
		case "startup":
			setStartup(on)
		case "autoHD":
			cfg.AutoHD = on
		case "hdStyle":
			if v == "anime" || v == "real" {
				cfg.HDStyle = v
			}
		case "dn.enabled":
			cfg.DayNight.Enabled = on
			lastPeriod = ""
			kickWeather()
		case "dn.mode":
			if v == "fixed" || v == "sun" {
				cfg.DayNight.Mode = v
				lastPeriod = ""
				kickWeather()
			}
		case "dn.dayStart":
			if parseHM(v) >= 0 {
				cfg.DayNight.DayStart = v
				lastPeriod = ""
			}
		case "dn.nightStart":
			if parseHM(v) >= 0 {
				cfg.DayNight.NightStart = v
				lastPeriod = ""
			}
		case "w.clock":
			cfg.Widgets.Clock = on
		case "w.seconds":
			cfg.Widgets.Seconds = on
		case "w.date":
			cfg.Widgets.Date = on
		case "w.weather":
			cfg.Widgets.Weather = on
			kickWeather()
		case "w.city":
			if strings.TrimSpace(v) != "" {
				setCity(strings.TrimSpace(v))
			}
		case "w.pos":
			cfg.Widgets.Pos = v
		case "w.size":
			cfg.Widgets.Size = v
		case "viz.enabled":
			cfg.Viz.Enabled = on
		case "viz.style":
			cfg.Viz.Style = v
		case "viz.color":
			if strings.HasPrefix(v, "#") && len(v) <= 9 {
				cfg.Viz.Color = v
			}
		case "viz.sens":
			if f > 0 {
				cfg.Viz.Sens = f
			}
		case "viz.pos":
			cfg.Viz.Pos = v
		case "hotkeys":
			cfg.Hotkeys = on
			registerHotkeys(on)
		case "batteryPause":
			cfg.BatteryPause = on
		case "coveredPause":
			cfg.CoveredPause = on
		case "autoUpdate":
			cfg.NoAutoUpdate = !on
			if on {
				go func() { checkUpdate(); wv.Dispatch(pushUI) }()
			}
		case "fpsLimit":
			n, _ := strconv.Atoi(v)
			if n == 0 || n == 24 || n == 30 || n == 60 {
				cfg.FPSLimit = n
			}
		}
		saveConfig()
		applyState()
	}
	pushUI()
}

func pushUI() {
	if ui != nil {
		ui.Eval("window.refresh && refresh()")
	}
}

func uiProc(h, msg, wp, lp uintptr) uintptr {
	if msg == 0x0010 { // WM_CLOSE -> just hide
		pShowWindow.Call(h, 0)
		ui.Eval("window.onHidden && onHidden()")
		return 0
	}
	r, _, _ := pCallWindowProcW.Call(uiOrigProc, h, msg, wp, lp)
	return r
}

func createUI() {
	dpi, _, _ := pGetDpiForSystem.Call()
	if dpi == 0 {
		dpi = 96
	}
	sc := func(v int) uint { return uint(v * int(dpi) / 96) }
	ui = webview2.NewWithOptions(webview2.WebViewOptions{
		AutoFocus: true,
		DataPath:  filepath.Join(os.Getenv("LOCALAPPDATA"), "VidWallPC"),
		WindowOptions: webview2.WindowOptions{
			Title: "VidWall", Width: sc(1060), Height: sc(700), Center: true,
		},
	})
	if ui == nil {
		return
	}
	uiHwnd = uintptr(ui.Window())
	pShowWindow.Call(uiHwnd, 0)
	uiOrigProc, _, _ = pSetWindowLongPtrW.Call(uiHwnd, ^uintptr(3), syscall.NewCallback(uiProc)) // GWLP_WNDPROC
	pSendMessageW.Call(uiHwnd, 0x0080, 1, trayIcon) // WM_SETICON big
	pSendMessageW.Call(uiHwnd, 0x0080, 0, trayIcon) // small
	dark := int32(1)
	pDwmSetWindowAttr.Call(uiHwnd, 20, uintptr(unsafe.Pointer(&dark)), 4)
	ui.Navigate(fmt.Sprintf("http://127.0.0.1:%d/ui", port))
}

func showUI() {
	if ui == nil {
		return
	}
	if ic, _, _ := pIsIconic.Call(uiHwnd); ic != 0 {
		pShowWindow.Call(uiHwnd, 9) // SW_RESTORE
	} else {
		pShowWindow.Call(uiHwnd, 5) // SW_SHOW
	}
	pSetForegroundWindow.Call(uiHwnd)
	ui.Eval("window.onShown && onShown()")
}


// ---------- AI upscale integration ----------

var job UpscaleJob

func isHD(p string) bool { return strings.Contains(filepath.Base(p), "_HD_") }

// largest monitor resolution, in real pixels
func targetRes() (int, int) {
	w, h := 1920, 1080
	if ms := monitors(); len(ms) > 0 {
		w, h = 0, 0
		for _, m := range ms {
			w, h = max(w, int(m.W)), max(h, int(m.H))
		}
	}
	return w, h
}


func hotkeyNames() []string {
	var n []string
	for _, h := range hotkeys {
		n = append(n, h.name)
	}
	return n
}

var restarting bool
