package com.mineboy.vwhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.audiofx.Visualizer;
import android.view.View;

/** Audio spectrum of whatever plays on the phone. Invisible when it's silent. */
public class VizView extends View {
    private static final int N = 20;                 // bands per side (mirrored -> 40 bars)
    private final float[] raw = new float[N], shown = new float[N];
    private Visualizer vis;
    private long lastData;
    private float gain = 0.35f;                       // automatic gain (follows the loudest band)
    private float activity = 0f;                      // 0 = hidden, 1 = fully visible
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint soft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    public int color = P.ACCENT;
    public String style = "bars";
    public float sens = 1f;
    public float bottomInset = 0;                     // distance from the bottom to stay above (search bar + dock)
    private boolean running;
    private Shader shader;
    private float shaderTop = -1, shaderBase = -1;
    private int shaderColor;

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
        for (int i = 0; i < N; i++) { raw[i] = 0; shown[i] = 0; }
        activity = 0;
        invalidate();
    }

    private void analyse(byte[] fft, int rateHz) {
        int n = fft.length / 2;
        if (n < 4 || rateHz <= 0) return;
        double fmin = 45, fmax = Math.min(12000, rateHz / 2.0);
        float loudest = 0;
        for (int b = 0; b < N; b++) {
            double f0 = fmin * Math.pow(fmax / fmin, (double) b / N), f1 = fmin * Math.pow(fmax / fmin, (double) (b + 1) / N);
            int i0 = Math.max(1, (int) (f0 / (rateHz / 2.0) * n)), i1 = Math.max(i0 + 1, (int) (f1 / (rateHz / 2.0) * n));
            double sum = 0;
            int cnt = 0;
            for (int i = i0; i < i1 && i < n; i++) {
                sum += Math.hypot(fft[2 * i], fft[2 * i + 1]);
                cnt++;
            }
            double m = cnt > 0 ? sum / cnt : 0;
            m *= 1 + b / (double) N * 2.0;                     // lift the treble a bit
            float v = (float) Math.max(0, (20 * Math.log10(m + 1) - 6) / 30);
            raw[b] = v;
            loudest = Math.max(loudest, v);
        }
        if (loudest > 0.08f) lastData = System.currentTimeMillis();
        // automatic gain: quickly follows loud parts, slowly relaxes in quiet parts
        gain = loudest > gain ? gain + (loudest - gain) * 0.3f : Math.max(0.25f, gain * 0.997f);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (!running) return;
        boolean playing = System.currentTimeMillis() - lastData < 500;
        activity += ((playing ? 1f : 0f) - activity) * (playing ? 0.12f : 0.06f);
        for (int i = 0; i < N; i++) {
            float t = playing ? Math.min(1f, raw[i] / gain * 0.9f * sens) : 0f;
            shown[i] += (t - shown[i]) * (t > shown[i] ? 0.5f : 0.14f);
        }
        if (activity < 0.02f) {               // silence: draw nothing, check again a bit later
            postInvalidateDelayed(120);
            return;
        }
        float W = getWidth(), H = getHeight();
        int alpha = (int) (activity * 255);
        int col = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));

        if ("circle".equals(style)) {
            float cx = W / 2, cy = H * 0.4f, R = W * 0.2f;
            int n = N * 2;
            paint.setShader(null);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth((float) (2 * Math.PI * R / n * 0.5));
            soft.setStrokeCap(Paint.Cap.ROUND);
            soft.setStrokeWidth(paint.getStrokeWidth() * 2.2f);
            soft.setColor(Color.argb(alpha / 4, Color.red(color), Color.green(color), Color.blue(color)));
            paint.setColor(col);
            for (int i = 0; i < n; i++) {
                float lv = shown[i < N ? i : n - 1 - i];
                if (lv < 0.03f) continue;
                double ang = i * 2 * Math.PI / n - Math.PI / 2;
                float len = lv * W * 0.18f;
                float x0 = cx + (float) Math.cos(ang) * R, y0 = cy + (float) Math.sin(ang) * R;
                float x1 = cx + (float) Math.cos(ang) * (R + len), y1 = cy + (float) Math.sin(ang) * (R + len);
                c.drawLine(x0, y0, x1, y1, soft);
                c.drawLine(x0, y0, x1, y1, paint);
            }
        } else if ("wave".equals(style)) {
            float base = H - bottomInset - P.dp(getContext(), 30), amp = H * 0.09f;
            path.reset();
            int n = N * 2;
            float step = W / (n - 1);
            for (int i = 0; i < n; i++) {
                float lv = i < N ? shown[N - 1 - i] : shown[i - N];
                float x = i * step, y = base - lv * amp;
                if (i == 0) path.moveTo(x, y);
                else {
                    float px = (i - 1) * step;
                    float pl = (i - 1) < N ? shown[N - i] : shown[i - 1 - N];
                    float py = base - pl * amp;
                    path.quadTo(px, py, (px + x) / 2, (py + y) / 2);
                }
            }
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            soft.setStyle(Paint.Style.STROKE);
            soft.setStrokeWidth(P.dp(getContext(), 9));
            soft.setColor(Color.argb(alpha / 5, Color.red(color), Color.green(color), Color.blue(color)));
            paint.setStrokeWidth(P.dp(getContext(), 3));
            paint.setColor(col);
            c.drawPath(path, soft);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            soft.setStyle(Paint.Style.FILL);
        } else { // bars, mirrored from the middle
            int n = N * 2;
            float side = W * 0.06f;
            float slot = (W - 2 * side) / n, bw = slot * 0.58f;
            float base = H - bottomInset - P.dp(getContext(), 10), maxH = H * 0.13f;
            if (shader == null || shaderBase != base || shaderTop != base - maxH || shaderColor != color) {
                shader = new LinearGradient(0, base - maxH, 0, base, Color.WHITE, color, Shader.TileMode.CLAMP);
                shaderBase = base; shaderTop = base - maxH; shaderColor = color;
            }
            paint.setShader(shader);
            paint.setAlpha(alpha);
            soft.setColor(Color.argb(alpha / 5, Color.red(color), Color.green(color), Color.blue(color)));
            for (int i = 0; i < n; i++) {
                float lv = i < N ? shown[N - 1 - i] : shown[i - N];
                if (lv < 0.02f) continue;
                float h = lv * maxH, x = side + i * slot + (slot - bw) / 2;
                r.set(x - bw * 0.35f, base - h - bw * 0.35f, x + bw * 1.35f, base);
                c.drawRoundRect(r, bw, bw, soft);           // soft glow
                r.set(x, base - h, x + bw, base);
                c.drawRoundRect(r, bw / 2, bw / 2, paint);
            }
            paint.setShader(null);
            paint.setAlpha(255);
        }
        postInvalidateOnAnimation();
    }
}
