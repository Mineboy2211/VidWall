//go:build windows

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// ---------- config sections ----------

type DayNightCfg struct {
	Enabled    bool   `json:"enabled"`
	Mode       string `json:"mode"` // "fixed" | "sun"
	DayVideo   string `json:"dayVideo"`
	NightVideo string `json:"nightVideo"`
	DayStart   string `json:"dayStart"`   // "07:00"
	NightStart string `json:"nightStart"` // "19:30"
}

type WidgetsCfg struct {
	Clock   bool    `json:"clock"`
	Seconds bool    `json:"seconds"`
	Date    bool    `json:"date"`
	Weather bool    `json:"weather"`
	City    string  `json:"city"`
	Lat     float64 `json:"lat"`
	Lon     float64 `json:"lon"`
	Pos     string  `json:"pos"`  // tl tr bl br center
	Size    string  `json:"size"` // s m l
}

type VizCfg struct {
	Enabled bool    `json:"enabled"`
	Style   string  `json:"style"` // bars | wave | circle | glow
	Color   string  `json:"color"`
	Sens    float64 `json:"sens"`
	Pos     string  `json:"pos"`    // bottom | center
	Source  string  `json:"source"` // "" = all PC sound, "app:discord.exe", "out:<id>", "in:<id>"
}

func (c *Config) defaults() {
	if c.DayNight.Mode == "" {
		c.DayNight.Mode = "fixed"
	}
	if c.DayNight.DayStart == "" {
		c.DayNight.DayStart = "07:00"
	}
	if c.DayNight.NightStart == "" {
		c.DayNight.NightStart = "19:30"
	}
	if c.Widgets.Pos == "" {
		c.Widgets.Pos = "tr"
	}
	if c.Widgets.Size == "" {
		c.Widgets.Size = "m"
	}
	switch c.Viz.Style {
	case "", "bars":
		c.Viz.Style = "spectre"
	case "wave":
		c.Viz.Style = "aurore"
	case "circle":
		c.Viz.Style = "anneau"
	case "glow":
		c.Viz.Style = "lueur"
	}
	if c.Viz.Color == "" {
		c.Viz.Color = "#7c8cff"
	}
	if c.Viz.Sens <= 0 {
		c.Viz.Sens = 1
	}
	if c.Viz.Pos == "" {
		c.Viz.Pos = "bottom"
	}
}

func videosDir(sub string) string {
	d := filepath.Join(os.Getenv("USERPROFILE"), "Videos", "VidWall", sub)
	_ = os.MkdirAll(d, 0755)
	return d
}

func toolsDir() string { return filepath.Join(os.Getenv("LOCALAPPDATA"), "VidWallPC", "tools") }

// ---------- generic background jobs ----------

// startJob runs one tool at a time. If apply is true, the result becomes the wallpaper.
func startJob(kind, src string, apply bool, work func(ctx context.Context) (string, error)) {
	job.mu.Lock()
	if job.Running {
		job.mu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	job.Running, job.Kind, job.Phase, job.Progress, job.Src, job.Err, job.Done = true, kind, "tools", 0, src, "", ""
	job.Started = time.Now().Unix()
	job.cancel = cancel
	job.mu.Unlock()
	pushUI()

	go func() {
		out, err := work(ctx)
		log.Println("job", kind, "finished", out, err)
		job.mu.Lock()
		job.Running, job.cancel = false, nil
		switch {
		case err == nil:
			job.Done, job.Phase, job.Progress = out, "done", 1
		case errors.Is(err, errAlready):
			job.Err = "already"
		case errors.Is(err, errAlready60):
			job.Err = "already60"
		case ctx.Err() != nil:
			job.Err = "cancelled"
		default:
			job.Err = err.Error()
		}
		job.mu.Unlock()
		wv.Dispatch(func() {
			if err == nil && out != "" {
				if apply {
					if strings.EqualFold(cfg.Video, src) || cfg.Video == "" {
						cfg.Video = out
						videoVer = time.Now().UnixNano()
					}
					addRecent(out)
					saveConfig()
					applyState()
				}
			}
			pushUI()
		})
	}()
}

func startUpscale(src string) {
	tw, th := targetRes()
	model := "realesr-animevideov3"
	if cfg.HDStyle == "real" {
		model = "realesrgan-x4plus"
	}
	startJob("upscale", src, true, func(ctx context.Context) (string, error) {
		t, err := ensureTools(ctx, toolsDir(), job.set)
		if err != nil {
			return "", err
		}
		res, err := upscaleVideo(ctx, t, src, videosDir("HD"), tw, th, model, job.set)
		return res.Out, err
	})
}

func start60(src string) {
	startJob("fps60", src, true, func(ctx context.Context) (string, error) {
		t, err := ensureFFmpeg(ctx, toolsDir(), job.set)
		if err != nil {
			return "", err
		}
		exe, model, err := ensureRIFE(ctx, toolsDir(), job.set)
		if err != nil {
			return "", err
		}
		return interpolate60(ctx, t, exe, model, src, videosDir("60fps"), job.set)
	})
}

func startEdit(src string, o EditOpts) {
	startJob("edit", src, true, func(ctx context.Context) (string, error) {
		t, err := ensureFFmpeg(ctx, toolsDir(), job.set)
		if err != nil {
			return "", err
		}
		return editVideo(ctx, t, src, videosDir("Edits"), o, job.set)
	})
}

func startExport(src, kind string, short bool) {
	startJob(kind, src, false, func(ctx context.Context) (string, error) {
		t, err := ensureFFmpeg(ctx, toolsDir(), job.set)
		if err != nil {
			return "", err
		}
		if kind == "gif" {
			return exportGIF(ctx, t, src, videosDir("Exports"), job.set)
		}
		return exportPhone(ctx, t, src, videosDir("Exports"), short, job.set)
	})
}

func openInExplorer(p string) {
	if p == "" {
		return
	}
	cmd := exec.Command("explorer.exe", "/select,", p)
	_ = cmd.Start()
}

// ---------- day / night ----------

var lastPeriod string

func parseHM(s string) int {
	p := strings.Split(s, ":")
	if len(p) != 2 {
		return -1
	}
	h, e1 := strconv.Atoi(p[0])
	m, e2 := strconv.Atoi(p[1])
	if e1 != nil || e2 != nil {
		return -1
	}
	return h*60 + m
}

// dayBounds returns minutes-of-day where day and night start
func dayBounds() (int, int) {
	d, n := parseHM(cfg.DayNight.DayStart), parseHM(cfg.DayNight.NightStart)
	if cfg.DayNight.Mode == "sun" {
		weather.mu.Lock()
		sr, ss := weather.Sunrise, weather.Sunset
		weather.mu.Unlock()
		if a := hmFromISO(sr); a >= 0 {
			d = a
		}
		if b := hmFromISO(ss); b >= 0 {
			n = b
		}
	}
	if d < 0 {
		d = 7 * 60
	}
	if n < 0 {
		n = 19*60 + 30
	}
	return d, n
}

func hmFromISO(s string) int { // "2026-10-25T07:12"
	if i := strings.Index(s, "T"); i >= 0 {
		return parseHM(s[i+1:])
	}
	return -1
}

func currentPeriod() string {
	now := time.Now()
	m := now.Hour()*60 + now.Minute()
	d, n := dayBounds()
	isDay := false
	if d < n {
		isDay = m >= d && m < n
	} else {
		isDay = m >= d || m < n
	}
	if isDay {
		return "day"
	}
	return "night"
}

func dayNightTick() {
	if !cfg.DayNight.Enabled {
		lastPeriod = ""
		return
	}
	p := currentPeriod()
	if p == lastPeriod {
		return
	}
	lastPeriod = p
	v := cfg.DayNight.DayVideo
	if p == "night" {
		v = cfg.DayNight.NightVideo
	}
	if v == "" || !fileExists(v) || strings.EqualFold(v, cfg.Video) {
		return
	}
	cfg.Video = v
	addRecent(v)
	videoVer = time.Now().UnixNano()
	saveConfig()
	applyState()
}

// ---------- weather (open-meteo, no key needed) ----------

type weatherState struct {
	mu      sync.Mutex
	Temp    float64 `json:"temp"`
	Code    int     `json:"code"`
	IsDay   int     `json:"isDay"`
	City    string  `json:"city"`
	Sunrise string  `json:"sunrise"`
	Sunset  string  `json:"sunset"`
	OK      bool    `json:"ok"`
	Err     string  `json:"err"`
}

var weather weatherState
var weatherKick = make(chan struct{}, 1)

func kickWeather() {
	select {
	case weatherKick <- struct{}{}:
	default:
	}
}

func getJSON(u string, v any) error {
	c := &http.Client{Timeout: 15 * time.Second}
	resp, err := c.Get(u)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("%s", resp.Status)
	}
	return json.NewDecoder(resp.Body).Decode(v)
}

// geocode a city name (runs in background), then refresh the weather
func setCity(name string) {
	go func() {
		var r struct {
			Results []struct {
				Name      string  `json:"name"`
				Country   string  `json:"country"`
				Latitude  float64 `json:"latitude"`
				Longitude float64 `json:"longitude"`
			} `json:"results"`
		}
		err := getJSON("https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&name="+url.QueryEscape(name), &r)
		wv.Dispatch(func() {
			if err != nil || len(r.Results) == 0 {
				weather.mu.Lock()
				weather.Err = "Ville introuvable"
				weather.mu.Unlock()
			} else {
				g := r.Results[0]
				cfg.Widgets.City = g.Name
				cfg.Widgets.Lat, cfg.Widgets.Lon = g.Latitude, g.Longitude
				weather.mu.Lock()
				weather.Err = ""
				weather.mu.Unlock()
				saveConfig()
				kickWeather()
			}
			pushUI()
		})
	}()
}

func weatherLoop() {
	for {
		lat, lon, city := cfg.Widgets.Lat, cfg.Widgets.Lon, cfg.Widgets.City
		if city != "" && (cfg.Widgets.Weather || (cfg.DayNight.Enabled && cfg.DayNight.Mode == "sun")) {
			var r struct {
				Current struct {
					Temp  float64 `json:"temperature_2m"`
					Code  int     `json:"weather_code"`
					IsDay int     `json:"is_day"`
				} `json:"current"`
				Daily struct {
					Sunrise []string `json:"sunrise"`
					Sunset  []string `json:"sunset"`
				} `json:"daily"`
			}
			u := fmt.Sprintf("https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&daily=sunrise,sunset&forecast_days=1&timezone=auto", lat, lon)
			err := getJSON(u, &r)
			weather.mu.Lock()
			if err == nil {
				weather.Temp, weather.Code, weather.IsDay, weather.City, weather.OK, weather.Err = r.Current.Temp, r.Current.Code, r.Current.IsDay, city, true, ""
				if len(r.Daily.Sunrise) > 0 {
					weather.Sunrise, weather.Sunset = r.Daily.Sunrise[0], r.Daily.Sunset[0]
				}
			} else {
				weather.Err = "Météo indisponible"
			}
			weather.mu.Unlock()
			wv.Dispatch(func() { applyState(); pushUI() })
		}
		select {
		case <-weatherKick:
		case <-time.After(20 * time.Minute):
		}
	}
}

func (w *weatherState) snapshot() map[string]any {
	w.mu.Lock()
	defer w.mu.Unlock()
	return map[string]any{"temp": w.Temp, "code": w.Code, "isDay": w.IsDay, "city": w.City,
		"sunrise": w.Sunrise, "sunset": w.Sunset, "ok": w.OK, "err": w.Err}
}

// ---------- keyboard shortcuts ----------

var (
	pRegisterHotKey   = user32.NewProc("RegisterHotKey")
	pUnregisterHotKey = user32.NewProc("UnregisterHotKey")
	pIsZoomed         = user32.NewProc("IsZoomed")
	pGetPowerStatus   = kernel32.NewProc("GetSystemPowerStatus")
)

const wmHotkey = 0x0312

var hotkeys = []struct {
	id, vk uintptr
	name   string
}{
	{1, 0x50, "Ctrl+Alt+P : pause / lecture"},
	{2, 0x27, "Ctrl+Alt+→ : vidéo suivante"},
	{3, 0x25, "Ctrl+Alt+← : vidéo précédente"},
	{4, 0x4D, "Ctrl+Alt+M : son on/off"},
	{5, 0x56, "Ctrl+Alt+V : ouvrir VidWall"},
}

func registerHotkeys(on bool) {
	for _, h := range hotkeys {
		pUnregisterHotKey.Call(trayHwnd, h.id)
		if on {
			pRegisterHotKey.Call(trayHwnd, h.id, 0x1|0x2|0x4000, h.vk) // ALT|CTRL|NOREPEAT
		}
	}
}

func onHotkey(id uintptr) {
	switch id {
	case 1:
		handleCmd(cmdPause)
	case 2:
		stepVideo(1)
	case 3:
		stepVideo(-1)
	case 4:
		cfg.Sound = !cfg.Sound
		saveConfig()
		applyState()
	case 5:
		showUI()
	}
	pushUI()
}

// stepVideo switches to the next/previous existing video of the library
func stepVideo(dir int) {
	var list []string
	for _, p := range cfg.Recent {
		if fileExists(p) {
			list = append(list, p)
		}
	}
	if len(list) < 2 {
		return
	}
	// the library order changes when a video is used, so keep a stable order by name
	sortStrings(list)
	cur := 0
	for i, p := range list {
		if strings.EqualFold(p, cfg.Video) {
			cur = i
		}
	}
	next := list[(cur+dir+len(list))%len(list)]
	cfg.Video = next
	cfg.Paused = false
	videoVer = time.Now().UnixNano()
	saveConfig()
	applyState()
}

func sortStrings(a []string) {
	for i := 1; i < len(a); i++ {
		for j := i; j > 0 && strings.ToLower(a[j]) < strings.ToLower(a[j-1]); j-- {
			a[j], a[j-1] = a[j-1], a[j]
		}
	}
}

// ---------- performance checks ----------

type powerStatus struct {
	ACLine, BatteryFlag, Percent, Saver byte
	LifeTime, FullLifeTime              uint32
}

// onBattery: true when unplugged; hasBattery: false on desktops
func onBattery() (bool, bool) {
	var ps powerStatus
	if r, _, _ := pGetPowerStatus.Call(uintptr(unsafe.Pointer(&ps))); r == 0 {
		return false, false
	}
	has := ps.BatteryFlag != 128 && ps.BatteryFlag != 255
	return has && ps.ACLine == 0, has
}

// desktopCovered: the active window is maximized (the wallpaper can't be seen)
func desktopCovered() bool {
	fg, _, _ := pGetForegroundWindow.Call()
	if fg == 0 || fg == wvHwnd || fg == uiHwnd {
		return false
	}
	cls := make([]uint16, 256)
	pGetClassNameW.Call(fg, uintptr(unsafe.Pointer(&cls[0])), 256)
	switch windows.UTF16ToString(cls) {
	case "Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd":
		return false
	}
	z, _, _ := pIsZoomed.Call(fg)
	return z != 0
}

var pauseReason string

func perfTick() {
	reason := ""
	switch {
	case cfg.AutoPause && fullscreenAppActive():
		reason = "fullscreen"
	case cfg.CoveredPause && desktopCovered():
		reason = "covered"
	case cfg.BatteryPause:
		if on, _ := onBattery(); on {
			reason = "battery"
		}
	}
	paused := reason != ""
	if paused != autoPaused || reason != pauseReason {
		autoPaused, pauseReason = paused, reason
		applyState()
	}
}
