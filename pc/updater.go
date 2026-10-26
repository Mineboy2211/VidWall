package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Updates are described by updates.json in the GitHub repo (see README there).
const (
	appVersion  = "1.9.0"
	manifestURL = "https://raw.githubusercontent.com/Mineboy2211/VidWall/main/updates.json"
)

type UpdateState struct {
	mu        sync.Mutex
	Checking  bool    `json:"checking"`
	Available bool    `json:"available"`
	Latest    string  `json:"latest"`
	Notes     string  `json:"notes"`
	URL       string  `json:"-"`
	Size      int64   `json:"size"`
	Running   bool    `json:"running"`
	Progress  float64 `json:"progress"`
	Err       string  `json:"err"`
	Checked   int64   `json:"checked"`
}

var upd UpdateState

func (u *UpdateState) snapshot() map[string]any {
	u.mu.Lock()
	defer u.mu.Unlock()
	return map[string]any{"version": appVersion, "checking": u.Checking, "available": u.Available, "latest": u.Latest,
		"notes": u.Notes, "size": u.Size, "running": u.Running, "progress": u.Progress, "err": u.Err, "checked": u.Checked}
}

// newer reports whether version a is greater than b ("1.10.0" > "1.9.2").
func newer(a, b string) bool {
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < max(len(pa), len(pb)); i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(pa[i])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(pb[i])
		}
		if x != y {
			return x > y
		}
	}
	return false
}

type manifestEntry struct {
	Version string `json:"version"`
	URL     string `json:"url"`
	Notes   string `json:"notes"`
	Size    int64  `json:"size"`
}

// checkUpdate asks GitHub for the newest PC release. Safe to call from any goroutine.
func checkUpdate() {
	upd.mu.Lock()
	if upd.Checking || upd.Running {
		upd.mu.Unlock()
		return
	}
	upd.Checking, upd.Err = true, ""
	upd.mu.Unlock()

	err := func() error {
		req, _ := http.NewRequest("GET", fmt.Sprintf("%s?t=%d", manifestURL, time.Now().Unix()/300), nil)
		req.Header.Set("User-Agent", "VidWallPC/"+appVersion)
		req.Header.Set("Cache-Control", "no-cache")
		c := &http.Client{Timeout: 20 * time.Second}
		resp, err := c.Do(req)
		if err != nil {
			return errors.New("pas de connexion")
		}
		defer resp.Body.Close()
		if resp.StatusCode == 404 {
			return errors.New("aucune version publiée pour l'instant")
		}
		if resp.StatusCode != 200 {
			return fmt.Errorf("GitHub : %s", resp.Status)
		}
		var m map[string]manifestEntry
		if err := json.NewDecoder(resp.Body).Decode(&m); err != nil {
			return errors.New("fichier de mise à jour invalide")
		}
		e := m["pc"]
		upd.mu.Lock()
		defer upd.mu.Unlock()
		upd.Checked = time.Now().Unix()
		upd.Latest = e.Version
		if e.Version == "" || e.URL == "" || !newer(e.Version, appVersion) {
			upd.Available = false
			return nil
		}
		upd.Available, upd.Notes, upd.URL, upd.Size = true, strings.TrimSpace(e.Notes), e.URL, e.Size
		return nil
	}()
	upd.mu.Lock()
	upd.Checking = false
	if err != nil {
		upd.Err = err.Error()
	}
	upd.mu.Unlock()
}

// installUpdate downloads the new exe, swaps it with the running one and restarts.
// The running exe can be renamed on Windows, so: current -> .old, new -> current, start it, quit.
func installUpdate(restart func(exe string) error) {
	upd.mu.Lock()
	if upd.Running || !upd.Available || upd.URL == "" {
		upd.mu.Unlock()
		return
	}
	upd.Running, upd.Progress, upd.Err = true, 0, ""
	url := upd.URL
	upd.mu.Unlock()

	err := func() error {
		exe, err := os.Executable()
		if err != nil {
			return err
		}
		tmp := exe + ".new"
		if err := download(context.Background(), url, tmp, func(p float64) {
			upd.mu.Lock()
			upd.Progress = p
			upd.mu.Unlock()
		}); err != nil {
			os.Remove(tmp)
			return fmt.Errorf("téléchargement : %w", err)
		}
		if st, err := os.Stat(tmp); err != nil || st.Size() < 1<<20 {
			os.Remove(tmp)
			return errors.New("fichier téléchargé invalide")
		}
		old := exe + ".old"
		os.Remove(old)
		if err := os.Rename(exe, old); err != nil {
			os.Remove(tmp)
			return fmt.Errorf("impossible de remplacer l'app : %w", err)
		}
		if err := os.Rename(tmp, exe); err != nil {
			os.Rename(old, exe) // roll back
			return fmt.Errorf("impossible d'installer : %w", err)
		}
		return restart(exe)
	}()
	upd.mu.Lock()
	upd.Running = false
	if err != nil {
		upd.Err = err.Error()
	}
	upd.mu.Unlock()
}

// cleanupOldExe removes the previous version left by an update.
func cleanupOldExe() {
	if exe, err := os.Executable(); err == nil {
		for i := 0; i < 10; i++ {
			if err := os.Remove(exe + ".old"); err == nil || os.IsNotExist(err) {
				return
			}
			time.Sleep(500 * time.Millisecond)
		}
	}
}
