package pe.radio.rav4;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Vistas dibujadas a mano; toman los colores de Theme.P. */
public class Views {

    public static final int RIGHT = 0, LEFT = 1, DOWN = 2, UP = 3;

    /** Contenedor raíz que detecta deslizamientos en las 4 direcciones sin romper los toques normales. */
    public static class GestureFrame extends android.widget.FrameLayout {
        public interface Listener { boolean wants(int dir); void swipe(int dir); }
        private final Listener l;
        private float sx, sy;
        private boolean fired;
        private final float slop;

        public GestureFrame(Context c, Listener l) {
            super(c);
            this.l = l;
            slop = 70 * c.getResources().getDisplayMetrics().density;
        }

        private boolean track(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    sx = e.getX(); sy = e.getY(); fired = false; return false;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (fired) return true;
                    float dx = e.getX() - sx, dy = e.getY() - sy;
                    int dir = -1;
                    if (Math.abs(dx) > slop && Math.abs(dx) > 1.6f * Math.abs(dy)) dir = dx > 0 ? RIGHT : LEFT;
                    else if (Math.abs(dy) > slop && Math.abs(dy) > 1.6f * Math.abs(dx)) dir = dy > 0 ? DOWN : UP;
                    if (dir >= 0 && l.wants(dir)) { fired = true; l.swipe(dir); return true; }
                    return false;
                default:
                    return fired;
            }
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent e) { return track(e); }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) { track(e); return true; }
    }

    /** Velocímetro: segmentado (terminal) o anillo continuo (glass). */
    public static class Gauge extends View {
        private float target = 0, shown = 0;
        private final float max = 180;
        private String sub = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public Gauge(Context c) { super(c); }

        public void setSpeed(float kmh) {
            float v = Math.max(0, Math.min(max, kmh));
            if (sweeping) { pending = v; return; }
            target = v; invalidate();
        }
        public void setSub(String s) { sub = s; invalidate(); }
        private boolean sweeping = false; private float pending = 0;
        /** Barrido de aguja al encender (como el Cockpit). */
        public void sweep() {
            if (!anim) return;
            pending = target; sweeping = true; target = max; invalidate();
            postDelayed(new Runnable() { public void run() { sweeping = false; target = pending; invalidate(); } }, 750);
        }

        @Override
        protected void onDraw(Canvas c) {
            Theme.Pal P = Theme.P;
            shown += (target - shown) * 0.18f;
            if (Math.abs(target - shown) > 0.2f) postInvalidateOnAnimation(); else shown = target;

            float w = getWidth(), h = getHeight();
            float rad = Math.min(w * 0.42f, h * 0.44f);
            float cx = w / 2f, cy = h * 0.52f;
            float start = 135, sweep = 270, frac = shown / max;
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            p.setStyle(Paint.Style.STROKE);
            p.setShader(null);

            if (!P.glass) {
                int segs = 36;
                float gap = 2.2f, seg = sweep / segs;
                p.setStrokeWidth(rad * 0.09f);
                p.setStrokeCap(Paint.Cap.BUTT);
                for (int i = 0; i < segs; i++) {
                    float f = (i + 1f) / segs;
                    p.setColor(f <= frac ? (f > 0.67f ? P.err : f > 0.5f ? P.warn : P.accent) : P.track);
                    c.drawArc(r, start + i * seg + gap / 2, seg - gap, false, p);
                }
            } else {
                p.setStrokeWidth(rad * 0.12f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(P.track);
                c.drawArc(r, start, sweep, false, p);
                if (frac > 0.003f) {
                    p.setShader(new LinearGradient(cx - rad, cy + rad, cx + rad, cy - rad,
                            new int[]{P.ok, P.accent, frac > 0.6f ? P.err : P.accent}, null, Shader.TileMode.CLAMP));
                    c.drawArc(r, start, sweep * frac, false, p);
                    p.setShader(null);
                }
            }

            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(P.font);
            p.setTextSize(rad * 0.075f);
            p.setColor(P.dim);
            if (!P.glass) for (int v = 0; v <= max; v += 30) {
                double a = Math.toRadians(start + sweep * v / max);
                float rr = rad * 0.76f;
                c.drawText(String.valueOf(v), cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr + rad * 0.025f, p);
            }

            p.setTypeface(P.fontBold);
            p.setTextSize(rad * (P.glass ? 0.62f : 0.5f));
            p.setColor(P.text);
            String val = P.glass ? String.valueOf(Math.round(shown)) : String.format("%02d", Math.round(shown));
            c.drawText(val, cx, cy + rad * (P.glass ? 0.2f : 0.16f), p);
            p.setTypeface(P.font);
            p.setTextSize(rad * 0.1f);
            p.setColor(P.dim);
            c.drawText("km/h", cx, cy + rad * 0.4f, p);
            p.setTextSize(rad * 0.075f);
            c.drawText(sub, cx, cy + rad * 0.92f, p);
        }
    }

    /** Gráfico de línea (historial de velocidad). */
    public static class Spark extends View {
        private static final float[] data = new float[120]; // persiste al cambiar de tema
        private static int n = 0;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Spark(Context c) { super(c); }

        public static void push(float v) {
            System.arraycopy(data, 1, data, 0, data.length - 1);
            data[data.length - 1] = v;
            if (n < data.length) n++;
        }

        @Override
        protected void onDraw(Canvas c) {
            Theme.Pal P = Theme.P;
            float w = getWidth(), h = getHeight(), pad = 4;
            float max = 60;
            for (float v : data) if (v > max) max = v;
            max = (float) Math.ceil(max / 20) * 20;
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(P.track);
            for (int i = 0; i <= 2; i++) { float y = pad + (h - 2 * pad) * i / 2f; c.drawLine(0, y, w, y, p); }
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(P.font);
            p.setTextSize(11);
            p.setColor(P.dim);
            c.drawText(String.valueOf((int) max), 2, pad + 11, p);
            c.drawText("0", 2, h - pad - 2, p);
            if (n < 2) return;
            Path line = new Path(), fill = new Path();
            float step = w / (data.length - 1);
            for (int i = data.length - n; i < data.length; i++) {
                float x = i * step, y = h - pad - (h - 2 * pad) * data[i] / max;
                if (i == data.length - n) { line.moveTo(x, y); fill.moveTo(x, h); fill.lineTo(x, y); }
                else { line.lineTo(x, y); fill.lineTo(x, y); }
            }
            fill.lineTo(w, h); fill.close();
            int a = P.accent & 0x00FFFFFF;
            p.setShader(new LinearGradient(0, 0, 0, h, 0x55000000 | a, a, Shader.TileMode.CLAMP));
            c.drawPath(fill, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(P.accent);
            c.drawPath(line, p);
        }
    }

    // ===== Tema Cockpit (igual al de la interfaz web): colores cian/rosa =====
    static final int COCK_ACC = 0xFF00E5FF, COCK_ACC2 = 0xFFFF2D95, COCK_DIM = 0x99FFFFFF;
    /** Color de primer plano del Cockpit con la transparencia dada: blanco en oscuro, casi negro en claro. */
    static int fg(int alpha) { return (alpha << 24) | (Theme.P.dark ? 0xFFFFFF : 0x0B0C10); }
    /** false = sin animaciones (carretera quieta, sin estela ni halo, sin barrido). */
    public static boolean anim = true;

    /** Carretera neón en perspectiva; la cuadrícula corre más rápido cuanto mayor es la velocidad. */
    public static class CockpitRoad extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float shown = 0, target = 0, offset = 0;
        private long last = 0;

        public CockpitRoad(Context c) { super(c); }

        public void setSpeed(float kmh) { target = Math.max(0, kmh); if (last == 0) postInvalidateOnAnimation(); }

        @Override
        protected void onDraw(Canvas c) {
            long now = android.os.SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            shown += (target - shown) * Math.min(1f, dt * 3f);

            float w = getWidth(), h = getHeight();
            float horizon = h * 0.52f;
            // fondo
            p.setShader(new LinearGradient(0, 0, 0, h, Theme.P.dark ? new int[]{0xFF020306, 0xFF05060C, 0xFF0A0420}
                    : new int[]{0xFFF4F6FA, 0xFFE7ECF3, 0xFFDDE4EE}, null, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            // resplandor del horizonte
            p.setShader(new android.graphics.RadialGradient(w / 2f, horizon, w * 0.6f,
                    new int[]{0x5500E5FF, 0x00000000}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, horizon - h * 0.25f, w, horizon + h * 0.1f, p);
            p.setShader(null);

            float cxp = w / 2f;
            c.save();
            c.clipRect(0, horizon, w, h);
            // líneas verticales que convergen al punto de fuga
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            for (int i = -7; i <= 7; i++) {
                float bx = cxp + i * (w / 5f);
                p.setColor(Theme.P.dark ? 0x3300E5FF : 0x400077C8);
                c.drawLine(cxp, horizon, bx, h, p);
            }
            // líneas horizontales con perspectiva, desplazándose hacia el conductor
            if (anim) offset += (0.15f + shown / 90f) * dt;
            if (offset > 1f) offset -= 1f;
            for (int i = 0; i < 12; i++) {
                float t = (i + offset) / 12f;
                float yy = horizon + (h - horizon) * t * t;   // más juntas cerca del horizonte
                int alpha = (int) (0x66 * t);
                p.setColor((alpha << 24) | 0x00FF2D95);
                c.drawLine(0, yy, w, yy, p);
            }
            c.restore();
            // línea del horizonte brillante
            p.setStrokeWidth(3);
            p.setColor(COCK_ACC2);
            c.drawLine(0, horizon, w, horizon, p);

            if (anim && (Math.abs(target - shown) > 0.3f || shown > 0.3f)) postInvalidateOnAnimation();
        }
    }

    /** Brújula: rosa de los vientos que gira según el rumbo. */
    public static class Compass extends View {
        private float bearing = -1, shown = 0;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Compass(Context c) { super(c); }

        public void setBearing(float b) { bearing = b; postInvalidateOnAnimation(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f - dp(2);
            if (bearing >= 0) {
                float d = bearing - shown;
                while (d > 180) d -= 360; while (d < -180) d += 360;
                shown += d * 0.2f;
                if (Math.abs(d) > 0.5f) postInvalidateOnAnimation();
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(fg(0x33));
            c.drawCircle(cx, cy, r, p);
            c.save();
            c.rotate(-shown, cx, cy);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            String[] d = {"N", "E", "S", "O"};
            int[] col = {COCK_ACC2, fg(0xFF), fg(0xFF), fg(0xFF)};
            for (int i = 0; i < 4; i++) {
                double a = Math.toRadians(i * 90 - 90);
                float tx = cx + (float) Math.cos(a) * (r - dp(9)), ty = cy + (float) Math.sin(a) * (r - dp(9));
                p.setColor(col[i]);
                p.setTextSize(dp(10));
                c.drawText(d[i], tx, ty + dp(3.5f), p);
            }
            c.restore();
            // aguja fija arriba
            p.setColor(COCK_ACC);
            Path tri = new Path();
            tri.moveTo(cx, cy - r + dp(1)); tri.lineTo(cx - dp(5), cy - r + dp(10)); tri.lineTo(cx + dp(5), cy - r + dp(10)); tri.close();
            c.drawPath(tri, p);
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }

    /** Desenfoque de caja rápido sobre un bitmap chico (se usa una vez al abrir el cajón). */
    public static void boxBlur(android.graphics.Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int ra = 0, rr = 0, rg = 0, rb = 0, n = 0;
                for (int k = -r; k <= r; k++) { int xx = Math.max(0, Math.min(w - 1, x + k)); int c = px[y * w + xx];
                    ra += c >>> 24; rr += (c >> 16) & 255; rg += (c >> 8) & 255; rb += c & 255; n++; }
                tmp[y * w + x] = ((ra / n) << 24) | ((rr / n) << 16) | ((rg / n) << 8) | (rb / n);
            }
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int ra = 0, rr = 0, rg = 0, rb = 0, n = 0;
                for (int k = -r; k <= r; k++) { int yy = Math.max(0, Math.min(h - 1, y + k)); int c = tmp[yy * w + x];
                    ra += c >>> 24; rr += (c >> 16) & 255; rg += (c >> 8) & 255; rb += c & 255; n++; }
                px[y * w + x] = ((ra / n) << 24) | ((rr / n) << 16) | ((rg / n) << 8) | (rb / n);
            }
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    /**
     * Brillo automático: % para una hora del día (con fracción) a partir de los puntos "hora:%,...".
     * "steps" = escalones (cada punto rige hasta el siguiente); "curve" = arco suave (coseno) entre puntos,
     * con vuelta a medianoche. Nunca baja de 18 %.
     */
    public static int brightAt(String bands, String shape, float hour) { return Math.round(brightAtF(bands, shape, hour)); }

    /** Igual que brightAt pero sin redondear (para dibujar la curva sin dientes). */
    public static float brightAtF(String bands, String shape, float hour) {
        java.util.List<int[]> l = new java.util.ArrayList<int[]>();
        for (String s : bands.split(",")) {
            String[] hp = s.split(":");
            if (hp.length != 2) continue;
            try { l.add(new int[]{Integer.parseInt(hp[0].trim()), Integer.parseInt(hp[1].trim())}); } catch (Exception e) { }
        }
        if (l.isEmpty()) return -1f;
        java.util.Collections.sort(l, new java.util.Comparator<int[]>() { public int compare(int[] a, int[] b) { return a[0] - b[0]; } });
        if (l.size() == 1) return Math.max(18f, l.get(0)[1]);
        int[] prev = l.get(l.size() - 1), next = l.get(0);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i)[0] <= hour) { prev = l.get(i); next = l.get((i + 1) % l.size()); }
        }
        if (!"curve".equals(shape)) return Math.max(18f, prev[1]);
        float span = next[0] - prev[0];
        if (span <= 0) span += 24;
        float t = hour - prev[0];
        if (t < 0) t += 24;
        float f = Math.max(0, Math.min(1, t / span));
        float v = prev[1] + (next[1] - prev[1]) * (1 - (float) Math.cos(Math.PI * f)) / 2f;
        return Math.max(18f, v);
    }

    /** Barra de nivel (0..1) con pista redondeada y relleno en degradado. */
    public static class Bar extends View {
        private float frac = 0;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Bar(Context c) { super(c); }

        private float shownF = 0; private long pulseT = 0;
        public void setFraction(float f) {
            float n = Math.max(0, Math.min(1, f));
            if (Math.abs(n - frac) > 0.005f) pulseT = android.os.SystemClock.uptimeMillis();
            frac = n; if (!anim) shownF = frac; postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            // "barra viva": se llena con suavidad y brilla un momento al cambiar
            float real = frac;
            shownF += (real - shownF) * 0.12f;
            if (Math.abs(real - shownF) < 0.002f) shownF = real; else postInvalidateOnAnimation();
            long dt = android.os.SystemClock.uptimeMillis() - pulseT;
            float frac = shownF;
            p.setShader(null);
            p.setColor(fg(0x1A));
            c.drawRoundRect(0, 0, w, h, r, r, p);
            if (frac > 0.01f) {
                float fw = Math.max(h, w * frac);
                p.setShader(new LinearGradient(0, 0, fw, 0, COCK_ACC, COCK_ACC2, Shader.TileMode.CLAMP));
                c.drawRoundRect(0, 0, fw, h, r, r, p);
                p.setShader(null);
                if (anim && dt < 900) {
                    p.setColor(android.graphics.Color.argb((int) (120 * (1 - dt / 900f)), 255, 255, 255));
                    c.drawRoundRect(0, 0, fw, h, r, r, p);
                    postInvalidateOnAnimation();
                }
            }
        }
    }

    /** Línea de altitud (tarjeta Altitud del Cockpit). */
    public static class Line extends View {
        private static final float[] data = new float[90];
        private static int n = 0;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Line(Context c) { super(c); }
        public static void push(float v) {
            System.arraycopy(data, 1, data, 0, data.length - 1);
            data[data.length - 1] = v; if (n < data.length) n++;
        }
        @Override protected void onDraw(Canvas c) {
            if (n < 2) return;
            float w = getWidth(), h = getHeight(), lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = data.length - n; i < data.length; i++) { lo = Math.min(lo, data[i]); hi = Math.max(hi, data[i]); }
            if (hi - lo < 10) { float m = (hi + lo) / 2; lo = m - 5; hi = m + 5; }
            android.graphics.Path path = new android.graphics.Path();
            for (int i = 0; i < n; i++) {
                float x = w * i / (n - 1), y = h - 2 - (data[data.length - n + i] - lo) / (hi - lo) * (h - 4);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(COCK_ACC);
            c.drawPath(path, p);
            path.lineTo(w, h); path.lineTo(0, h); path.close();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, 0, h, (COCK_ACC & 0xFFFFFF) | 0x55000000, 0, Shader.TileMode.CLAMP));
            c.drawPath(path, p); p.setShader(null);
        }
    }

    /** Velocímetro del Cockpit: arco con degradado, marcas, números, aguja con halo y estela de velocidad. */
    public static class CockpitGauge extends View {
        private float target = 0, shown = 0, max = 180, scale = 0.88f;
        public void setScale(float sc) { scale = sc; invalidate(); }
        private String sub = "";
        private final float[] trail = new float[6];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private static final float START = 135, SWEEP = 270;

        public CockpitGauge(Context c) { super(c); }

        public void setMax(float m) { max = m; invalidate(); }

        /** Barrido de encendido (como un tablero real): la aguja sube al máximo y vuelve a la velocidad actual. */
        public void sweep() {
            if (!anim) return;
            pending = target;
            sweeping = true;
            target = max;
            postInvalidateOnAnimation();
            postDelayed(new Runnable() { public void run() { sweeping = false; target = pending; postInvalidateOnAnimation(); } }, 750);
        }
        private boolean sweeping = false;
        private float pending = 0;

        public void setSpeed(float kmh) {
            float v = Math.max(0, Math.min(max, kmh));
            if (sweeping) { pending = v; return; }
            target = v; postInvalidateOnAnimation();
        }
        public void setSub(String s) { sub = s; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            shown += (target - shown) * 0.18f;
            boolean moving = Math.abs(target - shown) > 0.15f;
            if (!moving) shown = target;
            System.arraycopy(trail, 1, trail, 0, trail.length - 1);
            trail[trail.length - 1] = shown;

            float w = getWidth(), h = getHeight();
            float rad = Math.min(w * 0.44f, h * 0.42f) * scale;
            float cx = w / 2f, cy = h * 0.5f;
            float frac = shown / max;
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);

            // arco de fondo
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(rad * 0.1f);
            p.setShader(null);
            p.setColor(fg(0x14));
            c.drawArc(r, START, SWEEP, false, p);

            // marcas
            p.setStrokeCap(Paint.Cap.BUTT);
            for (int v = 0; v <= max; v += 10) {
                boolean major = v % 20 == 0;
                double a = Math.toRadians(START + SWEEP * v / max);
                float r1 = rad * 0.88f, r2 = rad * (major ? 0.76f : 0.82f);
                p.setStrokeWidth(major ? 3 : 2);
                p.setColor(major ? fg(0xD8) : fg(0x66));
                c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                        cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, p);
            }
            // números
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(fg(0xB3));
            p.setTextSize(rad * 0.1f);
            for (int v = 0; v <= max; v += 20) {
                double a = Math.toRadians(START + SWEEP * v / max);
                float rr = rad * 0.62f;
                c.drawText(String.valueOf(v), cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr + rad * 0.035f, p);
            }

            // arco de valor con degradado cian→violeta→rosa
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            if (frac > 0.003f) {
                p.setShader(new android.graphics.SweepGradient(cx, cy,
                        new int[]{COCK_ACC, 0xFF7C4DFF, COCK_ACC2, COCK_ACC2}, new float[]{0, 0.4f, 0.75f, 1f}));
                // halo (estela de velocidad): más grueso y brillante cuanto más rápido
                float glow = anim ? 0.3f + 0.7f * frac : 0f;
                p.setStrokeWidth(rad * 0.1f + rad * 0.12f * glow);
                p.setAlpha((int) (70 * glow));
                c.drawArc(r, START, SWEEP * frac, false, p);
                p.setAlpha(255);
                p.setStrokeWidth(rad * 0.1f);
                c.drawArc(r, START, SWEEP * frac, false, p);
                p.setShader(null);
            }

            // aguja con estela (posiciones anteriores, desvaneciéndose) = sensación de velocidad
            for (int i = 0; anim && i < trail.length - 1; i++) {
                float a = START + SWEEP * (trail[i] / max);
                int al = (int) (90 * (i + 1f) / trail.length);
                drawNeedle(c, cx, cy, rad, a, (0x00FFFFFF & COCK_ACC2) | (al << 24), rad * 0.025f);
            }
            drawNeedle(c, cx, cy, rad, START + SWEEP * frac, COCK_ACC2, rad * 0.035f);
            // cubo central
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.P.dark ? 0xFF0B0D14 : 0xFFFFFFFF);
            c.drawCircle(cx, cy, rad * 0.08f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(COCK_ACC);
            c.drawCircle(cx, cy, rad * 0.08f, p);

            // lectura central
            p.setStyle(Paint.Style.FILL);
            p.setColor(fg(0xFF));
            p.setFakeBoldText(true);
            p.setTextSize(rad * 0.52f);
            c.drawText(String.valueOf(Math.round(shown)), cx, cy + rad * 0.68f, p);
            p.setFakeBoldText(false);
            p.setColor(fg(0x99));
            p.setTextSize(rad * 0.1f);
            c.drawText("KM/H", cx, cy + rad * 0.86f, p);
            if (sub.length() > 0) {
                p.setTextSize(rad * 0.085f);
                c.drawText(sub, cx, cy + rad * 1.04f, p);
            }

            if (moving) postInvalidateOnAnimation();
        }

        private void drawNeedle(Canvas c, float cx, float cy, float rad, float angle, int color, float baseW) {
            double a = Math.toRadians(angle);
            float tipX = cx + (float) Math.cos(a) * rad * 0.82f, tipY = cy + (float) Math.sin(a) * rad * 0.82f;
            double perp = a + Math.PI / 2;
            float bx = (float) Math.cos(perp) * baseW, by = (float) Math.sin(perp) * baseW;
            Path n = new Path();
            n.moveTo(cx + bx, cy + by);
            n.lineTo(tipX, tipY);
            n.lineTo(cx - bx, cy - by);
            n.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawPath(n, p);
        }
    }

    /** Fondo de pantalla dibujado (original): "ondas", "noche", "atardecer" o "rav4". */
    /** Dock vertical estilo macOS: al pasar el dedo los íconos cercanos se agrandan (efecto lupa). */
    public static class MacDock extends android.widget.LinearLayout {
        public MacDock(Context c) { super(c); setOrientation(VERTICAL); setClipChildren(false); setClipToPadding(false); }
        private void magnify(float y) {
            float reach = 110 * getResources().getDisplayMetrics().density;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (!"icon".equals(v.getTag())) continue;
                float cy = v.getTop() + v.getHeight() / 2f, d = Math.abs(y - cy);
                float s = 1f + 0.55f * Math.max(0f, 1f - d / reach);
                v.setPivotX(0); v.setPivotY(v.getHeight() / 2f);
                v.setScaleX(s); v.setScaleY(s);
            }
        }
        private void relax() {
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).animate().scaleX(1f).scaleY(1f).setDuration(220).start();
        }
        @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (!f) relax(); }
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
            int a = e.getActionMasked();
            if (anim && (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_MOVE)) magnify(e.getY());
            if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) relax();
            return super.dispatchTouchEvent(e);
        }
    }

    /** Fondo propio inspirado en los fondos de iPhone/CarPlay: manchas de color grandes y difusas (no es una imagen de Apple). */
    public static class IosWall extends View {
        private static android.graphics.Bitmap cache; private static boolean cacheDark;
        public IosWall(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            boolean dark = Theme.P.dark;
            if (cache == null || cacheDark != dark) {
                int bw = 160, bh = Math.max(1, 160 * h / w);
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
                Canvas k = new Canvas(b);
                k.drawColor(dark ? 0xFF05060F : 0xFFF3F1FA);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                int[][] blobs = dark
                        ? new int[][]{{0xFF1E3AFF, 20, 20, 90}, {0xFF8A2BE2, 120, 15, 80}, {0xFFFF4F7B, 140, 95, 70}, {0xFF00B3A4, 30, 100, 75}, {0xFFFF9F0A, 85, 70, 40}}
                        : new int[][]{{0xFF8FB4FF, 20, 20, 90}, {0xFFD7B4FF, 120, 15, 80}, {0xFFFFB3C7, 140, 95, 70}, {0xFF9FE8DC, 30, 100, 75}, {0xFFFFD99A, 85, 70, 40}};
                for (int[] bl : blobs) {
                    float x = bl[1] * bw / 160f, y = bl[2] * bh / 100f, r = bl[3] * bw / 160f;
                    p.setShader(new android.graphics.RadialGradient(x, y, r, bl[0], bl[0] & 0x00FFFFFF, Shader.TileMode.CLAMP));
                    k.drawCircle(x, y, r, p);
                }
                p.setShader(null);
                p.setColor(dark ? 0x40000000 : 0x26FFFFFF);
                k.drawRect(0, 0, bw, bh, p);
                cache = b; cacheDark = dark;
            }
            Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(cache, null, new RectF(0, 0, w, h), fp);
        }
    }

    public static class Wallpaper extends View {
        private final String style;
        private final android.graphics.Bitmap photo;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Wallpaper(Context c, String style, android.graphics.Bitmap photo) {
            super(c);
            this.style = style;
            this.photo = photo;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            boolean dark = Theme.P.dark;
            p.setShader(null);
            if ("rav4".equals(style) && photo != null) {
                c.drawColor(0xFF000000);
                float s = Math.max(w / photo.getWidth(), h / photo.getHeight()) * 0.85f;
                float bw = photo.getWidth() * s, bh = photo.getHeight() * s;
                c.drawBitmap(photo, null, new RectF((w - bw) / 2, h - bh, (w + bw) / 2, h), p);
                p.setShader(new LinearGradient(0, 0, 0, h, dark ? 0xEE000000 : 0x99FFFFFF, 0x33000000, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                return;
            }
            if ("noche".equals(style)) {
                p.setShader(new LinearGradient(0, 0, w, h, dark ? new int[]{0xFF0B132B, 0xFF1C2541, 0xFF000000}
                        : new int[]{0xFFDDE7F7, 0xFFC4D3EE, 0xFFF5F7FB}, null, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                return;
            }
            if ("atardecer".equals(style)) {
                p.setShader(new LinearGradient(0, 0, 0, h, dark ? new int[]{0xFFE58FA6, 0xFF8E5C9E, 0xFF231A3A}
                        : new int[]{0xFFFFC9A8, 0xFFF6B9C9, 0xFFB9D4F5}, null, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                return;
            }
            // "ondas": base oscura con dos grandes arcos de color
            c.drawColor(dark ? 0xFF05080F : 0xFFEAF1F8);
            p.setShader(new LinearGradient(0, 0, w * 0.7f, h, dark ? 0xFF0E6B5C : 0xFF9FE3D3, dark ? 0xFF062A33 : 0xFFD4F1EA, Shader.TileMode.CLAMP));
            c.drawCircle(w * 0.25f, -h * 0.55f, h * 1.35f, p);
            p.setShader(new LinearGradient(w, h, w * 0.4f, 0, dark ? 0xFF1F4FD1 : 0xFF8FB0F5, dark ? 0xFF0A1C55 : 0xFFD6E2FB, Shader.TileMode.CLAMP));
            c.drawCircle(w * 1.05f, h * 1.25f, h * 0.95f, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(dark ? 0x553D8BFF : 0x553D8BFF);
            c.drawCircle(w * 1.05f, h * 1.25f, h * 0.97f, p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
