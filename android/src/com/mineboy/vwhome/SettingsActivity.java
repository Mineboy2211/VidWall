package com.mineboy.vwhome;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {
    private static final int PICK = 7, MIC = 8;
    private SharedPreferences sp;
    private LinearLayout root;
    private final Handler h = new Handler(Looper.getMainLooper());
    private GridLayout libGrid;
    private TextView cityInfo, dnInfo, dayPick, nightPick, dayTime, nightTime;
    private LinearLayout dnFixedRow;
    private Switch vizSwitch;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = P.sp(this);
        getWindow().setStatusBarColor(P.BG);
        getWindow().setNavigationBarColor(P.BG);
        ScrollView sv = new ScrollView(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF141A40, P.BG, 0xFF0F1E2E});
        sv.setBackground(bg);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(40));
        sv.addView(root);
        setContentView(sv);
        build();
    }

    private int dp(float v) { return P.dp(this, v); }

    // ---------------------------------------------------------------- UI helpers

    private TextView title(String t, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private LinearLayout card(String heading, String sub) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x10FFFFFF);
        d.setCornerRadius(dp(18));
        d.setStroke(dp(1), 0x18FFFFFF);
        c.setBackground(d);
        c.addView(title(heading, 16, Color.WHITE, true));
        if (sub != null) {
            TextView s = title(sub, 12.5f, P.MUTED, false);
            s.setPadding(0, dp(3), 0, dp(4));
            c.addView(s);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        root.addView(c, lp);
        return c;
    }

    private Switch toggle(LinearLayout parent, String label, String desc, final String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(title(label, 15, Color.WHITE, false));
        if (desc != null) txt.addView(title(desc, 12, P.MUTED, false));
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        Switch s = new Switch(this);
        s.setChecked(sp.getBoolean(key, def));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton c, boolean on) { sp.edit().putBoolean(key, on).apply(); }
        });
        row.addView(s);
        parent.addView(row);
        return s;
    }

    interface OnPick { void pick(String v); }

    /** segmented control */
    private LinearLayout seg(LinearLayout parent, final String[] labels, final String[] values, String current, final OnPick cb) {
        final LinearLayout s = new LinearLayout(this);
        s.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x40000000);
        d.setCornerRadius(dp(14));
        s.setBackground(d);
        final List<TextView> btns = new ArrayList<TextView>();
        for (int i = 0; i < labels.length; i++) {
            final TextView t = title(labels[i], 13, P.MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            final String v = values[i];
            t.setTag(v);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    for (TextView o : btns) styleSeg(o, o == t);
                    cb.pick(v);
                }
            });
            btns.add(t);
            s.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            styleSeg(t, v.equals(current));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        parent.addView(s, lp);
        return s;
    }

    private void styleSeg(TextView t, boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10));
        d.setColor(on ? P.ACCENT : Color.TRANSPARENT);
        t.setBackground(d);
        t.setTextColor(on ? P.BG : P.MUTED);
    }

    private TextView button(LinearLayout parent, String text, boolean primary, View.OnClickListener l) {
        TextView t = title(text, 14.5f, primary ? P.BG : Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(13), dp(14), dp(13));
        GradientDrawable d = primary ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{P.ACCENT, P.ACCENT2}) : new GradientDrawable();
        if (!primary) {
            d.setColor(0x14FFFFFF);
            d.setStroke(dp(1), 0x22FFFFFF);
        }
        d.setCornerRadius(dp(14));
        t.setBackground(d);
        t.setOnClickListener(l);
        if (parent != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            parent.addView(t, lp);
        }
        return t;
    }

    // ---------------------------------------------------------------- screen

    private void build() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.addView(title("VidWall Home", 28, Color.WHITE, true));
        head.addView(title("Ton écran d'accueil avec ta vidéo en fond", 13.5f, P.MUTED, false));
        root.addView(head);
        button(root, HomeActivity.isOurWallpaper(this) ? "✓  Fond d'écran animé actif" : "✨  Activer le fond d'écran animé (transitions fluides)", true, new View.OnClickListener() {
            @Override public void onClick(View v) { HomeActivity.askSetWallpaper(SettingsActivity.this); }
        });
        button(root, "🏠  Définir comme écran d'accueil", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            }
        });

        // ---------- library
        LinearLayout lib = card("🎬 Bibliothèque", "Touche une vidéo ou un GIF pour l'utiliser · appui long pour supprimer");
        libGrid = new GridLayout(this);
        libGrid.setColumnCount(3);
        lib.addView(libGrid);
        button(lib, "＋  Ajouter des vidéos ou des GIF", true, new View.OnClickListener() {
            @Override public void onClick(View v) { pickFiles(); }
        });
        refreshLibrary();

        // ---------- display
        LinearLayout disp = card("🖼️ Affichage", null);
        seg(disp, new String[]{"Remplir l'écran", "Ajuster"}, new String[]{"cover", "contain"}, sp.getString("fit", "cover"), new OnPick() {
            @Override public void pick(String v) { sp.edit().putString("fit", v).apply(); }
        });
        TextView spl = title("Vitesse", 13, P.MUTED, false);
        spl.setPadding(0, dp(10), 0, 0);
        disp.addView(spl);
        seg(disp, new String[]{"x0.5", "x1", "x1.5", "x2"}, new String[]{"0.5", "1.0", "1.5", "2.0"}, String.valueOf(sp.getFloat("speed", 1f)), new OnPick() {
            @Override public void pick(String v) { sp.edit().putFloat("speed", Float.parseFloat(v)).apply(); }
        });
        toggle(disp, "Son", "Coupé par défaut (pas de son pour les GIF)", "sound", false);
        toggle(disp, "Économie de batterie", "Pas d'animation quand le mode économie d'énergie est activé", "saver", true);
        toggle(disp, "Barre de recherche sur l'accueil", "Apps, contacts, calculs, paramètres, web", "homeSearch", true);
        toggle(disp, "Dock en bas", "Désactivé : glisse vers le haut pour voir tes apps", "showDock", false);
        TextView tip = title("Astuce : touche deux fois l'écran d'accueil pour mettre la vidéo en pause.", 12, P.MUTED, false);
        tip.setPadding(0, dp(4), 0, 0);
        disp.addView(tip);

        // ---------- day / night
        LinearLayout dn = card("🌗 Jour / Nuit", "Une vidéo le jour, une autre la nuit");
        toggle(dn, "Activer le cycle jour / nuit", null, "dnOn", false);
        seg(dn, new String[]{"🕖 Heures fixes", "🌅 Soleil"}, new String[]{"fixed", "sun"}, sp.getString("dnMode", "fixed"), new OnPick() {
            @Override public void pick(String v) { sp.edit().putString("dnMode", v).apply(); updateDN(); }
        });
        dnFixedRow = new LinearLayout(this);
        dayTime = button(null, "", false, new View.OnClickListener() {
            @Override public void onClick(View v) { pickTime("dayStart", "07:00"); }
        });
        nightTime = button(null, "", false, new View.OnClickListener() {
            @Override public void onClick(View v) { pickTime("nightStart", "19:30"); }
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
        half.setMargins(0, dp(8), dp(5), 0);
        dnFixedRow.addView(dayTime, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -2, 1);
        half2.setMargins(dp(5), dp(8), 0, 0);
        dnFixedRow.addView(nightTime, half2);
        dn.addView(dnFixedRow);
        dnInfo = title("", 12.5f, P.MUTED, false);
        dnInfo.setPadding(0, dp(8), 0, 0);
        dn.addView(dnInfo);
        dayPick = button(dn, "", false, new View.OnClickListener() {
            @Override public void onClick(View v) { chooseFromLibrary("dayVideo", "☀️ Vidéo du jour"); }
        });
        nightPick = button(dn, "", false, new View.OnClickListener() {
            @Override public void onClick(View v) { chooseFromLibrary("nightVideo", "🌙 Vidéo de la nuit"); }
        });
        updateDN();

        // ---------- widgets
        LinearLayout w = card("🕒 Widgets", "Affichés en haut de ton écran d'accueil");
        toggle(w, "Horloge", null, "wClock", true);
        toggle(w, "Secondes", null, "wSec", false);
        toggle(w, "Date", null, "wDate", true);
        toggle(w, "Météo", null, "wWeather", false);
        LinearLayout cityRow = new LinearLayout(this);
        cityRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText city = new EditText(this);
        city.setHint("Ta ville (ex : Mirabel)");
        city.setText(sp.getString("city", ""));
        city.setTextColor(Color.WHITE);
        city.setHintTextColor(P.MUTED);
        city.setSingleLine(true);
        city.setImeOptions(EditorInfo.IME_ACTION_DONE);
        cityRow.addView(city, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ok = button(null, "OK", true, new View.OnClickListener() {
            @Override public void onClick(View v) { saveCity(city.getText().toString()); }
        });
        LinearLayout.LayoutParams okp = new LinearLayout.LayoutParams(dp(70), -2);
        okp.leftMargin = dp(8);
        cityRow.addView(ok, okp);
        w.addView(cityRow);
        cityInfo = title(weatherInfo(), 12.5f, P.MUTED, false);
        w.addView(cityInfo);
        TextView al = title("Alignement", 13, P.MUTED, false);
        al.setPadding(0, dp(10), 0, 0);
        w.addView(al);
        seg(w, new String[]{"Gauche", "Centre", "Droite"}, new String[]{"left", "center", "right"}, sp.getString("wAlign", "left"), new OnPick() {
            @Override public void pick(String v) { sp.edit().putString("wAlign", v).apply(); }
        });
        TextView sz = title("Taille", 13, P.MUTED, false);
        sz.setPadding(0, dp(6), 0, 0);
        w.addView(sz);
        seg(w, new String[]{"S", "M", "L"}, new String[]{"s", "m", "l"}, sp.getString("wSize", "m"), new OnPick() {
            @Override public void pick(String v) { sp.edit().putString("wSize", v).apply(); }
        });

        // ---------- visualizer
        LinearLayout vz = card("🎵 Visualiseur audio", "Réagit à la musique qui joue sur ton téléphone");
        vizSwitch = toggle(vz, "Activer", "Demande l'autorisation micro (rien n'est enregistré)", "viz", false);
        vizSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton c, boolean on) {
                sp.edit().putBoolean("viz", on).apply();
                if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC);
            }
        });
        String curViz = sp.getString("vizStyle", "spectre");
        if ("bars".equals(curViz)) curViz = "spectre"; else if ("wave".equals(curViz)) curViz = "aurore"; else if ("circle".equals(curViz)) curViz = "anneau";
        seg(vz, new String[]{"Spectre", "Aurore", "Anneau", "Étoiles", "Lueur"}, new String[]{"spectre", "aurore", "anneau", "particules", "lueur"}, curViz, new OnPick() {
            @Override public void pick(String v) { sp.edit().putString("vizStyle", v).apply(); }
        });
        TextView cl = title("Couleur", 13, P.MUTED, false);
        cl.setPadding(0, dp(10), 0, dp(6));
        vz.addView(cl);
        final LinearLayout sw = new LinearLayout(this);
        vz.addView(sw);
        drawSwatches(sw);
        TextView se = title("Sensibilité", 13, P.MUTED, false);
        se.setPadding(0, dp(12), 0, 0);
        vz.addView(se);
        SeekBar sb = new SeekBar(this);
        sb.setMax(20);
        sb.setProgress(Math.round((sp.getFloat("vizSens", 1f) - 0.4f) * 10));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { sp.edit().putFloat("vizSens", 0.4f + p / 10f).apply(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        vz.addView(sb);

        // ---------- updates
        LinearLayout up = card("🔄 Mises à jour", "Version installée : " + Updater.current(this));
        toggle(up, "Vérifier automatiquement", "Au plus deux fois par jour", "autoUpdate", true);
        final TextView upInfo = title("", 12.5f, P.MUTED, false);
        up.addView(upInfo);
        button(up, "Vérifier maintenant", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                upInfo.setText("Vérification…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        final String[] u = Updater.check(SettingsActivity.this);
                        h.post(new Runnable() {
                            @Override public void run() {
                                if (u == null) upInfo.setText("✓ Tu as la dernière version");
                                else if (u[0].isEmpty()) upInfo.setText(u[2]);
                                else { upInfo.setText("Version " + u[0] + " disponible"); Updater.offer(SettingsActivity.this, u); }
                            }
                        });
                    }
                }).start();
            }
        });

        TextView foot = title("Appui long sur l'écran d'accueil → ces réglages · glisse vers le haut → toutes tes apps · appui long sur une app → l'épingler", 12, P.MUTED, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(10), dp(22), dp(10), 0);
        root.addView(foot);
    }

    private void drawSwatches(final LinearLayout sw) {
        sw.removeAllViews();
        int cur = sp.getInt("vizColor", P.ACCENT);
        for (final int c : P.COLORS) {
            View v = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(c);
            if (c == cur) d.setStroke(dp(3), Color.WHITE);
            v.setBackground(d);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { sp.edit().putInt("vizColor", c).apply(); drawSwatches(sw); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.rightMargin = dp(10);
            sw.addView(v, lp);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == MIC && (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED)) {
            sp.edit().putBoolean("viz", false).apply();
            vizSwitch.setChecked(false);
            Toast.makeText(this, "Sans cette autorisation, le visualiseur ne peut pas entendre la musique", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- library

    private void refreshLibrary() {
        libGrid.removeAllViews();
        final List<String> lib = P.library(this);
        final String active = P.active(this);
        int w = (getResources().getDisplayMetrics().widthPixels - dp(36 + 32)) / 3;
        for (final String p : lib) {
            FrameLayout tile = new FrameLayout(this);
            GradientDrawable d = new GradientDrawable();
            d.setColor(0xFF05060F);
            d.setCornerRadius(dp(12));
            if (p.equals(active)) d.setStroke(dp(3), P.ACCENT);
            tile.setBackground(d);
            tile.setPadding(dp(3), dp(3), dp(3), dp(3));
            tile.setClipToOutline(true);
            final ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            TextView lbl = title((P.isGif(p) ? "GIF · " : "") + P.displayName(p), 10.5f, Color.WHITE, true);
            lbl.setSingleLine(true);
            lbl.setPadding(dp(6), dp(14), dp(6), dp(4));
            lbl.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
            tile.addView(lbl, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            if (p.equals(active)) {
                TextView b = title("ACTIF", 9.5f, P.BG, true);
                b.setPadding(dp(6), dp(2), dp(6), dp(2));
                GradientDrawable bd = new GradientDrawable();
                bd.setColor(P.ACCENT);
                bd.setCornerRadius(dp(5));
                b.setBackground(bd);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                blp.setMargins(dp(5), dp(5), 0, 0);
                tile.addView(b, blp);
            }
            tile.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sp.edit().putString("active", p).apply();
                    refreshLibrary();
                    Toast.makeText(SettingsActivity.this, "Fond d'écran changé", Toast.LENGTH_SHORT).show();
                }
            });
            tile.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("Supprimer ?")
                            .setMessage(P.displayName(p))
                            .setPositiveButton("Supprimer", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface di, int i) {
                                    new File(p).delete();
                                    List<String> l = P.list(SettingsActivity.this, "library");
                                    l.remove(p);
                                    P.saveList(SettingsActivity.this, "library", l);
                                    if (p.equals(sp.getString("active", ""))) {
                                        List<String> rest = P.library(SettingsActivity.this);
                                        sp.edit().putString("active", rest.isEmpty() ? "" : rest.get(0)).apply();
                                    }
                                    refreshLibrary();
                                    updateDN();
                                }
                            })
                            .setNegativeButton("Annuler", null).show();
                    return true;
                }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w;
            lp.height = w * 16 / 10;
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            libGrid.addView(tile, lp);
            new Thread(new Runnable() {
                @Override public void run() {
                    final Bitmap bm = P.thumbnail(p, 240);
                    h.post(new Runnable() { @Override public void run() { if (bm != null) iv.setImageBitmap(bm); } });
                }
            }).start();
        }
        if (lib.isEmpty()) {
            TextView e = title("Aucune vidéo pour l'instant — ajoute-en une ci-dessous 👇", 13, P.MUTED, false);
            e.setPadding(0, dp(10), 0, dp(4));
            libGrid.addView(e);
        }
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "image/gif"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<Uri>();
        ClipData cd = data.getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        Toast.makeText(this, "Import de " + uris.size() + " fichier(s)…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> added = new ArrayList<String>();
                for (Uri u : uris) {
                    String name = queryName(u);
                    String type = getContentResolver().getType(u);
                    if (type != null && type.equals("image/gif") && !name.toLowerCase().endsWith(".gif")) name += ".gif";
                    File dst = new File(P.libDir(SettingsActivity.this), System.currentTimeMillis() + "_" + name.replaceAll("[\\\\/:*?\"<>|]", "_"));
                    try {
                        InputStream in = getContentResolver().openInputStream(u);
                        OutputStream out = new FileOutputStream(dst);
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        out.close();
                        in.close();
                        added.add(dst.getAbsolutePath());
                    } catch (Exception e) {
                        dst.delete();
                    }
                }
                h.post(new Runnable() {
                    @Override public void run() {
                        List<String> l = P.list(SettingsActivity.this, "library");
                        l.addAll(added);
                        P.saveList(SettingsActivity.this, "library", l);
                        if (!added.isEmpty()) sp.edit().putString("active", added.get(0)).apply();
                        refreshLibrary();
                        updateDN();
                        Toast.makeText(SettingsActivity.this, added.size() + " ajouté(s) ✓", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private String queryName(Uri u) {
        String n = "video";
        try {
            Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) n = c.getString(0);
                c.close();
            }
        } catch (Exception ignored) {}
        return n;
    }

    // ---------------------------------------------------------------- day / night

    private void updateDN() {
        boolean sun = "sun".equals(sp.getString("dnMode", "fixed"));
        dnFixedRow.setVisibility(sun ? View.GONE : View.VISIBLE);
        dayTime.setText("☀️ Jour : " + sp.getString("dayStart", "07:00"));
        nightTime.setText("🌙 Nuit : " + sp.getString("nightStart", "19:30"));
        int[] b = P.dayBounds(this);
        if (sun) {
            dnInfo.setText(sp.getString("city", "").isEmpty()
                    ? "Indique ta ville dans Widgets → Météo pour suivre le soleil."
                    : "📍 " + sp.getString("city", "") + " — lever " + P.hm(b[0]) + " · coucher " + P.hm(b[1]));
        } else {
            dnInfo.setText("En ce moment : " + ("day".equals(P.period(this)) ? "☀️ jour" : "🌙 nuit"));
        }
        String d = sp.getString("dayVideo", ""), n = sp.getString("nightVideo", "");
        dayPick.setText("☀️ Vidéo du jour : " + (d.isEmpty() || !new File(d).exists() ? "choisir…" : P.displayName(d)));
        nightPick.setText("🌙 Vidéo de la nuit : " + (n.isEmpty() || !new File(n).exists() ? "choisir…" : P.displayName(n)));
    }

    private void pickTime(final String key, String def) {
        int m = Math.max(0, P.parseHM(sp.getString(key, def)));
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(TimePicker v, int hh, int mm) {
                sp.edit().putString(key, P.hm(hh * 60 + mm)).apply();
                updateDN();
            }
        }, m / 60, m % 60, true).show();
    }

    private void chooseFromLibrary(final String key, String titleText) {
        final List<String> lib = P.library(this);
        if (lib.isEmpty()) {
            Toast.makeText(this, "Ajoute d'abord des vidéos à ta bibliothèque", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[lib.size()];
        for (int i = 0; i < lib.size(); i++) names[i] = (P.isGif(lib.get(i)) ? "GIF · " : "🎬 ") + P.displayName(lib.get(i));
        new AlertDialog.Builder(this).setTitle(titleText).setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int i) {
                sp.edit().putString(key, lib.get(i)).apply();
                updateDN();
            }
        }).setNegativeButton("Annuler", null).show();
    }

    // ---------------------------------------------------------------- weather

    private String weatherInfo() {
        if (sp.getString("city", "").isEmpty()) return "Indique ta ville pour la météo et le soleil";
        if (sp.getLong("wxTime", 0) == 0) return "📍 " + sp.getString("city", "");
        String[] w = P.weatherText(sp.getInt("wcode", 0), sp.getInt("isDay", 1) == 1);
        return "📍 " + sp.getString("city", "") + " — " + w[0] + " " + Math.round(sp.getFloat("temp", 0)) + "° " + w[1];
    }

    private void saveCity(final String name) {
        if (name.trim().isEmpty()) return;
        cityInfo.setText("Recherche…");
        new Thread(new Runnable() {
            @Override public void run() {
                final String err = P.setCity(SettingsActivity.this, name.trim());
                h.post(new Runnable() {
                    @Override public void run() {
                        cityInfo.setText(err != null ? err : weatherInfo());
                        updateDN();
                    }
                });
            }
        }).start();
    }
}
