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

        private final Choreographer.FrameCallback gifLoop = new Choreographer.FrameCallback() {
            @Override public void doFrame(long t) {
                if (!visible || gif == null || !surfaceReady) return;
                long now = System.currentTimeMillis();
                if (now - lastGifFrame >= 30) { lastGifFrame = now; drawGif(); }
                Choreographer.getInstance().postFrameCallback(this);
            }
        };

        @Override
        public void onCreate(SurfaceHolder sh) {
            super.onCreate(sh);
            sp = P.sp(VideoWallpaperService.this);
            sp.registerOnSharedPreferenceChangeListener(this);
            setOffsetNotificationsEnabled(false);
            sh.setFormat(fmt);
        }

        @Override
        public void onDestroy() {
            sp.unregisterOnSharedPreferenceChangeListener(this);
            release();
            super.onDestroy();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder sh) {
            super.onSurfaceCreated(sh);
            holder = sh;
            surfaceReady = true;
            canvasUsed = mediaUsed = false;
            loadedKey = "";
            load();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder sh, int f, int w, int hh) {
            super.onSurfaceChanged(sh, f, w, hh);
            if (gifMode) drawGif();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder sh) {
            surfaceReady = false;
            release();
            loadedKey = "";
            super.onSurfaceDestroyed(sh);
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) {
                checkDayNight();
                load();
                play();
            } else {
                pause();
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences s, String key) {
            if (key == null) return;
            switch (key) {
                case "active": case "fit": case "sound": case "speed":
                    h.post(new Runnable() { @Override public void run() { load(); if (visible) play(); } });
                    break;
                case "paused": case "saver":
                    h.post(new Runnable() { @Override public void run() { if (visible) play(); } });
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
            if (key.equals(loadedKey)) return;
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
                    gif = ImageDecoder.decodeDrawable(ImageDecoder.createSource(new File(path)));
                    if (gif instanceof AnimatedImageDrawable) {
                        ((AnimatedImageDrawable) gif).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    }
                    drawGif();
                } catch (Exception e) {
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
                            if (shouldPlay()) p.start(); else p.seekTo(0);
                        }
                    });
                    m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override public boolean onError(MediaPlayer p, int a, int b) { return true; }
                    });
                    m.prepareAsync();
                } catch (Exception e) {
                    release();
                }
            }
        }

        private void play() {
            if (!shouldPlay()) { pause(); return; }
            try { if (mp != null && !mp.isPlaying()) mp.start(); } catch (Exception ignored) {}
            if (gif instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) gif).start();
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
            } catch (Exception ignored) {
            } finally {
                if (c != null) try { holder.getSurface().unlockCanvasAndPost(c); } catch (Exception ignored) {}
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
