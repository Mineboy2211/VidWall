package com.mineboy.vwhome;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.wallpaper.WallpaperService;
import android.view.Choreographer;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.File;

/**
 * System live wallpaper: the video/GIF lives in the wallpaper layer, behind every window
 * (home screen, recents, notification shade) — this is how Niagara & co. stay smooth.
 */
public class VideoWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new VEngine();
    }

    class VEngine extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener {
        private SharedPreferences sp;
        private SurfaceHolder holder;
        private MediaPlayer mp;
        private Drawable gif;
        private String loadedKey = "";
        private boolean visible, surfaceReady, gifMode, canvasUsed, mediaUsed;
        private int fmt = PixelFormat.RGBX_8888;
        private long lastGifFrame;
        private final Handler h = new Handler(Looper.getMainLooper());
        private int errors;
        private long errorsSince;

        // If a live wallpaper crashes, Android removes it and puts the default wallpaper back.
        // So nothing in here is allowed to crash: every callback is guarded.
        private void guard(String where, Runnable r) {
            try { r.run(); } catch (Throwable t) { Log.w("VidWall", "wallpaper " + where, t); }
        }

        // the video stopped (decoder taken by a game/camera, media server restarted…): reload it
        private final Runnable reload = new Runnable() {
            @Override public void run() {
                guard("reload", new Runnable() { @Override public void run() {
                    loadedKey = "";
                    load();
                    if (visible) play();
                }});
            }
        };

        private void scheduleReload() {
            long now = System.currentTimeMillis();
            if (now - errorsSince > 60000) { errorsSince = now; errors = 0; }
            errors++;
            h.removeCallbacks(reload);
            h.postDelayed(reload, errors <= 5 ? 1200 : 15000);
        }

        private final Choreographer.FrameCallback gifLoop = new Choreographer.FrameCallback() {
            @Override public void doFrame(long t) {
                if (!visible || gif == null || !surfaceReady) return;
                long now = System.currentTimeMillis();
                if (now - lastGifFrame >= 30) { lastGifFrame = now; try { drawGif(); } catch (Throwable ignored) {} }
                Choreographer.getInstance().postFrameCallback(this);
            }
        };

        @Override
        public void onCreate(final SurfaceHolder sh) {
            super.onCreate(sh);
            sp = P.sp(VideoWallpaperService.this);
            guard("create", new Runnable() { @Override public void run() {
                sp.registerOnSharedPreferenceChangeListener(VEngine.this);
                setOffsetNotificationsEnabled(false);
                sh.setFormat(fmt);
            }});
        }

        @Override
        public void onDestroy() {
            h.removeCallbacks(reload);
            guard("destroy", new Runnable() { @Override public void run() {
                if (sp != null) sp.unregisterOnSharedPreferenceChangeListener(VEngine.this);
                release();
            }});
            super.onDestroy();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder sh) {
            super.onSurfaceCreated(sh);
            holder = sh;
            surfaceReady = true;
            canvasUsed = mediaUsed = false;
            loadedKey = "";
            guard("surfaceCreated", new Runnable() { @Override public void run() { load(); if (visible) play(); }});
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder sh, int f, int w, int hh) {
            super.onSurfaceChanged(sh, f, w, hh);
            if (gifMode) guard("surfaceChanged", new Runnable() { @Override public void run() { drawGif(); }});
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder sh) {
            surfaceReady = false;
            h.removeCallbacks(reload);
            guard("surfaceDestroyed", new Runnable() { @Override public void run() { release(); }});
            loadedKey = "";
            super.onSurfaceDestroyed(sh);
        }

        @Override
        public void onVisibilityChanged(final boolean v) {
            visible = v;
            guard("visibility", new Runnable() { @Override public void run() {
                if (v) {
                    checkDayNight();
                    load();
                    play();
                } else {
                    pause();
                }
            }});
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences s, String key) {
            if (key == null) return;
            switch (key) {
                case "active": case "fit": case "sound": case "speed":
                    h.post(new Runnable() { @Override public void run() { guard("pref", new Runnable() { @Override public void run() { load(); if (visible) play(); }}); } });
                    break;
                case "paused": case "saver":
                    h.post(new Runnable() { @Override public void run() { guard("pref", new Runnable() { @Override public void run() { if (visible) play(); }}); } });
                    break;
            }
        }

        private boolean shouldPlay() {
            if (!visible || sp.getBoolean("paused", false)) return false;
            if (sp.getBoolean("saver", true)) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && pm.isPowerSaveMode()) return false;
            }
            return true;
        }

        private void checkDayNight() {
            if (!sp.getBoolean("dnOn", false)) return;
            String p = P.period(VideoWallpaperService.this);
            if (p.equals(sp.getString("dnLast", ""))) return;
            sp.edit().putString("dnLast", p).apply();
            String v = sp.getString("day".equals(p) ? "dayVideo" : "nightVideo", "");
            if (!v.isEmpty() && new File(v).exists()) sp.edit().putString("active", v).apply();
        }

        private void load() {
            if (!surfaceReady || holder == null) return;
            String path = P.active(VideoWallpaperService.this);
            String key = path + "|" + sp.getString("fit", "cover") + "|" + sp.getBoolean("sound", false) + "|" + sp.getFloat("speed", 1f);
            // same file, but the player died or the GIF was dropped: load it again
            boolean healthy = path.isEmpty() || (P.isGif(path) ? gif != null : mp != null);
            if (key.equals(loadedKey) && healthy) return;
            if (!path.isEmpty() && !new File(path).exists()) { loadedKey = key; release(); drawBlack(); return; }
            boolean wantGif = P.isGif(path);
            // MediaPlayer and canvas drawing can't share one surface: switching type asks for a new surface
            boolean needCanvas = wantGif || path.isEmpty();
            if ((needCanvas && mediaUsed) || (!needCanvas && canvasUsed)) {
                release();
                gifMode = wantGif;
                loadedKey = "";
                fmt = fmt == PixelFormat.RGBX_8888 ? PixelFormat.RGBA_8888 : PixelFormat.RGBX_8888;
                holder.setFormat(fmt); // -> onSurfaceDestroyed / onSurfaceCreated with a fresh surface
                return;
            }
            loadedKey = key;
            release();
            gifMode = wantGif;
            if (path.isEmpty()) { drawBlack(); return; }
            if (wantGif) {
                try {
                    // decode at screen size: a huge upscaled GIF must not run the wallpaper out of memory
                    final Rect fr = holder.getSurfaceFrame();
                    gif = ImageDecoder.decodeDrawable(ImageDecoder.createSource(new File(path)), new ImageDecoder.OnHeaderDecodedListener() {
                        @Override public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source src) {
                            int iw = info.getSize().getWidth(), ih = info.getSize().getHeight();
                            int sw = Math.max(1, Math.max(fr.width(), fr.height())), longest = Math.max(iw, ih);
                            if (longest > sw && sw > 1) {
                                float k = (float) sw / longest;
                                d.setTargetSize(Math.max(1, Math.round(iw * k)), Math.max(1, Math.round(ih * k)));
                            }
                            d.setAllocator(ImageDecoder.ALLOCATOR_DEFAULT);
                        }
                    });
                    if (gif instanceof AnimatedImageDrawable) {
                        ((AnimatedImageDrawable) gif).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    }
                    drawGif();
                } catch (Throwable e) {
                    Log.w("VidWall", "gif", e);
                    gif = null;
                    drawBlack();
                }
            } else {
                try {
                    final MediaPlayer m = new MediaPlayer();
                    mp = m;
                    m.setSurface(holder.getSurface());
                    mediaUsed = true;
                    m.setDataSource(path);
                    m.setLooping(true);
                    boolean sound = sp.getBoolean("sound", false);
                    m.setVolume(sound ? 1f : 0f, sound ? 1f : 0f);
                    m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override public void onPrepared(MediaPlayer p) {
                            if (p != mp) return;
                            try { p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING); } catch (Exception ignored) {}
                            float speed = sp.getFloat("speed", 1f);
                            if (speed != 1f) {
                                try { p.setPlaybackParams(new PlaybackParams().setSpeed(speed)); } catch (Exception ignored) {}
                            }
                            errors = 0;
                            try { if (shouldPlay()) p.start(); else p.seekTo(0); } catch (Throwable ignored) {}
                        }
                    });
                    m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override public boolean onError(MediaPlayer p, int a, int b) {
                            Log.w("VidWall", "player error " + a + "/" + b);
                            if (p == mp) { try { p.release(); } catch (Throwable ignored) {} mp = null; scheduleReload(); }
                            return true;
                        }
                    });
                    m.prepareAsync();
                } catch (Throwable e) {
                    Log.w("VidWall", "player", e);
                    release();
                    scheduleReload();
                }
            }
        }

        private void play() {
            if (!shouldPlay()) { pause(); return; }
            try { if (mp != null && !mp.isPlaying()) mp.start(); } catch (Exception ignored) {}
            if (gif instanceof AnimatedImageDrawable) {
                try { ((AnimatedImageDrawable) gif).start(); } catch (Throwable ignored) {}
                Choreographer.getInstance().removeFrameCallback(gifLoop);
                Choreographer.getInstance().postFrameCallback(gifLoop);
            }
        }

        private void pause() {
            try { if (mp != null && mp.isPlaying()) mp.pause(); } catch (Exception ignored) {}
            if (gif instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) gif).stop();
            Choreographer.getInstance().removeFrameCallback(gifLoop);
        }

        private void release() {
            Choreographer.getInstance().removeFrameCallback(gifLoop);
            if (mp != null) {
                try { mp.release(); } catch (Exception ignored) {}
                mp = null;
            }
            if (gif instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) gif).stop();
            gif = null;
        }

        private void drawGif() {
            if (gif == null || holder == null || !surfaceReady) return;
            Canvas c = null;
            try {
                c = holder.getSurface().lockHardwareCanvas();
                canvasUsed = true;
                int W = c.getWidth(), H = c.getHeight();
                int iw = Math.max(1, gif.getIntrinsicWidth()), ih = Math.max(1, gif.getIntrinsicHeight());
                boolean cover = !"contain".equals(sp.getString("fit", "cover"));
                float s = cover ? Math.max((float) W / iw, (float) H / ih) : Math.min((float) W / iw, (float) H / ih);
                int w = Math.round(iw * s), hh = Math.round(ih * s);
                c.drawColor(Color.BLACK);
                gif.setBounds((W - w) / 2, (H - hh) / 2, (W - w) / 2 + w, (H - hh) / 2 + hh);
                gif.draw(c);
            } catch (Throwable ignored) {
            } finally {
                if (c != null) try { holder.getSurface().unlockCanvasAndPost(c); } catch (Throwable ignored) {}
            }
        }

        private void drawBlack() {
            if (holder == null || !surfaceReady) return;
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                canvasUsed = true;
                if (c != null) c.drawColor(0xFF0B0D1A);
            } catch (Exception ignored) {
            } finally {
                if (c != null) try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) {}
            }
        }
    }
}
