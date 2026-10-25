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

// Releases are published on GitHub. PC builds use tags like "pc-v1.8.0" with an asset "VidWallPC.exe".
const (
	appVersion  = "1.8.0"
	releasesAPI = "https://api.github.com/repos/Mineboy2211/VidWall/releases?per_page=30"
	tagPrefix   = "pc-v"
	assetName   = "VidWallPC.exe"
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

type ghRelease struct {
	Tag        string `json:"tag_name"`
	Body       string `json:"body"`
	Draft      bool   `json:"draft"`
	Prerelease bool   `json:"prerelease"`
	Assets     []struct {
		Name string `json:"name"`
		URL  string `json:"browser_download_url"`
		Size int64  `json:"size"`
	} `json:"assets"`
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
		req, _ := http.NewRequest("GET", releasesAPI, nil)
		req.Header.Set("Accept", "application/vnd.github+json")
		req.Header.Set("User-Agent", "VidWallPC/"+appVersion)
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
		var rels []ghRelease
		if err := json.NewDecoder(resp.Body).Decode(&rels); err != nil {
			return err
		}
		best := ""
		var pick *ghRelease
		for i := range rels {
			r := &rels[i]
			if r.Draft || r.Prerelease || !strings.HasPrefix(r.Tag, tagPrefix) {
				continue
			}
			v := strings.TrimPrefix(r.Tag, tagPrefix)
			if best == "" || newer(v, best) {
				best, pick = v, r
			}
		}
		upd.mu.Lock()
		defer upd.mu.Unlock()
		upd.Checked = time.Now().Unix()
		if pick == nil || !newer(best, appVersion) {
			upd.Available = false
			upd.Latest = best
			return nil
		}
		for _, a := range pick.Assets {
			if strings.EqualFold(a.Name, assetName) {
				upd.Available, upd.Latest, upd.Notes, upd.URL, upd.Size = true, best, strings.TrimSpace(pick.Body), a.URL, a.Size
				return nil
			}
		}
		return errors.New("la nouvelle version n'a pas de fichier .exe")
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
