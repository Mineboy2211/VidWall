package com.mineboy.vwhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.audiofx.Visualizer;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/**
 * Audio visualizer of whatever plays on the phone (same styles as VidWall PC):
 * Spectre, Aurore, Anneau, Particules, Lueur. Beat detection on the bass, automatic gain,
 * and it fades out completely when nothing plays.
 */
public class VizView extends View {
    private static final int NB = 48;                 // bands, low -> high (not mirrored)
    private final float[] raw = new float[NB], shown = new float[NB], peaks = new float[NB], peakV = new float[NB];
    private Visualizer vis;
    private volatile long lastData;
    private float gain = 0.35f, activity = 0f;
    private float bassAvg = 0, beat = 0, hueT = 0;
    private long lastBeat = 0, lastFrame = 0;
    private final ArrayList<float[]> hist = new ArrayList<>();   // past frames (aurora echoes)
    private final ArrayList<Part> parts = new ArrayList<>();
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    public int color = P.ACCENT;
    public String style = "spectre";
    public float sens = 1f;
    public float bottomInset = 0;                     // stay above the search bar / dock
    private boolean running;
    private float baseH, baseS, baseL;
    private int palColor = 0;

    private static class Part { float x, y, vx, vy, life, size, dh; }

    public VizView(Context c) {
        super(c);
    }

    public boolean start() {
        stop();
        try {
            vis = new Visualizer(0);
            vis.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.min(1024, range[1]));
            try { vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED); } catch (Throwable ignored) {}
            vis.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] w, int s) {}
                @Override public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRate) { analyse(fft, samplingRate / 1000); }
            }, Math.min(Visualizer.getMaxCaptureRate(), 30000), false, true);
            vis.setEnabled(true);
            running = true;
            lastFrame = 0;
            postInvalidateOnAnimation();
            return true;
        } catch (Throwable t) {
            vis = null;
            return false;
        }
    }

    public void stop() {
        running = false;
        if (vis != null) {
            try { vis.setEnabled(false); vis.release(); } catch (Throwable ignored) {}
            vis = null;
        }
        for (int i = 0; i < NB; i++) { raw[i] = 0; shown[i] = 0; peaks[i] = 0; }
        activity = 0;
        parts.clear();
        hist.clear();
        invalidate();
    }

    private void analyse(byte[] fft, int rateHz) {
        int n = fft.length / 2;
        if (n < 4 || rateHz <= 0) return;
        double fmin = 40, fmax = Math.min(14000, rateHz / 2.0), binHz = rateHz / 2.0 / n;
        float loudest = 0;
        int prevI1 = 1;
        for (int b = 0; b < NB; b++) {
            double f0 = fmin * Math.pow(fmax / fmin, (double) b / NB), f1 = fmin * Math.pow(fmax / fmin, (double) (b + 1) / NB);
            // low bands are narrower than one FFT bin: interpolate instead of repeating the same bin
            double c0 = f0 / binHz, c1 = f1 / binHz;
            double m;
            if (c1 - c0 < 1) {
                double pos = Math.max(1, (c0 + c1) / 2);
                int i = Math.min(n - 2, (int) pos);
                double fr = pos - i;
                double a = Math.hypot(fft[2 * i], fft[2 * i + 1]), bb = Math.hypot(fft[2 * i + 2], fft[2 * i + 3]);
                m = a * (1 - fr) + bb * fr;
            } else {
                int i0 = Math.max(prevI1, (int) c0), i1 = Math.max(i0 + 1, (int) c1);
                double sum = 0;
                int cnt = 0;
                for (int i = i0; i < i1 && i < n; i++) { sum += Math.hypot(fft[2 * i], fft[2 * i + 1]); cnt++; }
                m = cnt > 0 ? sum / cnt : 0;
                prevI1 = i1;
            }
            m *= 1 + b / (double) NB * 2.2;                    // lift the treble a bit
            float v = (float) Math.max(0, (20 * Math.log10(m + 1) - 6) / 30);
            raw[b] = v;
            loudest = Math.max(loudest, v);
        }
        if (loudest > 0.08f) lastData = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- colors (like the PC palette)

    private void palette() {
        if (palColor == color) return;
        palColor = color;
        float[] hsl = rgbToHsl(Color.red(color), Color.green(color), Color.blue(color));
        baseH = hsl[0];
        baseS = Math.max(0.55f, hsl[1]);
        baseL = Math.min(0.70f, Math.max(0.55f, hsl[2]));
    }

    /** color of the palette: alpha 0..1, hue shift in degrees, lightness shift in % */
    private int pc(float a, float dh, float dl) {
        float l = Math.min(0.85f, Math.max(0.45f, baseL + dl / 100f));
        int rgb = hslToRgb(((baseH + dh) % 360 + 360) % 360, baseS, l);
        return Color.argb(Math.max(0, Math.min(255, (int) (a * 255))), Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    private static float[] rgbToHsl(int ri, int gi, int bi) {
        float r = ri / 255f, g = gi / 255f, b = bi / 255f;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        float h = 0, s = 0, l = (mx + mn) / 2;
        if (mx != mn) {
            float d = mx - mn;
            s = l > 0.5f ? d / (2 - mx - mn) : d / (mx + mn);
            if (mx == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (mx == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h *= 60;
        }
        return new float[]{h, s, l};
    }

    private static int hslToRgb(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = l - c / 2;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return Color.rgb((int) ((r + m) * 255), (int) ((g + m) * 255), (int) ((b + m) * 255));
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (!running) return;
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float k = dt * 60;                                       // frame-rate independent smoothing
        boolean playing = now - lastData < 600;
        activity += ((playing ? 1f : 0f) - activity) * Math.min(1, (playing ? 0.12f : 0.07f) * k);

        float loud = 0;
        for (int i = 0; i < NB; i++) loud = Math.max(loud, raw[i]);
        gain = loud > gain ? gain + (loud - gain) * 0.25f : Math.max(0.22f, gain - 0.0015f * k);
        for (int i = 0; i < NB; i++) {
            float t = playing ? Math.min(1f, raw[i] / gain * 0.92f * sens) : 0f;
            shown[i] += (t - shown[i]) * Math.min(1, (t > shown[i] ? 0.55f : 0.13f) * k);
            if (shown[i] > peaks[i]) { peaks[i] = shown[i]; peakV[i] = 0; }
            else { peakV[i] += 0.0009f * k; peaks[i] = Math.max(0, peaks[i] - peakV[i] * k); }
        }
        // beat detection on the bass
        float bass = (shown[0] + shown[1] + shown[2] + shown[3]) / 4;
        bassAvg += (bass - bassAvg) * 0.04f * k;
        if (bass > bassAvg * 1.3f + 0.08f && bass > 0.3f && now - lastBeat > 200) { lastBeat = now; beat = 1; onBeat(); }
        beat = Math.max(0, beat - 0.06f * k);
        hueT += dt * 8;

        if (activity < 0.015f && !playing) {      // silence: draw nothing, check again a bit later
            parts.clear();
            hist.clear();
            postInvalidateDelayed(120);
            return;
        }
        palette();
        float W = getWidth(), H = getHeight(), A = activity;
        String st = style == null ? "spectre" : style;
        switch (st) {
            case "aurore": case "wave": drawAurora(c, W, H, A); break;
            case "anneau": case "circle": drawRing(c, W, H, A, bass); break;
            case "particules": drawParticles(c, W, H, A, dt); break;
            case "lueur": case "glow": drawGlow(c, W, H, A, bass); break;
            default: drawSpectrum(c, W, H, A);
        }
        postInvalidateOnAnimation();
    }

    private float base(float H) { return H - bottomInset - P.dp(getContext(), 8); }

    // ---- 1. spectrum low -> high with falling peak caps
    private Shader specShader;
    private float specKeyBase = -1, specKeyH = -1;
    private int specKeyCol;

    private void drawSpectrum(Canvas c, float W, float H, float A) {
        float side = W * 0.04f, slot = (W - 2 * side) / NB, bw = slot * 0.62f;
        float base = base(H), maxH = H * 0.16f;
        if (specShader == null || specKeyBase != base || specKeyH != maxH || specKeyCol != color) {
            specShader = new LinearGradient(0, base - maxH, 0, base, new int[]{pc(1, 40, 15), pc(1, 0, 0), pc(0.9f, -30, -5)}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP);
            specKeyBase = base; specKeyH = maxH; specKeyCol = color;
        }
        int al = (int) (A * 255);
        glow.setShader(null);
        glow.setColor(pc(0.12f * A, 0, 0));
        for (int i = 0; i < NB; i++) {
            float h = shown[i] * maxH;
            if (h < 2) continue;
            float x = side + i * slot;
            r.set(x - bw * 0.2f, base - h - bw * 0.6f, x + bw * 1.2f + slot - bw, base);
            c.drawRoundRect(r, bw, bw, glow);
        }
        paint.setShader(specShader);
        paint.setAlpha(al);
        Paint cap = glow;
        for (int i = 0; i < NB; i++) {
            float h = shown[i] * maxH, x = side + i * slot + (slot - bw) / 2;
            if (h >= 2) { r.set(x, base - h, x + bw, base); c.drawRoundRect(r, bw / 2, bw / 2, paint); }
            float ph = peaks[i] * maxH;
            if (ph > 4) {
                cap.setColor(Color.argb((int) (230 * A), 255, 255, 255));
                r.set(x, base - ph - bw * 0.55f, x + bw, base - ph - bw * 0.25f);
                c.drawRect(r, cap);
            }
        }
        paint.setShader(null);
        paint.setAlpha(255);
    }

    // ---- 2. aurora: flowing layered curves with echoes of the previous frames
    private void drawAurora(Canvas c, float W, float H, float A) {
        hist.add(0, shown.clone());
        if (hist.size() > 18) hist.remove(hist.size() - 1);
        float base = base(H), amp = H * 0.22f;
        float[][] layers = {{0, 0, 0.85f}, {7, 70, 0.6f}, {14, -60, 0.45f}};
        float[] px = new float[25], py = new float[25];
        for (float[] L : layers) {
            int d = (int) L[0];
            float dh = L[1], al = L[2];
            float[] f = hist.get(Math.min(d, hist.size() - 1));
            float top = base;
            for (int i = 0; i <= 24; i++) {
                int b = Math.min(NB - 1, (int) Math.floor(Math.pow(i / 24.0, 1.6) * (NB - 1)));
                float sway = (float) Math.sin(hueT * 0.35 + i * 0.55 + d) * 0.04f;
                px[i] = i / 24f * W;
                py[i] = base - (f[b] * 0.9f + 0.05f + sway) * amp;
                top = Math.min(top, py[i]);
            }
            curve(px, py);
            path.lineTo(W, base + 2);
            path.lineTo(0, base + 2);
            path.close();
            float hs = (float) Math.sin(hueT * 0.05) * 20;
            glow.setShader(new LinearGradient(0, top, 0, base, new int[]{pc(al * 0.75f * A, dh + hs, 12), pc(al * 0.35f * A, dh, 0), pc(al * 0.08f * A, dh, 0)},
                    new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
            glow.setStyle(Paint.Style.FILL);
            c.drawPath(path, glow);
            glow.setShader(null);
            curve(px, py);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(3, H * 0.003f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(pc(Math.min(1, al * 1.3f) * A, dh, 22));
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void curve(float[] x, float[] y) {
        path.reset();
        path.moveTo(x[0], y[0]);
        for (int i = 1; i < x.length - 1; i++) path.quadTo(x[i], y[i], (x[i] + x[i + 1]) / 2, (y[i] + y[i + 1]) / 2);
        path.lineTo(x[x.length - 1], y[y.length - 1]);
    }

    // ---- 3. ring: one continuous shape, pulses on the bass
    private void drawRing(Canvas c, float W, float H, float A, float bass) {
        float cx = W / 2, cy = H * 0.45f, R = W * (0.24f + bass * 0.05f + beat * 0.04f);
        int n = 96;
        float rot = hueT * 0.02f;
        float[][] rings = {{1, 0, 1, 0.012f}, {0.82f, 60, 0.7f, 0.007f}, {1.18f, -50, 0.5f, 0.005f}};
        for (float[] g : rings) {
            float scale = g[0], dh = g[1], al = g[2], lw = g[3];
            path.reset();
            for (int i = 0; i <= n; i++) {
                double a = (i / (double) n) * Math.PI * 2 + rot * (scale > 1 ? -1 : 1);
                float pos = (i % n) / (float) n;
                int b1 = (int) Math.floor(pos * (NB - 1));
                float w = Math.min(1, (1 - pos) * 8);             // blend the seam so the ring closes smoothly
                float lv = shown[b1] * w + shown[0] * (1 - w);
                float rr = R * scale + lv * W * 0.16f * (scale == 1 ? 1 : 0.6f);
                float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(W * lw);
            paint.setColor(pc(al * A, dh + (float) Math.sin(hueT * 0.04) * 15, 12));
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        // inner glow flashing on beats
        glow.setShader(new RadialGradient(cx, cy, Math.max(1, R * 0.95f), pc((0.15f + beat * 0.5f) * A, 0, 20), pc(0, 0, 0), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, R * 0.95f, glow);
        glow.setShader(null);
    }

    // ---- 4. particles: rise from the bottom with the music, burst on beats
    private void onBeat() {
        if (!"particules".equals(style)) return;
        float W = getWidth(), H = getHeight();
        for (int i = 0; i < 22 && parts.size() < 400; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            float sp = (0.2f + rnd.nextFloat() * 0.5f) * H;
            Part p = new Part();
            p.x = W / 2; p.y = H * 0.45f;
            p.vx = (float) Math.cos(a) * sp; p.vy = (float) Math.sin(a) * sp;
            p.life = 1; p.size = 1.5f + rnd.nextFloat() * 3; p.dh = rnd.nextFloat() * 80 - 40;
            parts.add(p);
        }
    }

    private void drawParticles(Canvas c, float W, float H, float A, float dt) {
        float bottom = H - bottomInset;
        for (int i = 0; i < NB; i += 2) {
            if (rnd.nextFloat() < shown[i] * 0.35f && parts.size() < 400) {
                Part p = new Part();
                p.x = (i + rnd.nextFloat() * 2) / NB * W; p.y = bottom + 4;
                p.vx = (rnd.nextFloat() - 0.5f) * H * 0.04f; p.vy = -(0.1f + shown[i] * 0.4f) * H;
                p.life = 1; p.size = 1 + shown[i] * 4; p.dh = i * 1.8f - 40;
                parts.add(p);
            }
        }
        float S = W / 1000f;
        for (int i = parts.size() - 1; i >= 0; i--) {
            Part p = parts.get(i);
            p.x += p.vx * dt; p.y += p.vy * dt; p.vy *= 0.995f; p.vx *= 0.99f; p.life -= dt * 0.55f;
            if (p.life <= 0 || p.y < -20 || p.x < -20 || p.x > W + 20) { parts.remove(i); continue; }
            paint.setColor(pc(p.life * 0.85f * A, p.dh, 15));
            c.drawCircle(p.x, p.y, p.size * S * 4 * (0.6f + p.life * 0.4f), paint);
        }
    }

    // ---- 5. glow: the screen edges breathe with the bass, the color drifts with the treble
    private void drawGlow(Canvas c, float W, float H, float A, float bass) {
        float treble = 0;
        for (int i = 30; i < NB; i++) treble += shown[i];
        treble /= NB - 30;
        float rad = (float) Math.hypot(W, H) / 1.7f;
        float inner = H * (0.42f - beat * 0.08f) / rad;
        glow.setShader(new RadialGradient(W / 2, H / 2, rad, new int[]{pc(0, 0, 0), pc(0, 0, 0), pc((0.35f + bass * 0.55f + beat * 0.25f) * A, treble * 90, 10)},
                new float[]{0, Math.max(0.05f, Math.min(0.9f, inner)), 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, glow);
        glow.setShader(null);
    }
}
