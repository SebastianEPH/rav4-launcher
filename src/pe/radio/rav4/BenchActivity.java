package pe.radio.rav4;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import pe.radio.bench.BenchProbe;

/**
 * Prueba de rendimiento: la misma escena Cockpit en Nativo (vistas del launcher) o Web (la página real del launcher
 * con ?bench=1). Corre en su propio proceso (:bench_nativo / :bench_web) para que el arranque se mida en frío, igual
 * que las apps de React Native y Flutter. Escena común: aguja = 90·(1 − cos(2πt/10 s)) km/h, rumbo = 12°/s.
 */
public class BenchActivity extends Activity {
    public static class Web extends BenchActivity { @Override boolean web() { return true; } }
    boolean web() { return false; }

    private final BenchProbe probe = BenchProbe.get();
    private long t0;
    private double dist = 0;
    private long lastMs = 0;
    private boolean hot = false, touchPending = false;
    private Views.CockpitRoad road;
    private Views.CockpitGauge gauge;
    private Views.Compass compass;
    private TextView distTxt, battTxt, gpsTxt, gpsSub, musicTitle, status, result;
    private View battBar, musicBar;
    private TextView btn;
    private WebView wv;

    static double kmhAt(long ms) { return 90 * (1 - Math.cos(2 * Math.PI * ms / 10000.0)); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        t0 = SystemClock.uptimeMillis();
        if (web()) buildWeb(); else buildNative();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) probe.touchDown(ev.getEventTime());
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() { close(); }

    private void close() {
        finish();
        // proceso propio: se cierra para que la próxima prueba vuelva a arrancar en frío
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // ================= Web =================
    private void buildWeb() {
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        wv.setBackgroundColor(0xFF03040A);
        wv.addJavascriptInterface(new Object() {
            @JavascriptInterface public void firstFrame() { probe.firstFrame(); }
            @JavascriptInterface public void frame(double ms) { probe.frame(ms); }
            @JavascriptInterface public void touchShown() { probe.touchShown(); }
            @JavascriptInterface public void start() { probe.start(false); }   // cuadros: los cuenta requestAnimationFrame
            @JavascriptInterface public String finish() {
                return probe.finish(BenchActivity.this, "web", "nota=fps = requestAnimationFrame de la página real del launcher (tema Cockpit, efectos Altos)\nwebview="
                        + (android.os.Build.VERSION.SDK_INT >= 26 && WebView.getCurrentWebViewPackage() != null ? WebView.getCurrentWebViewPackage().versionName : "?"));
            }
            @JavascriptInterface public void close() { runOnUiThread(new Runnable() { public void run() { BenchActivity.this.close(); } }); }
        }, "Bench");
        setContentView(wv);
        wv.loadUrl("file:///android_asset/web/index.html?bench=1&theme=cockpit&fx=high&mode=dark");
    }

    // ================= Nativo =================
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xB80C101C); g.setCornerRadius(dp(22)); g.setStroke(1, 0x14FFFFFF);
        c.setBackground(g);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        return c;
    }
    private TextView tv(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }
    private View bar(LinearLayout parent, int color) {
        FrameLayout track = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(); g.setColor(0x1AFFFFFF); g.setCornerRadius(dp(4));
        track.setBackground(g);
        View in = new View(this);
        GradientDrawable gi = new GradientDrawable(); gi.setColor(color); gi.setCornerRadius(dp(4));
        in.setBackground(gi);
        track.addView(in, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(8)); lp.topMargin = dp(10);
        parent.addView(track, lp);
        return in;
    }

    private void buildNative() {
        Views.anim = true;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF03040A);
        road = new Views.CockpitRoad(this);
        root.addView(road, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(24), dp(70), dp(24), dp(24));
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));

        // velocímetro (lado del conductor)
        FrameLayout g = new FrameLayout(this);
        LinearLayout gc = card(); gc.setGravity(Gravity.CENTER);
        gauge = new Views.CockpitGauge(this);
        gauge.setMax(180);
        g.addView(gauge, new FrameLayout.LayoutParams(dp(520), dp(520), Gravity.CENTER));
        gc.addView(g, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(560), -1); glp.rightMargin = dp(18); glp.bottomMargin = dp(14);
        row.addView(gc, glp);
        gc.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    hot = !hot;
                    gauge.setSub(hot ? "● TOQUE" : "");
                    ((GradientDrawable) gc.getBackground()).setStroke(dp(2), hot ? 0xFF00E5FF : 0x14FFFFFF);
                    touchPending = true;
                }
                return true;
            }
        });

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        row.addView(side, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout trip = card();
        FrameLayout tripWrap = new FrameLayout(this);
        LinearLayout tripText = new LinearLayout(this); tripText.setOrientation(LinearLayout.VERTICAL);
        tripText.addView(tv("VIAJE", 13, 0x8CFFFFFF, false));
        distTxt = tv("0.00 km", 44, Color.WHITE, true); tripText.addView(distTxt);
        tripText.addView(tv("En marcha · prom. 72 · máx. 180", 15, 0x8CFFFFFF, false));
        tripWrap.addView(tripText);
        compass = new Views.Compass(this);
        tripWrap.addView(compass, new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.END | Gravity.TOP));
        trip.addView(tripWrap);
        side.addView(trip, mb());

        LinearLayout two = new LinearLayout(this);
        LinearLayout batt = card();
        batt.addView(tv("BATERÍA", 13, 0x8CFFFFFF, false));
        battTxt = tv("13.9 V", 44, Color.WHITE, true); batt.addView(battTxt);
        battBar = bar(batt, 0xFF00E5FF);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1f); bl.rightMargin = dp(14);
        two.addView(batt, bl);
        LinearLayout gps = card();
        gps.addView(tv("GPS", 13, 0x8CFFFFFF, false));
        gpsTxt = tv("9 sats", 44, Color.WHITE, true); gps.addView(gpsTxt);
        gpsSub = tv("", 15, 0x8CFFFFFF, false); gps.addView(gpsSub);
        two.addView(gps, new LinearLayout.LayoutParams(0, -2, 1f));
        side.addView(two, mb());

        LinearLayout music = card();
        musicTitle = tv("MÚSICA", 13, 0x8CFFFFFF, false); music.addView(musicTitle);
        music.addView(tv("Canción de prueba", 22, Color.WHITE, true));
        musicBar = bar(music, 0xFFFF2D95);
        side.addView(music, mb());

        // barra superior: título + botón / cuenta regresiva
        FrameLayout top = new FrameLayout(this);
        top.setPadding(dp(24), dp(14), dp(24), 0);
        top.addView(tv("Nativo · Java + Canvas", 16, 0xB3FFFFFF, false), new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        btn = tv("Iniciar prueba (30 s)", 17, 0xFF001018, true);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFF00E5FF); bg.setCornerRadius(dp(14));
        btn.setBackground(bg); btn.setPadding(dp(22), dp(10), dp(22), dp(10));
        btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startTest(); } });
        top.addView(btn, new FrameLayout.LayoutParams(-2, -2, Gravity.END));
        status = tv("", 18, 0xFF00E5FF, true);
        top.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        root.addView(top, new FrameLayout.LayoutParams(-1, dp(60), Gravity.TOP));

        result = tv("", 19, Color.WHITE, false);
        result.setLineSpacing(0, 1.3f);
        GradientDrawable rb = new GradientDrawable(); rb.setColor(0xF5080A12); rb.setCornerRadius(dp(22)); rb.setStroke(dp(1), 0xFF00E5FF);
        result.setBackground(rb); result.setPadding(dp(28), dp(28), dp(28), dp(28));
        result.setVisibility(View.GONE);
        result.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { result.setVisibility(View.GONE); } });
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        rl.leftMargin = rl.rightMargin = dp(300); rl.topMargin = dp(160);
        root.addView(result, rl);

        setContentView(root);
        Choreographer.getInstance().postFrameCallback(loop);
    }

    private LinearLayout.LayoutParams mb() { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.bottomMargin = dp(14); return l; }

    private boolean first = true;
    private final Choreographer.FrameCallback loop = new Choreographer.FrameCallback() {
        public void doFrame(long ns) {
            long ms = SystemClock.uptimeMillis() - t0;
            double v = kmhAt(ms);
            if (lastMs != 0) dist += v / 3600.0 * ((ms - lastMs) / 1000.0);
            lastMs = ms;
            gauge.setSpeed((float) v);
            road.setSpeed((float) v);
            compass.setBearing((ms * 0.012f) % 360);
            double s = Math.sin(ms / 1500.0);
            distTxt.setText(String.format(Locale.US, "%.2f km", dist));
            battTxt.setText(String.format(Locale.US, "%.1f V", 13.6 + 0.3 * s));
            setW(battBar, (float) (0.6 + 0.2 * s));
            gpsTxt.setText((9 + Math.round(2 * Math.sin(ms / 4000.0))) + " sats");
            gpsSub.setText(Math.round(150 + 20 * Math.sin(ms / 3000.0)) + " m · " + Math.round((ms * 0.012) % 360) + "°");
            java.util.Calendar c = java.util.Calendar.getInstance();
            musicTitle.setText(String.format(Locale.US, "MÚSICA · %d:%02d:%02d %s", (c.get(java.util.Calendar.HOUR_OF_DAY) + 11) % 12 + 1,
                    c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND), c.get(java.util.Calendar.HOUR_OF_DAY) < 12 ? "AM" : "PM"));
            setW(musicBar, (ms / 300f % 100) / 100f);
            if (touchPending) { touchPending = false; probe.touchShown(); }
            if (first) { first = false; probe.firstFrame(); }
            if (probe.running()) {
                int left = probe.remainingMs();
                status.setText("Midiendo… " + (left + 999) / 1000 + " s · toca el velocímetro varias veces");
                if (left == 0) endTest();
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void setW(View in, float f) {
        View track = (View) in.getParent();
        int w = Math.round(track.getWidth() * Math.max(0, Math.min(1, f)));
        ViewGroup.LayoutParams lp = in.getLayoutParams();
        if (lp.width != w) { lp.width = w; in.setLayoutParams(lp); }
    }

    private void startTest() {
        result.setVisibility(View.GONE);
        btn.setVisibility(View.GONE);
        probe.start(true);
    }

    private void endTest() {
        String r = probe.finish(this, "nativo", "nota=fps = Choreographer (hilo de la interfaz), mismas vistas que el tema Cockpit nativo");
        status.setText("");
        btn.setText("Repetir prueba (30 s)");
        btn.setVisibility(View.VISIBLE);
        result.setText(r + "\n\nToca para cerrar");
        result.setVisibility(View.VISIBLE);
    }
}
