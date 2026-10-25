package com.mineboy.vwhome;

import android.content.Intent;
import android.provider.Settings;

import java.text.Normalizer;
import java.util.Locale;

/** Search helpers: calculator, app matching, system settings shortcuts. */
public class Search {

    public static String norm(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }

    /** Higher is better, 0 = no match. Supports initials ("yt" -> YouTube, "gm" -> Google Maps). */
    public static int appScore(String label, String q) {
        String l = norm(label), s = norm(q).trim();
        if (s.isEmpty()) return 0;
        if (l.equals(s)) return 100;
        if (l.startsWith(s)) return 90;
        String[] words = l.split("[\\s\\-_.:]+");
        for (String w : words) if (w.startsWith(s)) return 80;
        // initials of words, or of CamelCase parts
        StringBuilder ini = new StringBuilder();
        for (String w : words) if (!w.isEmpty()) ini.append(w.charAt(0));
        StringBuilder camel = new StringBuilder();
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (i == 0 || Character.isUpperCase(c) || (i > 0 && label.charAt(i - 1) == ' ')) camel.append(Character.toLowerCase(c));
        }
        if (s.length() >= 2 && (ini.toString().startsWith(s) || norm(camel.toString()).startsWith(s))) return 70;
        if (l.contains(s)) return 60;
        // letters in order ("ytb" -> youtube)
        int j = 0;
        for (int i = 0; i < l.length() && j < s.length(); i++) if (l.charAt(i) == s.charAt(j)) j++;
        if (j == s.length() && s.length() >= 3) return 30;
        return 0;
    }

    // ---------------------------------------------------------------- calculator

    private String src;
    private int pos;

    /** Returns the result or null when the text is not a calculation. */
    public static Double calc(String text) {
        String t = text.trim().replace('×', '*').replace('÷', '/').replace(',', '.').replace("x", "*").replace("X", "*");
        if (!t.matches(".*\\d.*") || !t.matches(".*[-+*/^%()√].*") || !t.matches("[0-9.+\\-*/^%() a-z√π]+")) return null;
        if (t.matches("^[-+]?\\d+(\\.\\d+)?$")) return null;
        try {
            Search p = new Search();
            p.src = t.replace(" ", "");
            p.pos = 0;
            double v = p.expr();
            if (p.pos != p.src.length() || Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    public static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e15) return String.valueOf((long) Math.rint(v));
        String s = String.format(Locale.ROOT, "%.10f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace('.', ',');
    }

    private char peek() { return pos < src.length() ? src.charAt(pos) : '\0'; }

    private double expr() {
        double v = term();
        while (peek() == '+' || peek() == '-') {
            char op = src.charAt(pos++);
            double r = term();
            v = op == '+' ? v + r : v - r;
        }
        return v;
    }

    private double term() {
        double v = factor();
        while (peek() == '*' || peek() == '/' || peek() == '%') {
            char op = src.charAt(pos++);
            if (op == '%' && !(Character.isDigit(peek()) || peek() == '(')) { v = v / 100; continue; } // "50%"
            double r = factor();
            v = op == '*' ? v * r : op == '/' ? v / r : v % r;
        }
        return v;
    }

    private double factor() {
        double v = unary();
        if (peek() == '^') { pos++; v = Math.pow(v, factor()); }
        return v;
    }

    private double unary() {
        if (peek() == '-') { pos++; return -unary(); }
        if (peek() == '+') { pos++; return unary(); }
        return primary();
    }

    private double primary() {
        if (peek() == '(') {
            pos++;
            double v = expr();
            if (peek() == ')') pos++;
            return v;
        }
        if (peek() == '√') { pos++; return Math.sqrt(unary()); }
        if (peek() == 'π') { pos++; return Math.PI; }
        if (Character.isLetter(peek())) {
            int s = pos;
            while (Character.isLetter(peek())) pos++;
            String f = src.substring(s, pos);
            if (f.equals("pi")) return Math.PI;
            double a = primary();
            if (f.equals("sqrt") || f.equals("racine")) return Math.sqrt(a);
            if (f.equals("sin")) return Math.sin(Math.toRadians(a));
            if (f.equals("cos")) return Math.cos(Math.toRadians(a));
            if (f.equals("tan")) return Math.tan(Math.toRadians(a));
            if (f.equals("log")) return Math.log10(a);
            if (f.equals("ln")) return Math.log(a);
            throw new RuntimeException("fn");
        }
        int s = pos;
        while (Character.isDigit(peek()) || peek() == '.') pos++;
        if (s == pos) throw new RuntimeException("num");
        return Double.parseDouble(src.substring(s, pos));
    }

    // ---------------------------------------------------------------- settings

    public static final String[][] SETTINGS = {
            // icon, title, keywords, action
            {"📶", "Wi-Fi", "wifi wi-fi internet reseau wlan", Settings.ACTION_WIFI_SETTINGS},
            {"🔵", "Bluetooth", "bluetooth ecouteurs casque airpods buds", Settings.ACTION_BLUETOOTH_SETTINGS},
            {"📱", "Données mobiles", "donnees mobiles 4g 5g reseau sim data", Settings.ACTION_DATA_ROAMING_SETTINGS},
            {"✈️", "Mode avion", "avion airplane", Settings.ACTION_AIRPLANE_MODE_SETTINGS},
            {"🔋", "Batterie", "batterie battery economie energie", Intent.ACTION_POWER_USAGE_SUMMARY},
            {"💡", "Affichage", "affichage ecran luminosite display brightness mode sombre dark", Settings.ACTION_DISPLAY_SETTINGS},
            {"🔊", "Son et vibration", "son sonnerie volume vibration sound", Settings.ACTION_SOUND_SETTINGS},
            {"🔔", "Notifications", "notifications notif", "android.settings.NOTIFICATION_SETTINGS"},
            {"🧩", "Applications", "applications apps applis", Settings.ACTION_APPLICATION_SETTINGS},
            {"💾", "Stockage", "stockage memoire storage espace", Settings.ACTION_INTERNAL_STORAGE_SETTINGS},
            {"📍", "Localisation", "localisation position gps location", Settings.ACTION_LOCATION_SOURCE_SETTINGS},
            {"🔒", "Sécurité", "securite verrouillage empreinte mot de passe pin security", Settings.ACTION_SECURITY_SETTINGS},
            {"♿", "Accessibilité", "accessibilite accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS},
            {"🕒", "Date et heure", "date heure time horloge", Settings.ACTION_DATE_SETTINGS},
            {"🌐", "Langue", "langue language clavier keyboard", Settings.ACTION_LOCALE_SETTINGS},
            {"📡", "NFC", "nfc paiement sans contact", Settings.ACTION_NFC_SETTINGS},
            {"📺", "Diffuser l'écran", "cast diffuser smart view tv", Settings.ACTION_CAST_SETTINGS},
            {"🛡️", "VPN", "vpn", Settings.ACTION_VPN_SETTINGS},
            {"🏠", "Application d'accueil", "launcher accueil home ecran d'accueil", Settings.ACTION_HOME_SETTINGS},
            {"ℹ️", "À propos du téléphone", "a propos telephone version android modele about", Settings.ACTION_DEVICE_INFO_SETTINGS},
            {"⚙️", "Paramètres", "parametres reglages settings options", Settings.ACTION_SETTINGS},
    };

    public static int settingScore(String[] s, String q) {
        String n = norm(q).trim();
        if (n.length() < 2) return 0;
        if (norm(s[1]).startsWith(n)) return 90;
        for (String k : s[2].split(" ")) if (k.startsWith(n)) return 70;
        if (norm(s[1]).contains(n)) return 50;
        return 0;
    }

    public static boolean looksLikeUrl(String q) {
        String t = q.trim();
        return !t.contains(" ") && t.matches("(?i)^(https?://)?[a-z0-9-]+(\\.[a-z0-9-]+)+(/.*)?$");
    }
}
