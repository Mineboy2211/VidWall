package com.mineboy.vwhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** Checks updates.json in the GitHub repo and installs the new APK. */
public class Updater {
    static final String MANIFEST = "https://raw.githubusercontent.com/Mineboy2211/VidWall/main/updates.json";

    public static String current(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    static boolean newer(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = 0, y = 0;
            try { if (i < pa.length) x = Integer.parseInt(pa[i]); } catch (Exception ignored) {}
            try { if (i < pb.length) y = Integer.parseInt(pb[i]); } catch (Exception ignored) {}
            if (x != y) return x > y;
        }
        return false;
    }

    /** Blocking. Returns {version, url, notes} when an update exists, {"", "", error} on error, null when up to date. */
    public static String[] check(Context c) {
        try {
            JSONObject m = new JSONObject(P.get(MANIFEST + "?t=" + System.currentTimeMillis() / 300000));
            JSONObject e = m.optJSONObject("home");
            String best = e == null ? null : e.optString("version", null);
            String url = e == null ? null : e.optString("url", null);
            String notes = e == null ? "" : e.optString("notes", "");
            if (url == null || url.isEmpty()) best = null;
            P.sp(c).edit().putLong("updChecked", System.currentTimeMillis()).apply();
            if (best == null || !newer(best, current(c))) return null;
            return new String[]{best, url, notes};
        } catch (java.io.FileNotFoundException e) {
            return new String[]{"", "", "Aucune version publiée pour l'instant"};
        } catch (Exception e) {
            return new String[]{"", "", "Pas de connexion"};
        }
    }

    /** Shows the "update available" dialog. */
    public static void offer(final Activity a, final String[] u) {
        String msg = "Version " + u[0] + " disponible (tu as la " + current(a) + ").";
        if (u[2] != null && !u[2].trim().isEmpty()) msg += "\n\n" + u[2].trim();
        new AlertDialog.Builder(a).setTitle("⬆ Mise à jour")
                .setMessage(msg)
                .setPositiveButton("Installer", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { download(a, u[0], u[1]); }
                })
                .setNegativeButton("Plus tard", null).show();
    }

    static void download(final Activity a, String version, String url) {
        try {
            final DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle("VidWall Home " + version);
            r.setMimeType("application/vnd.android.package-archive");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "VidWallHome-" + version + ".apk");
            final long id = dm.enqueue(r);
            Toast.makeText(a, "Téléchargement de la mise à jour…", Toast.LENGTH_SHORT).show();
            final Handler h = new Handler(Looper.getMainLooper());
            h.post(new Runnable() {
                int ticks = 0;
                @Override public void run() {
                    Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
                    int status = -1;
                    if (c != null) {
                        if (c.moveToFirst()) status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        c.close();
                    }
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Uri apk = dm.getUriForDownloadedFile(id);
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(apk, "application/vnd.android.package-archive");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { a.startActivity(i); }
                        catch (Exception e) { Toast.makeText(a, "Ouvre l'APK depuis tes téléchargements", Toast.LENGTH_LONG).show(); }
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        Toast.makeText(a, "Le téléchargement a échoué", Toast.LENGTH_LONG).show();
                    } else if (ticks++ < 600) {
                        h.postDelayed(this, 500);
                    }
                }
            });
        } catch (Exception e) {
            Toast.makeText(a, "Impossible de télécharger : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Automatic check, at most every 12 hours; offers each new version once. */
    public static void autoCheck(final Activity a) {
        if (!P.sp(a).getBoolean("autoUpdate", true)) return;
        if (System.currentTimeMillis() - P.sp(a).getLong("updChecked", 0) < 12 * 3600 * 1000L) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final String[] u = check(a);
                if (u == null || u[0].isEmpty()) return;
                if (u[0].equals(P.sp(a).getString("updOffered", ""))) return;
                P.sp(a).edit().putString("updOffered", u[0]).apply();
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { if (!a.isFinishing()) offer(a, u); }
                });
            }
        }).start();
    }
}
