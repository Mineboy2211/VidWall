package com.mineboy.vwhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.MediaMetadataRetriever;
import android.util.TypedValue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Shared settings + helpers. */
public class P {
    public static final int ACCENT = 0xFF7C8CFF;
    public static final int ACCENT2 = 0xFF5EE1FF;
    public static final int BG = 0xFF0B0D1A;
    public static final int MUTED = 0xFF8F96C4;
    public static final int[] COLORS = {0xFF7C8CFF, 0xFF5EE1FF, 0xFFC084FC, 0xFFF472B6, 0xFFFB7185, 0xFFFBBF24, 0xFF4ADE80, 0xFFFFFFFF};

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("vwhome", Context.MODE_PRIVATE);
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    // ---------- string lists ----------
    public static List<String> list(Context c, String key) {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    public static void saveList(Context c, String key, List<String> l) {
        JSONArray a = new JSONArray();
        for (String s : l) a.put(s);
        sp(c).edit().putString(key, a.toString()).apply();
    }

    // ---------- library ----------
    public static File libDir(Context c) {
        File d = new File(c.getFilesDir(), "library");
        d.mkdirs();
        return d;
    }

    public static List<String> library(Context c) {
        List<String> out = new ArrayList<String>();
        for (String p : list(c, "library")) if (new File(p).exists()) out.add(p);
        return out;
    }

    public static boolean isGif(String p) {
        return p != null && p.toLowerCase().endsWith(".gif");
    }

    public static String active(Context c) {
        String a = sp(c).getString("active", "");
        return new File(a).exists() ? a : "";
    }

    public static String displayName(String p) {
        String n = new File(p).getName();
        int i = n.indexOf('_');
        return i > 0 && i < 16 ? n.substring(i + 1) : n;
    }

    public static Bitmap thumbnail(String p, int w) {
        try {
            Bitmap b;
            if (isGif(p)) {
                b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(new File(p)));
            } else {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                r.setDataSource(p);
                b = r.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                r.release();
            }
            if (b == null) return null;
            int h = Math.max(1, b.getHeight() * w / Math.max(1, b.getWidth()));
            return Bitmap.createScaledBitmap(b.copy(Bitmap.Config.ARGB_8888, false), w, h, true);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- day / night ----------
    public static int parseHM(String s) {
        try {
            String[] p = s.split(":");
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return -1;
        }
    }

    public static int[] dayBounds(Context c) {
        SharedPreferences s = sp(c);
        int d = parseHM(s.getString("dayStart", "07:00")), n = parseHM(s.getString("nightStart", "19:30"));
        if ("sun".equals(s.getString("dnMode", "fixed"))) {
            int a = hmFromISO(s.getString("sunrise", "")), b = hmFromISO(s.getString("sunset", ""));
            if (a >= 0) d = a;
            if (b >= 0) n = b;
        }
        if (d < 0) d = 7 * 60;
        if (n < 0) n = 19 * 60 + 30;
        return new int[]{d, n};
    }

    static int hmFromISO(String s) {
        int i = s.indexOf('T');
        return i >= 0 ? parseHM(s.substring(i + 1)) : -1;
    }

    public static String period(Context c) {
        Calendar cal = Calendar.getInstance();
        int m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int[] b = dayBounds(c);
        boolean day = b[0] < b[1] ? (m >= b[0] && m < b[1]) : (m >= b[0] || m < b[1]);
        return day ? "day" : "night";
    }

    public static String hm(int m) {
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    // ---------- weather (open-meteo, no key) ----------
    static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestProperty("User-Agent", "VidWallHome");
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l);
        r.close();
        return sb.toString();
    }

    /** Geocode a city; returns null on success, an error message otherwise. Blocking. */
    public static String setCity(Context c, String name) {
        try {
            JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&name=" + URLEncoder.encode(name, "UTF-8")));
            JSONArray r = o.optJSONArray("results");
            if (r == null || r.length() == 0) return "Ville introuvable";
            JSONObject g = r.getJSONObject(0);
            sp(c).edit().putString("city", g.getString("name")).putFloat("lat", (float) g.getDouble("latitude"))
                    .putFloat("lon", (float) g.getDouble("longitude")).putLong("wxTime", 0).apply();
            refreshWeather(c);
            return null;
        } catch (Exception e) {
            return "Pas de connexion";
        }
    }

    /** Fetch weather + sunrise/sunset. Blocking; call from a thread. */
    public static boolean refreshWeather(Context c) {
        SharedPreferences s = sp(c);
        if (s.getString("city", "").isEmpty()) return false;
        try {
            String u = String.format(java.util.Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&daily=sunrise,sunset&forecast_days=1&timezone=auto",
                    s.getFloat("lat", 0), s.getFloat("lon", 0));
            JSONObject o = new JSONObject(get(u));
            JSONObject cur = o.getJSONObject("current");
            JSONObject daily = o.getJSONObject("daily");
            s.edit().putFloat("temp", (float) cur.getDouble("temperature_2m")).putInt("wcode", cur.getInt("weather_code"))
                    .putInt("isDay", cur.optInt("is_day", 1))
                    .putString("sunrise", daily.getJSONArray("sunrise").getString(0))
                    .putString("sunset", daily.getJSONArray("sunset").getString(0))
                    .putLong("wxTime", System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String[] weatherText(int code, boolean isDay) {
        String ic, lb;
        if (code == 0) { ic = isDay ? "☀️" : "🌙"; lb = "Ensoleillé"; }
        else if (code == 1) { ic = isDay ? "🌤️" : "🌙"; lb = "Peu nuageux"; }
        else if (code == 2) { ic = "⛅"; lb = "Partiellement nuageux"; }
        else if (code == 3) { ic = "☁️"; lb = "Couvert"; }
        else if (code == 45 || code == 48) { ic = "🌫️"; lb = "Brouillard"; }
        else if (code >= 51 && code <= 57) { ic = "🌦️"; lb = "Bruine"; }
        else if (code >= 61 && code <= 67) { ic = "🌧️"; lb = "Pluie"; }
        else if (code >= 71 && code <= 77) { ic = "🌨️"; lb = "Neige"; }
        else if (code >= 80 && code <= 82) { ic = "🌦️"; lb = "Averses"; }
        else if (code == 85 || code == 86) { ic = "🌨️"; lb = "Averses de neige"; }
        else if (code >= 95) { ic = "⛈️"; lb = "Orage"; }
        else { ic = "🌡️"; lb = ""; }
        return new String[]{ic, lb};
    }
}
