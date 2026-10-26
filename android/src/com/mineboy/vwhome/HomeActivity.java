package com.mineboy.vwhome;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.ImageDecoder;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HomeActivity extends Activity {

    static class App {
        String label, key;
        ComponentName cn;
        ResolveInfo ri;
        Drawable icon;
    }

    private SharedPreferences sp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextureView tex;
    private ImageView gifView;
    private VizView viz;
    private TextView clock, date, wx;
    private LinearLayout widgetBox, dock;
    private TextView searchPill;
    private View homeContent;
    private float drawerProgress;
    private android.animation.ValueAnimator drawerAnimator;
    private GridLayout homeGrid;
    private FrameLayout drawer;
    private GridView drawerGrid;
    private EditText search;
    private final List<App> apps = new ArrayList<App>();
    private final List<App> filtered = new ArrayList<App>();
    private final Map<String, App> byKey = new HashMap<String, App>();
    private DrawerAdapter adapter;

    private MediaPlayer mp;
    private SurfaceTexture keptST;
    private int savedPos;
    private String savedPosPath = "";
    private Surface surface;
    private String loaded = "";
    private int vw, vh;
    private boolean resumed;
    private boolean sysWall;
    private String lastPeriod = "";
    private int insetTop, insetBottom;
    private GestureDetector gestures;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = P.sp(this);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setDecorFitsSystemWindows(false);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        tex = new TextureView(this);
        root.addView(tex, mp());
        gifView = new ImageView(this);
        gifView.setVisibility(View.GONE);
        root.addView(gifView, mp());

        // soft gradients so text stays readable on bright videos
        View shadeTop = new View(this);
        shadeTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0x00000000}));
        root.addView(shadeTop, new FrameLayout.LayoutParams(-1, P.dp(this, 220), Gravity.TOP));
        View shadeBot = new View(this);
        shadeBot.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x77000000, 0x00000000}));
        root.addView(shadeBot, new FrameLayout.LayoutParams(-1, P.dp(this, 260), Gravity.BOTTOM));

        viz = new VizView(this);
        root.addView(viz, mp());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, mp());

        widgetBox = new LinearLayout(this);
        widgetBox.setOrientation(LinearLayout.VERTICAL);
        widgetBox.setPadding(P.dp(this, 26), P.dp(this, 70), P.dp(this, 26), 0);
        clock = text(64, Typeface.create("sans-serif-thin", Typeface.NORMAL));
        date = text(17, Typeface.create("sans-serif", Typeface.NORMAL));
        wx = text(15, Typeface.create("sans-serif", Typeface.NORMAL));
        widgetBox.addView(clock);
        widgetBox.addView(date);
        widgetBox.addView(wx);
        content.addView(widgetBox, new LinearLayout.LayoutParams(-1, -2));

        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));

        homeGrid = new GridLayout(this);
        homeGrid.setColumnCount(4);
        homeGrid.setPadding(P.dp(this, 10), 0, P.dp(this, 10), P.dp(this, 14));
        content.addView(homeGrid, new LinearLayout.LayoutParams(-1, -2));

        searchPill = new TextView(this);
        searchPill.setText("🔍   Rechercher");
        searchPill.setTextColor(0xDDFFFFFF);
        searchPill.setTextSize(15);
        searchPill.setGravity(Gravity.CENTER_VERTICAL);
        searchPill.setPadding(P.dp(this, 20), 0, P.dp(this, 20), 0);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0x33FFFFFF);
        pbg.setCornerRadius(P.dp(this, 26));
        pbg.setStroke(P.dp(this, 1), 0x22FFFFFF);
        searchPill.setBackground(pbg);
        searchPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSearch(); }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, P.dp(this, 50));
        plp.setMargins(P.dp(this, 14), 0, P.dp(this, 14), P.dp(this, 12));
        content.addView(searchPill, plp);

        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER);
        GradientDrawable dbg = new GradientDrawable();
        dbg.setColor(0x33FFFFFF);
        dbg.setCornerRadius(P.dp(this, 30));
        dbg.setStroke(P.dp(this, 1), 0x22FFFFFF);
        dock.setBackground(dbg);
        dock.setPadding(P.dp(this, 8), P.dp(this, 10), P.dp(this, 8), P.dp(this, 10));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.setMargins(P.dp(this, 14), 0, P.dp(this, 14), P.dp(this, 14));
        content.addView(dock, dlp);

        // keep the visualizer just above the search bar / dock
        content.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int top = searchPill.getVisibility() == View.VISIBLE ? searchPill.getTop()
                        : dock.getVisibility() == View.VISIBLE ? dock.getTop() : v.getHeight() - insetBottom - P.dp(HomeActivity.this, 20);
                if (homeGrid.getChildCount() > 0) top = homeGrid.getTop();
                viz.bottomInset = v.getHeight() - top + P.dp(HomeActivity.this, 6);
            }
        });
        buildDrawer();
        setContentView(root);

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                insetTop = in.getInsets(WindowInsets.Type.systemBars()).top;
                insetBottom = in.getInsets(WindowInsets.Type.systemBars()).bottom;
                ((LinearLayout.LayoutParams) dock.getLayoutParams()).bottomMargin = insetBottom + P.dp(HomeActivity.this, 12);
                dock.requestLayout();
                drawer.setPadding(0, insetTop + P.dp(HomeActivity.this, 12), 0, insetBottom);
                return in;
            }
        });

        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
                if (keptST != null && keptST != st) {
                    // a fresh texture was created: restore ours so the video keeps playing without restarting
                    try { tex.setSurfaceTexture(keptST); return; } catch (Exception ignored) {}
                }
                keptST = st;
                if (surface == null) surface = new Surface(st);
                loaded = "";
                applyWallpaper();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) { fitVideo(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                return false; // we keep the texture (and the player) alive; released in onDestroy
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dy = e2.getY() - e1.getY();
                if (dy > P.dp(HomeActivity.this, 60) && Math.abs(vy) > Math.abs(vx)) { expandNotifications(); return true; }
                return false;
            }
            @Override public void onLongPress(MotionEvent e) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                // double tap: pause / resume the wallpaper
                boolean p = !sp.getBoolean("paused", false);
                sp.edit().putBoolean("paused", p).apply();
                if (p) pausePlayback(); else resumePlayback();
                Toast.makeText(HomeActivity.this, p ? "Fond d'écran en pause" : "Lecture", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        homeContent = content;
        final int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        content.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            boolean dragging;
            android.view.VelocityTracker vt;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (vt == null) vt = android.view.VelocityTracker.obtain();
                vt.addMovement(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY(); dragging = false;
                        gestures.onTouchEvent(e);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = e.getRawY() - downY, dx = e.getRawX() - downX;
                        if (!dragging && dy < -slop && Math.abs(dy) > Math.abs(dx)) {
                            dragging = true;
                            MotionEvent c = MotionEvent.obtain(e);
                            c.setAction(MotionEvent.ACTION_CANCEL);
                            gestures.onTouchEvent(c);  // no long-press while dragging
                            c.recycle();
                            drawerAnim(false);
                            drawer.setVisibility(View.VISIBLE);
                        }
                        if (dragging) setDrawerProgress(Math.max(0, Math.min(1, -(dy + slop) / (root.getHeight() * 0.6f))));
                        else gestures.onTouchEvent(e);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (dragging) {
                            vt.computeCurrentVelocity(1000);
                            float vy = vt.getYVelocity();
                            if (vy < -700 || (vy < 700 && drawerProgress > 0.3f)) openDrawer(); else closeDrawer();
                        } else gestures.onTouchEvent(e);
                        vt.recycle(); vt = null; dragging = false;
                        return true;
                    }
                }
                return gestures.onTouchEvent(e);
            }
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        loadApps();
        if (P.library(this).isEmpty()) {
            Toast.makeText(this, "Appuie longuement sur l'écran pour choisir ta vidéo", Toast.LENGTH_LONG).show();
        }
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        sysWall = isOurWallpaper();
        root.setBackgroundColor(sysWall ? Color.TRANSPARENT : Color.BLACK);
        boolean was = sp.getBoolean("wallWasOurs", false);
        if (was != sysWall) sp.edit().putBoolean("wallWasOurs", sysWall).apply();
        if (was && !sysWall) offerWallpaperBack();
        else maybeAskWallpaper();
        if (keptST != null && tex.getSurfaceTexture() != keptST) {
            try { tex.setSurfaceTexture(keptST); } catch (Exception ignored) {}
        }
        checkDayNight();
        applyWallpaper();
        applyWidgets();
        refreshDock();
        searchPill.setVisibility(sp.getBoolean("homeSearch", true) ? View.VISIBLE : View.GONE);
        dock.setVisibility(sp.getBoolean("showDock", false) ? View.VISIBLE : View.GONE);
        startViz();
        maybeRefreshWeather();
        Updater.autoCheck(this);
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        h.removeCallbacks(tick);
        pausePlayback();
        try {
            if (mp != null) { savedPos = mp.getCurrentPosition(); savedPosPath = P.active(this); }
        } catch (Exception ignored) {}
        viz.stop();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (menuLayer != null) { FrameLayout l = menuLayer; menuLayer = null; blur(drawer, 0); blur(homeContent, 0); root.removeView(l); }
        if (drawerProgress > 0) closeDrawer(false); // back on the home page next time
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // home button pressed while already home
        if (menuOpen()) closeMenu();
        if (drawerProgress > 0) closeDrawer(false);
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (menuOpen()) { closeMenu(); return; }
        if (drawerProgress > 0) closeDrawer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) {}
        releasePlayer();
        if (surface != null) surface.release();
        if (keptST != null) keptST.release();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            Calendar c = Calendar.getInstance();
            if (c.get(Calendar.SECOND) == 0) checkDayNight();
            h.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    // ---------------------------------------------------------------- wallpaper

    private void checkDayNight() {
        if (!sp.getBoolean("dnOn", false)) { lastPeriod = ""; return; }
        String p = P.period(this);
        if (p.equals(lastPeriod)) return;
        lastPeriod = p;
        String v = sp.getString("day".equals(p) ? "dayVideo" : "nightVideo", "");
        if (!v.isEmpty() && new File(v).exists() && !v.equals(sp.getString("active", ""))) {
            sp.edit().putString("active", v).apply();
            if (resumed) applyWallpaper();
        }
    }

    private boolean saverBlocks() {
        if (!sp.getBoolean("saver", true)) return false;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isPowerSaveMode();
    }

    static boolean isOurWallpaper(Context c) {
        try {
            android.app.WallpaperInfo wi = android.app.WallpaperManager.getInstance(c).getWallpaperInfo();
            return wi != null && c.getPackageName().equals(wi.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isOurWallpaper() { return isOurWallpaper(this); }

    static void askSetWallpaper(Activity a) {
        try {
            Intent i = new Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(a, VideoWallpaperService.class));
            a.startActivity(i);
        } catch (Exception e) {
            try { a.startActivity(new Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); } catch (Exception ignored) {}
        }
    }

    // Android removed our live wallpaper (it does that when a wallpaper crashes): offer to put it back
    private void offerWallpaperBack() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Fond animé retiré")
                .setMessage("Android a remis le fond d'écran par défaut. Tu veux remettre ta vidéo ?")
                .setPositiveButton("Remettre", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { askSetWallpaper(HomeActivity.this); }
                })
                .setNegativeButton("Non", null).show();
    }

    private void maybeAskWallpaper() {
        if (sysWall || sp.getBoolean("wpAsked", false) || P.library(this).isEmpty()) return;
        sp.edit().putBoolean("wpAsked", true).apply();
        new android.app.AlertDialog.Builder(this)
                .setTitle("Transitions fluides ✨")
                .setMessage("Active le fond d'écran animé VidWall : ta vidéo restera derrière tout (apps récentes, notifications) comme avec Niagara, sans le fondu bizarre.\n\nChoisis « Écran d'accueil » (ou les deux) quand Android te le demande.")
                .setPositiveButton("Activer", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { askSetWallpaper(HomeActivity.this); }
                })
                .setNegativeButton("Plus tard", null).show();
    }

    private void applyWallpaper() {
        if (sysWall) {
            // the system wallpaper layer plays the video; this window stays transparent
            releasePlayer();
            loaded = "";
            Drawable d = gifView.getDrawable();
            if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).stop();
            gifView.setImageDrawable(null);
            gifView.setVisibility(View.GONE);
            tex.setVisibility(View.INVISIBLE);
            return;
        }
        String path = P.active(this);
        String key = path + "|" + sp.getString("fit", "cover") + "|" + sp.getBoolean("sound", false) + "|" + sp.getFloat("speed", 1f);
        if (!key.equals(loaded)) {
            loaded = key;
            releasePlayer();
            gifView.setImageDrawable(null);
            if (path.isEmpty()) {
                tex.setVisibility(View.VISIBLE);
                gifView.setVisibility(View.GONE);
            } else if (P.isGif(path)) {
                tex.setVisibility(View.GONE);
                gifView.setVisibility(View.VISIBLE);
                gifView.setScaleType("contain".equals(sp.getString("fit", "cover")) ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
                try {
                    Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(new File(path)));
                    gifView.setImageDrawable(d);
                    if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                } catch (Exception e) {
                    Toast.makeText(this, "GIF illisible", Toast.LENGTH_SHORT).show();
                }
            } else {
                tex.setVisibility(View.VISIBLE);
                gifView.setVisibility(View.GONE);
                startVideo(path);
            }
        }
        if (resumed && !sp.getBoolean("paused", false) && !saverBlocks()) resumePlayback(); else pausePlayback();
    }

    private void startVideo(String path) {
        if (surface == null) { loaded = ""; return; }
        try {
            final MediaPlayer m = new MediaPlayer();
            mp = m;
            m.setSurface(surface);
            m.setDataSource(path);
            m.setLooping(true);
            boolean sound = sp.getBoolean("sound", false);
            m.setVolume(sound ? 1f : 0f, sound ? 1f : 0f);
            m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer p) {
                    if (p != mp) return;
                    vw = p.getVideoWidth();
                    vh = p.getVideoHeight();
                    fitVideo();
                    float speed = sp.getFloat("speed", 1f);
                    if (speed != 1f) {
                        try { p.setPlaybackParams(new PlaybackParams().setSpeed(speed)); } catch (Exception ignored) {}
                    }
                    if (savedPos > 0 && savedPosPath.equals(P.active(HomeActivity.this))) p.seekTo(savedPos);
                    if (resumed && !sp.getBoolean("paused", false) && !saverBlocks()) p.start();
                }
            });
            m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer p, int a, int b) { return true; }
            });
            m.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "Vidéo illisible", Toast.LENGTH_SHORT).show();
        }
    }

    private void fitVideo() {
        if (vw == 0 || vh == 0 || tex.getWidth() == 0) return;
        float W = tex.getWidth(), H = tex.getHeight();
        boolean cover = !"contain".equals(sp.getString("fit", "cover"));
        float s = cover ? Math.max(W / vw, H / vh) : Math.min(W / vw, H / vh);
        Matrix m = new Matrix();
        m.setScale(vw * s / W, vh * s / H, W / 2, H / 2);
        tex.setTransform(m);
    }

    private void pausePlayback() {
        try { if (mp != null && mp.isPlaying()) mp.pause(); } catch (Exception ignored) {}
        Drawable d = gifView.getDrawable();
        if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).stop();
    }

    private void resumePlayback() {
        try { if (mp != null && !mp.isPlaying()) mp.start(); } catch (Exception ignored) {}
        Drawable d = gifView.getDrawable();
        if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
    }

    private void releasePlayer() {
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        vw = vh = 0;
    }

    // ---------------------------------------------------------------- widgets

    private TextView text(float sizeSp, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sizeSp);
        t.setTypeface(tf);
        t.setShadowLayer(P.dp(this, 8), 0, P.dp(this, 1), 0x99000000);
        return t;
    }

    private void applyWidgets() {
        String align = sp.getString("wAlign", "left");
        int g = "center".equals(align) ? Gravity.CENTER_HORIZONTAL : "right".equals(align) ? Gravity.END : Gravity.START;
        for (TextView t : new TextView[]{clock, date, wx}) t.setGravity(g);
        widgetBox.setGravity(g);
        float scale = "s".equals(sp.getString("wSize", "m")) ? 0.75f : "l".equals(sp.getString("wSize", "m")) ? 1.3f : 1f;
        clock.setTextSize(64 * scale);
        date.setTextSize(17 * scale);
        wx.setTextSize(15 * scale);
        clock.setVisibility(sp.getBoolean("wClock", true) ? View.VISIBLE : View.GONE);
        date.setVisibility(sp.getBoolean("wDate", true) ? View.VISIBLE : View.GONE);
        boolean showWx = sp.getBoolean("wWeather", false) && sp.getLong("wxTime", 0) > 0;
        wx.setVisibility(showWx ? View.VISIBLE : View.GONE);
        if (showWx) {
            String[] w = P.weatherText(sp.getInt("wcode", 0), sp.getInt("isDay", 1) == 1);
            wx.setText(w[0] + "  " + Math.round(sp.getFloat("temp", 0)) + "°  " + w[1] + " · " + sp.getString("city", ""));
        }
        updateClock();
    }

    private void updateClock() {
        java.util.Date now = new java.util.Date();
        boolean sec = sp.getBoolean("wSec", false);
        clock.setText(new SimpleDateFormat(sec ? "HH:mm:ss" : "HH:mm", Locale.FRANCE).format(now));
        String d = new SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now);
        date.setText(d.substring(0, 1).toUpperCase(Locale.FRANCE) + d.substring(1));
    }

    private void maybeRefreshWeather() {
        if (!sp.getBoolean("wWeather", false) && !(sp.getBoolean("dnOn", false) && "sun".equals(sp.getString("dnMode", "fixed")))) return;
        if (sp.getString("city", "").isEmpty()) return;
        if (System.currentTimeMillis() - sp.getLong("wxTime", 0) < 30 * 60 * 1000) return;
        new Thread(new Runnable() {
            @Override public void run() {
                if (P.refreshWeather(HomeActivity.this)) {
                    h.post(new Runnable() { @Override public void run() { applyWidgets(); lastPeriod = ""; checkDayNight(); } });
                }
            }
        }).start();
    }

    private void startViz() {
        viz.color = sp.getInt("vizColor", P.ACCENT);
        viz.style = sp.getString("vizStyle", "bars");
        viz.sens = sp.getFloat("vizSens", 1f);
        if (sp.getBoolean("viz", false) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viz.setVisibility(View.VISIBLE);
            viz.start();
        } else {
            viz.stop();
            viz.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        new Thread(new Runnable() {
            @Override public void run() {
                PackageManager pm = getPackageManager();
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
                final List<App> out = new ArrayList<App>();
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo.packageName.equals(getPackageName())) continue;
                    App a = new App();
                    a.ri = ri;
                    a.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                    a.key = a.cn.flattenToString();
                    a.label = String.valueOf(ri.loadLabel(pm));
                    try { a.icon = ri.loadIcon(pm); } catch (Exception ignored) {}
                    out.add(a);
                }
                Collections.sort(out, new Comparator<App>() {
                    @Override public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
                });
                h.post(new Runnable() {
                    @Override public void run() {
                        apps.clear();
                        apps.addAll(out);
                        byKey.clear();
                        for (App a : apps) byKey.put(a.key, a);
                        if (!sp.getBoolean("dockInit", false)) initDock();
                        filter(search.getText().toString());
                        refreshDock();
                    }
                });
            }
        }).start();
    }

    private Drawable icon(App a) {
        if (a.icon == null) a.icon = a.ri.loadIcon(getPackageManager());
        return a.icon;
    }

    /** First launch: put phone, messages, browser and camera in the dock. */
    private void initDock() {
        List<String> dockList = new ArrayList<String>();
        Intent[] wanted = {
                new Intent(Intent.ACTION_DIAL),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)};
        for (Intent w : wanted) {
            try {
                ResolveInfo ri = getPackageManager().resolveActivity(w, PackageManager.MATCH_DEFAULT_ONLY);
                if (ri == null || ri.activityInfo == null) continue;
                Intent li = getPackageManager().getLaunchIntentForPackage(ri.activityInfo.packageName);
                if (li == null || li.getComponent() == null) continue;
                String k = li.getComponent().flattenToString();
                if (byKey.containsKey(k) && !dockList.contains(k)) dockList.add(k);
            } catch (Exception ignored) {}
        }
        P.saveList(this, "dock", dockList);
        sp.edit().putBoolean("dockInit", true).apply();
    }

    private View appTile(final App a, boolean label, int iconDp) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(P.dp(this, 4), P.dp(this, 6), P.dp(this, 4), P.dp(this, 6));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon(a));
        t.addView(iv, new LinearLayout.LayoutParams(P.dp(this, iconDp), P.dp(this, iconDp)));
        if (label) {
            TextView tv = new TextView(this);
            tv.setText(a.label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(11.5f);
            tv.setSingleLine(true);
            tv.setGravity(Gravity.CENTER);
            tv.setShadowLayer(P.dp(this, 4), 0, P.dp(this, 1), 0xAA000000);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = P.dp(this, 5);
            t.addView(tv, lp);
        }
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launch(a, v); }
        });
        t.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { appMenu(a, v); return true; }
        });
        return t;
    }

    private void launch(App a, View from) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(a.cn);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            android.app.ActivityOptions o = android.app.ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight());
            startActivity(i, o.toBundle());
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d'ouvrir " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- long-press menu

    private FrameLayout menuLayer;

    private boolean menuOpen() { return menuLayer != null; }

    private void appMenu(final App a, final View anchor) {
        if (menuOpen()) return;
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        final List<String> home = P.list(this, "home"), dk = P.list(this, "dock");

        // ----- layer: dim + blur of everything behind
        final FrameLayout layer = new FrameLayout(this);
        menuLayer = layer;
        final View scrim = new View(this);
        scrim.setBackgroundColor(0x99050612);
        layer.addView(scrim, mp());
        scrim.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closeMenu(); } });
        blur(drawer, 18);
        blur(homeContent, 18);

        // ----- floating copy of the icon, lifted above the blur
        int[] loc = new int[2], rootLoc = new int[2];
        View iconView = anchor instanceof LinearLayout ? ((LinearLayout) anchor).getChildAt(0) : anchor;
        iconView.getLocationOnScreen(loc);
        root.getLocationOnScreen(rootLoc);
        final int ix = loc[0] - rootLoc[0], iy = loc[1] - rootLoc[1], isz = iconView.getWidth();
        final ImageView lifted = new ImageView(this);
        lifted.setImageDrawable(icon(a));
        lifted.setElevation(P.dp(this, 16));
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(isz, isz);
        llp.leftMargin = ix;
        llp.topMargin = iy;
        layer.addView(lifted, llp);

        // ----- glass card
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(P.dp(this, 8), P.dp(this, 10), P.dp(this, 8), P.dp(this, 8));
        GradientDrawable cbg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF21C2147, 0xF2121633});
        cbg.setCornerRadius(P.dp(this, 26));
        cbg.setStroke(P.dp(this, 1), 0x33A5B0FF);
        card.setBackground(cbg);
        card.setElevation(P.dp(this, 24));
        card.setClipToOutline(true);

        TextView title = new TextView(this);
        title.setText(a.label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(P.dp(this, 14), P.dp(this, 4), P.dp(this, 14), P.dp(this, 8));
        card.addView(title);

        final List<View> rows = new ArrayList<View>();

        // app shortcuts (we are the default launcher, so Android shares them with us)
        try {
            final android.content.pm.LauncherApps la = (android.content.pm.LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
            if (la != null && la.hasShortcutHostPermission()) {
                android.content.pm.LauncherApps.ShortcutQuery q = new android.content.pm.LauncherApps.ShortcutQuery();
                q.setPackage(a.cn.getPackageName());
                q.setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                        | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                        | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                List<android.content.pm.ShortcutInfo> sc = la.getShortcuts(q, android.os.Process.myUserHandle());
                if (sc != null && !sc.isEmpty()) {
                    Collections.sort(sc, new Comparator<android.content.pm.ShortcutInfo>() {
                        @Override public int compare(android.content.pm.ShortcutInfo x, android.content.pm.ShortcutInfo y) { return x.getRank() - y.getRank(); }
                    });
                    int n = 0;
                    for (final android.content.pm.ShortcutInfo si : sc) {
                        if (!si.isEnabled() || n >= 4) continue;
                        n++;
                        Drawable d = null;
                        try { d = la.getShortcutIconDrawable(si, getResources().getDisplayMetrics().densityDpi); } catch (Exception ignored) {}
                        CharSequence lbl = si.getShortLabel() != null ? si.getShortLabel() : si.getLongLabel();
                        rows.add(menuRow(card, d != null ? d : "↗", String.valueOf(lbl), Color.WHITE, new Runnable() {
                            @Override public void run() {
                                try { la.startShortcut(si, null, null); } catch (Exception e) { launch(a, anchor); }
                                closeMenu();
                                if (drawerProgress > 0) closeDrawer(false);
                            }
                        }));
                    }
                    if (n > 0) {
                        View sep = new View(this);
                        sep.setBackgroundColor(0x22FFFFFF);
                        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, P.dp(this, 1));
                        sl.setMargins(P.dp(this, 12), P.dp(this, 6), P.dp(this, 12), P.dp(this, 6));
                        card.addView(sep, sl);
                        rows.add(sep);
                    }
                }
            }
        } catch (Throwable ignored) {}

        final boolean onHome = home.contains(a.key), onDock = dk.contains(a.key);
        rows.add(menuRow(card, "📌", onHome ? "Retirer de l'accueil" : "Épingler à l'accueil", Color.WHITE, new Runnable() {
            @Override public void run() {
                if (onHome) home.remove(a.key);
                else if (home.size() >= 16) { Toast.makeText(HomeActivity.this, "Accueil plein (16 apps max)", Toast.LENGTH_SHORT).show(); closeMenu(); return; }
                else home.add(a.key);
                P.saveList(HomeActivity.this, "home", home);
                refreshDock();
                closeMenu();
            }
        }));
        rows.add(menuRow(card, "⭐", onDock ? "Retirer du dock" : "Ajouter au dock", Color.WHITE, new Runnable() {
            @Override public void run() {
                if (onDock) dk.remove(a.key);
                else if (dk.size() >= 5) { Toast.makeText(HomeActivity.this, "Dock plein (5 apps max)", Toast.LENGTH_SHORT).show(); closeMenu(); return; }
                else dk.add(a.key);
                P.saveList(HomeActivity.this, "dock", dk);
                refreshDock();
                closeMenu();
            }
        }));
        rows.add(menuRow(card, "ℹ️", "Infos de l'app", Color.WHITE, new Runnable() {
            @Override public void run() {
                closeMenu();
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.cn.getPackageName())));
            }
        }));
        rows.add(menuRow(card, "🗑️", "Désinstaller", 0xFFFF7A93, new Runnable() {
            @Override public void run() {
                closeMenu();
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.cn.getPackageName())));
            }
        }));

        // ----- place the card next to the icon (below if there's room, else above)
        int W = root.getWidth(), H = root.getHeight();
        int cw = Math.min(W - P.dp(this, 32), P.dp(this, 270));
        card.measure(View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.AT_MOST));
        int ch = card.getMeasuredHeight();
        int cx = Math.max(P.dp(this, 16), Math.min(W - cw - P.dp(this, 16), ix + isz / 2 - cw / 2));
        boolean below = iy + isz + P.dp(this, 14) + ch < H - P.dp(this, 24);
        int cy = below ? iy + isz + P.dp(this, 14) : Math.max(P.dp(this, 40), iy - P.dp(this, 14) - ch);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(cw, -2);
        clp.leftMargin = cx;
        clp.topMargin = cy;
        layer.addView(card, clp);
        root.addView(layer, mp());

        // ----- animations
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180).start();

        lifted.setScaleX(1f);
        lifted.setScaleY(1f);
        lifted.animate().scaleX(1.18f).scaleY(1.18f).translationY(-P.dp(this, 4))
                .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).setDuration(260).start();

        card.setPivotX(ix + isz / 2f - cx);
        card.setPivotY(below ? 0 : ch);
        card.setScaleX(0.6f);
        card.setScaleY(0.4f);
        card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).setDuration(300).start();

        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setAlpha(0f);
            r.setTranslationX(-P.dp(this, 18));
            r.animate().alpha(1f).translationX(0).setStartDelay(70 + i * 35L).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        }
        layer.setTag(new View[]{scrim, lifted, card});
    }

    private View menuRow(LinearLayout card, Object icon, String text, int color, final Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(P.dp(this, 12), P.dp(this, 11), P.dp(this, 12), P.dp(this, 11));
        android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0x267C8CFF);
        pressed.setCornerRadius(P.dp(this, 16));
        st.addState(new int[]{android.R.attr.state_pressed}, pressed);
        st.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        r.setBackground(st);
        View iv;
        if (icon instanceof Drawable) {
            ImageView im = new ImageView(this);
            im.setImageDrawable((Drawable) icon);
            iv = im;
        } else {
            TextView t = new TextView(this);
            t.setText(String.valueOf(icon));
            t.setTextSize(16);
            t.setGravity(Gravity.CENTER);
            iv = t;
        }
        r.addView(iv, new LinearLayout.LayoutParams(P.dp(this, 26), P.dp(this, 26)));
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(15);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setPadding(P.dp(this, 14), 0, 0, 0);
        r.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
        r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } });
        card.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private void closeMenu() {
        final FrameLayout layer = menuLayer;
        if (layer == null) return;
        menuLayer = null;
        blur(drawer, 0);
        blur(homeContent, 0);
        View[] v = (View[]) layer.getTag();
        v[0].animate().alpha(0f).setDuration(150).start();
        v[1].animate().scaleX(1f).scaleY(1f).translationY(0).setDuration(150).start();
        v[2].animate().scaleX(0.85f).scaleY(0.7f).alpha(0f).setDuration(140)
                .setInterpolator(new android.view.animation.AccelerateInterpolator()).withEndAction(new Runnable() {
                    @Override public void run() { root.removeView(layer); }
                }).start();
    }

    /** Android 12+ blur through reflection (we compile against an older SDK). radius 0 = remove. */
    private static void blur(View v, float radius) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            Class<?> re = Class.forName("android.graphics.RenderEffect");
            Object eff = null;
            if (radius > 0) eff = re.getMethod("createBlurEffect", float.class, float.class, android.graphics.Shader.TileMode.class)
                    .invoke(null, radius, radius, android.graphics.Shader.TileMode.CLAMP);
            View.class.getMethod("setRenderEffect", re).invoke(v, eff);
        } catch (Throwable ignored) {}
    }

    private void refreshDock() {
        dock.removeAllViews();
        for (String k : P.list(this, "dock")) {
            App a = byKey.get(k);
            if (a == null) continue;
            dock.addView(appTile(a, false, 50), new LinearLayout.LayoutParams(0, -2, 1));
        }
        // app drawer button
        TextView all = new TextView(this);
        all.setText("⋮⋮");
        all.setTextColor(Color.WHITE);
        all.setTextSize(20);
        all.setGravity(Gravity.CENTER);
        GradientDrawable c = new GradientDrawable();
        c.setShape(GradientDrawable.OVAL);
        c.setColor(0x33FFFFFF);
        all.setBackground(c);
        all.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDrawer(); }
        });
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.CENTER);
        wrap.addView(all, new LinearLayout.LayoutParams(P.dp(this, 50), P.dp(this, 50)));
        dock.addView(wrap, new LinearLayout.LayoutParams(0, -2, 1));

        homeGrid.removeAllViews();
        int w = getResources().getDisplayMetrics().widthPixels - P.dp(this, 20);
        for (String k : P.list(this, "home")) {
            App a = byKey.get(k);
            if (a == null) continue;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w / 4;
            homeGrid.addView(appTile(a, true, 54), lp);
        }
    }

    // ---------------------------------------------------------------- drawer

    private void buildDrawer() {
        drawer = new FrameLayout(this) {
            float downX, downY;
            boolean dragging;
            android.view.VelocityTracker vt;
            final int slop = android.view.ViewConfiguration.get(HomeActivity.this).getScaledTouchSlop();
            boolean atTop() {
                View list = drawerGrid.getVisibility() == View.VISIBLE ? drawerGrid : resultsScroll;
                return !list.canScrollVertically(-1);
            }
            @Override public boolean onInterceptTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY(); dragging = false;
                        if (vt != null) vt.recycle();
                        vt = android.view.VelocityTracker.obtain();
                        vt.addMovement(e);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (vt != null) vt.addMovement(e);
                        float dy = e.getRawY() - downY, dx = e.getRawX() - downX;
                        if (dy > slop && dy > Math.abs(dx) && atTop()) {
                            dragging = true;
                            drawerAnim(false);
                            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                            if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
                            return true;
                        }
                        break;
                }
                return false;
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                if (!dragging) return true;
                if (vt != null) vt.addMovement(e);
                float dy = e.getRawY() - downY;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        setDrawerProgress(Math.max(0, Math.min(1, 1 - (dy - slop) / (getHeight() * 0.6f))));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float vy = 0;
                        if (vt != null) { vt.computeCurrentVelocity(1000); vy = vt.getYVelocity(); vt.recycle(); vt = null; }
                        dragging = false;
                        if (vy > 700 || (vy > -700 && drawerProgress < 0.7f)) closeDrawer(); else openDrawer();
                        return true;
                }
                return true;
            }
        };
        drawer.setBackgroundColor(0xE60B0D1A);
        drawer.setVisibility(View.GONE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        drawer.addView(col, mp());

        search = new EditText(this);
        search.setHint("🔍  Apps, contacts, calculs, web…");
        search.setHintTextColor(P.MUTED);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setPadding(P.dp(this, 18), P.dp(this, 12), P.dp(this, 18), P.dp(this, 12));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0x22FFFFFF);
        sb.setCornerRadius(P.dp(this, 24));
        search.setBackground(sb);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(P.dp(this, 16), P.dp(this, 8), P.dp(this, 16), P.dp(this, 8));
        col.addView(search, slp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable e) {}
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, android.view.KeyEvent e) {
                if (firstAction != null) firstAction.run();
                return true;
            }
        });

        drawerGrid = new GridView(this);
        drawerGrid.setNumColumns(4);
        drawerGrid.setVerticalSpacing(P.dp(this, 10));
        drawerGrid.setPadding(P.dp(this, 8), P.dp(this, 4), P.dp(this, 8), P.dp(this, 20));
        drawerGrid.setClipToPadding(false);
        drawerGrid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        adapter = new DrawerAdapter();
        drawerGrid.setAdapter(adapter);
        col.addView(drawerGrid, new LinearLayout.LayoutParams(-1, 0, 1));

        resultsScroll = new android.widget.ScrollView(this);
        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        resultsBox.setPadding(P.dp(this, 12), 0, P.dp(this, 12), P.dp(this, 30));
        resultsScroll.addView(resultsBox);
        resultsScroll.setVisibility(View.GONE);
        col.addView(resultsScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(drawer, mp());
    }

    private void filter(String q) {
        filtered.clear();
        filtered.addAll(apps);
        if (adapter != null) adapter.notifyDataSetChanged();
        boolean searching = !q.trim().isEmpty();
        if (drawerGrid != null) {
            drawerGrid.setVisibility(searching ? View.GONE : View.VISIBLE);
            resultsScroll.setVisibility(searching ? View.VISIBLE : View.GONE);
        }
        if (searching) buildResults(q.trim());
        else firstAction = null;
    }

    // ---------------------------------------------------------------- universal search

    private android.widget.ScrollView resultsScroll;
    private LinearLayout resultsBox;
    private Runnable firstAction;
    private int searchGen;

    private void section(String t) {
        TextView v = new TextView(this);
        v.setText(t.toUpperCase(Locale.FRANCE));
        v.setTextColor(P.MUTED);
        v.setTextSize(11.5f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setLetterSpacing(0.08f);
        v.setPadding(P.dp(this, 8), P.dp(this, 16), 0, P.dp(this, 6));
        resultsBox.addView(v);
    }

    /** One result row. icon: Drawable or emoji String. extra: pairs of (emoji, action) shown as round buttons. */
    private LinearLayout row(Object icon, String title, String sub, final Runnable action, Object... extra) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(P.dp(this, 10), P.dp(this, 9), P.dp(this, 10), P.dp(this, 9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(P.dp(this, 16));
        r.setBackground(bg);
        View iv;
        if (icon instanceof Drawable) {
            ImageView im = new ImageView(this);
            im.setImageDrawable((Drawable) icon);
            iv = im;
        } else {
            TextView t = new TextView(this);
            t.setText(String.valueOf(icon));
            t.setTextSize(20);
            t.setGravity(Gravity.CENTER);
            GradientDrawable c = new GradientDrawable();
            c.setShape(GradientDrawable.OVAL);
            c.setColor(0x22FFFFFF);
            t.setBackground(c);
            iv = t;
        }
        r.addView(iv, new LinearLayout.LayoutParams(P.dp(this, 40), P.dp(this, 40)));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(P.dp(this, 12), 0, P.dp(this, 6), 0);
        TextView tt = new TextView(this);
        tt.setText(title);
        tt.setTextColor(Color.WHITE);
        tt.setTextSize(15);
        tt.setSingleLine(true);
        tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(tt);
        if (sub != null && !sub.isEmpty()) {
            TextView st = new TextView(this);
            st.setText(sub);
            st.setTextColor(P.MUTED);
            st.setTextSize(12);
            st.setSingleLine(true);
            st.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(st);
        }
        r.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        for (int i = 0; i + 1 < extra.length; i += 2) {
            final Runnable a = (Runnable) extra[i + 1];
            TextView b = new TextView(this);
            b.setText(String.valueOf(extra[i]));
            b.setTextSize(17);
            b.setGravity(Gravity.CENTER);
            GradientDrawable c = new GradientDrawable();
            c.setShape(GradientDrawable.OVAL);
            c.setColor(0x337C8CFF);
            b.setBackground(c);
            b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { a.run(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(P.dp(this, 40), P.dp(this, 40));
            lp.leftMargin = P.dp(this, 8);
            r.addView(b, lp);
        }
        r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = P.dp(this, 6);
        resultsBox.addView(r, lp);
        if (firstAction == null) firstAction = action;
        return r;
    }

    private void open(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            closeDrawer();
        } catch (Exception e) {
            Toast.makeText(this, "Aucune app pour ouvrir ça", Toast.LENGTH_SHORT).show();
        }
    }

    private void buildResults(final String q) {
        final int gen = ++searchGen;
        resultsBox.removeAllViews();
        firstAction = null;

        // calculator
        final Double v = Search.calc(q);
        if (v != null) {
            final String res = Search.fmt(v);
            section("Calcul");
            row("🧮", "= " + res, q + "  ·  toucher pour copier", new Runnable() {
                @Override public void run() {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("calcul", res));
                    Toast.makeText(HomeActivity.this, "Copié : " + res, Toast.LENGTH_SHORT).show();
                }
            });
        }

        // apps
        List<App> hits = new ArrayList<App>();
        final Map<App, Integer> score = new HashMap<App, Integer>();
        for (App a : apps) {
            int sc = Search.appScore(a.label, q);
            if (sc > 0) { hits.add(a); score.put(a, sc); }
        }
        Collections.sort(hits, new Comparator<App>() {
            @Override public int compare(App x, App y) { return score.get(y) - score.get(x); }
        });
        if (!hits.isEmpty()) {
            section("Applications");
            for (int i = 0; i < Math.min(6, hits.size()); i++) {
                final App a = hits.get(i);
                row(icon(a), a.label, null, new Runnable() {
                    @Override public void run() { launch(a, resultsBox); closeDrawer(); }
                });
            }
        }

        // web address
        if (Search.looksLikeUrl(q)) {
            section("Site web");
            final String url = q.matches("(?i)^https?://.*") ? q : "https://" + q;
            row("🌐", "Ouvrir " + q, url, new Runnable() {
                @Override public void run() { open(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            });
        }

        // contacts (async)
        final LinearLayout contactsSlot = new LinearLayout(this);
        contactsSlot.setOrientation(LinearLayout.VERTICAL);
        resultsBox.addView(contactsSlot);
        if (q.length() >= 2 && Search.calc(q) == null) {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        final List<String[]> found = queryContacts(q);
                        h.post(new Runnable() {
                            @Override public void run() {
                                if (gen != searchGen || found.isEmpty()) return;
                                LinearLayout keep = resultsBox;
                                resultsBox = contactsSlot;
                                Runnable fa = firstAction;
                                section("Contacts");
                                for (final String[] c : found) {
                                    row("👤", c[0], c[1], new Runnable() {
                                        @Override public void run() {
                                            open(new Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.getLookupUri(Long.parseLong(c[2]), c[3])));
                                        }
                                    }, "📞", new Runnable() {
                                        @Override public void run() { open(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c[1])))); }
                                    }, "💬", new Runnable() {
                                        @Override public void run() { open(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c[1])))); }
                                    });
                                }
                                resultsBox = keep;
                                if (fa != null) firstAction = fa;
                            }
                        });
                    }
                }).start();
            } else if (!sp.getBoolean("contactsAsked", false)) {
                LinearLayout keep = resultsBox;
                resultsBox = contactsSlot;
                Runnable fa = firstAction;
                section("Contacts");
                row("👥", "Chercher aussi dans tes contacts", "Toucher pour autoriser", new Runnable() {
                    @Override public void run() {
                        sp.edit().putBoolean("contactsAsked", true).apply();
                        requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 21);
                    }
                });
                resultsBox = keep;
                firstAction = fa;
            }
        }

        // phone number typed directly
        if (q.replaceAll("[\\s.()-]", "").matches("^\\+?\\d{4,15}$")) {
            section("Téléphone");
            row("📞", "Appeler " + q, null, new Runnable() {
                @Override public void run() { open(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(q)))); }
            });
        }

        // settings
        List<String[]> st = new ArrayList<String[]>();
        for (String[] e : Search.SETTINGS) if (Search.settingScore(e, q) > 0) st.add(e);
        boolean vw = Search.norm("vidwall fond d'ecran wallpaper video reglages launcher").contains(Search.norm(q)) && q.length() >= 3;
        if (!st.isEmpty() || vw) {
            section("Paramètres");
            if (vw) row("🎬", "Réglages VidWall Home", "Fond d'écran, jour/nuit, widgets…", new Runnable() {
                @Override public void run() { startActivity(new Intent(HomeActivity.this, SettingsActivity.class)); closeDrawer(); }
            });
            for (int i = 0; i < Math.min(4, st.size()); i++) {
                final String[] e = st.get(i);
                row(e[0], e[1], "Paramètres Android", new Runnable() {
                    @Override public void run() { open(new Intent(e[3])); }
                });
            }
        }

        // web
        section("Rechercher sur le web");
        final String enc = Uri.encode(q);
        row("🔎", "Google : « " + q + " »", null, new Runnable() {
            @Override public void run() {
                Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
                i.putExtra(android.app.SearchManager.QUERY, q);
                try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); closeDrawer(); }
                catch (Exception e) { open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + enc))); }
            }
        });
        row("▶️", "YouTube : « " + q + " »", null, new Runnable() {
            @Override public void run() { open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc))); }
        });
        row("🛍️", "Play Store : « " + q + " »", null, new Runnable() {
            @Override public void run() { open(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + enc))); }
        });
        row("🗺️", "Maps : « " + q + " »", null, new Runnable() {
            @Override public void run() { open(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + enc))); }
        });
    }

    private List<String[]> queryContacts(String q) {
        List<String[]> out = new ArrayList<String[]>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        android.database.Cursor c = null;
        try {
            Uri u = Uri.withAppendedPath(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q));
            c = getContentResolver().query(u, new String[]{
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    android.provider.ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY}, null, null, null);
            while (c != null && c.moveToNext() && out.size() < 5) {
                String id = c.getString(2);
                if (seen.contains(id)) continue;
                seen.add(id);
                out.add(new String[]{c.getString(0), c.getString(1), id, c.getString(3)});
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == 21) filter(search.getText().toString());
    }

    private void openSearch() {
        openDrawer();
        search.requestFocus();
        h.postDelayed(new Runnable() {
            @Override public void run() {
                InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120);
    }

    /** 0 = closed (home visible), 1 = drawer fully open. Follows the finger while dragging. */
    private void setDrawerProgress(float p) {
        drawerProgress = p;
        float H = Math.max(1, root.getHeight());
        drawer.setVisibility(p > 0 ? View.VISIBLE : View.GONE);
        drawer.setTranslationY((1 - p) * H * 0.35f);
        drawer.setAlpha(Math.min(1f, p * 1.4f));
        homeContent.setAlpha(1f - Math.min(1f, p * 1.6f));
        homeContent.setTranslationY(-p * P.dp(this, 40));
    }

    private void drawerAnim(boolean keep) {
        if (drawerAnimator != null) { drawerAnimator.cancel(); drawerAnimator = null; }
    }

    private void animateDrawerTo(final float target, long dur, final Runnable end) {
        drawerAnim(false);
        drawerAnimator = android.animation.ValueAnimator.ofFloat(drawerProgress, target);
        drawerAnimator.setDuration(dur);
        drawerAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
        drawerAnimator.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) { setDrawerProgress((Float) a.getAnimatedValue()); }
        });
        if (end != null) drawerAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!cancelled) end.run(); }
        });
        drawerAnimator.start();
    }

    private void openDrawer() {
        if (drawerProgress == 0) drawerGrid.setSelection(0);
        drawer.setVisibility(View.VISIBLE);
        animateDrawerTo(1f, (long) (120 + 140 * (1 - drawerProgress)), null);
    }

    private void closeDrawer() { closeDrawer(true); }

    private void closeDrawer(boolean animate) {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        Runnable reset = new Runnable() {
            @Override public void run() { search.setText(""); }
        };
        if (!animate) { drawerAnim(false); setDrawerProgress(0); reset.run(); return; }
        animateDrawerTo(0f, (long) (100 + 140 * drawerProgress), reset);
    }

    class DrawerAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public Object getItem(int i) { return filtered.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View cv, ViewGroup parent) {
            final App a = filtered.get(i);
            if (cv == null) cv = appTile(a, true, 52);
            LinearLayout t = (LinearLayout) cv;
            ((ImageView) t.getChildAt(0)).setImageDrawable(icon(a));
            ((TextView) t.getChildAt(1)).setText(a.label);
            t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { launch(a, v); } });
            t.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { appMenu(a, v); return true; } });
            return t;
        }
    }

    // ---------------------------------------------------------------- misc

    private void expandNotifications() {
        try {
            Object sbs = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbs);
        } catch (Throwable ignored) {}
    }

    private static FrameLayout.LayoutParams mp() {
        return new FrameLayout.LayoutParams(-1, -1);
    }
}
