package pe.radio.rav4;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Interfaz HTML/CSS/JS (assets/web) con un puente "Radio" hacia los datos y controles reales de la radio. */
public class WebHome {

    private static final String PAGE = "file:///android_asset/web/index.html";

    private final HomeActivity a;
    private final WebView web;
    private final android.widget.FrameLayout root;
    private View splash;
    private volatile boolean ready = false;
    private CanBridge can;   // se conecta recién cuando la página lo pide (tema Auto · CAN o grabación)
    // Lista de apps armada en segundo plano; los íconos se sirven aparte (ver shouldInterceptRequest).
    private volatile String appsJson = "[]";
    private final Map<String, ResolveInfo> byKey = new ConcurrentHashMap<String, ResolveInfo>();
    private static final String ICON_URL = "https://rav4.local/icon/";
    // Tiempos de arranque (ms) para mostrarlos en el Panel y poder optimizar con datos reales.
    private final long tCreate = android.os.SystemClock.elapsedRealtime();
    private volatile long tReady = 0, tApps = 0;
    // El estado se arma en un hilo aparte y la página solo lee la última copia: así JS nunca queda
    // esperando a Android (antes cada consulta bloqueaba la página y los toques tardaban segundos).
    private volatile String stateJson = "{}", canJson = "{}";
    private final android.os.HandlerThread bg = new android.os.HandlerThread("rav4-estado");
    private android.os.Handler bgH;
    private final Runnable refresh = new Runnable() {
        public void run() {
            try { stateJson = a.webState().toString(); } catch (Exception e) { }
            if (can != null) try { canJson = can.state().toString(); } catch (Exception e) { }
            bgH.postDelayed(this, 500);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public WebHome(HomeActivity activity) {
        a = activity;
        web = new WebView(a);
        web.setBackgroundColor(0xFF05070C);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // Solo páginas propias del APK: el puente "Radio" nunca debe quedar expuesto a una web externa.
        web.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView v, String url) { return !url.startsWith("file:///android_asset/"); }
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return shouldOverrideUrlLoading(v, r.getUrl().toString()); }
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return intercept(r.getUrl().toString()); }
            public WebResourceResponse shouldInterceptRequest(WebView v, String url) { return intercept(url); }
        });
        // Pantalla nativa inmediata (hora sobre el fondo del tema) mientras el WebView arranca: nada de negro.
        root = new android.widget.FrameLayout(a);
        root.setBackgroundColor(0xFF05070C);
        root.addView(web);
        splash = buildSplash();
        root.addView(splash);
        bg.start();
        bgH = new android.os.Handler(bg.getLooper());
        bgH.post(loadApps);
        bgH.post(refresh);
        web.addJavascriptInterface(new Bridge(), "Radio");
        web.loadUrl(PAGE);
        // Red de seguridad: si el WebView de la radio no puede correr la página, no dejar la pantalla en negro.
        web.postDelayed(new Runnable() {
            public void run() {
                if (ready) return;
                android.widget.Toast.makeText(a, "La interfaz web no arrancó en esta radio; vuelvo a la nativa.", android.widget.Toast.LENGTH_LONG).show();
                a.webUseNative();
            }
        }, 8000);
    }

    public View view() { return root; }

    private View buildSplash() {
        android.widget.LinearLayout l = new android.widget.LinearLayout(a);
        l.setOrientation(android.widget.LinearLayout.VERTICAL);
        l.setGravity(android.view.Gravity.CENTER);
        l.setBackgroundColor(0xFF05070C);
        java.util.Date now = new java.util.Date();
        boolean h24 = "24".equals(a.webPrefs().getString("web.clock", "12"));
        android.widget.TextView t = new android.widget.TextView(a);
        t.setText(new java.text.SimpleDateFormat(h24 ? "HH:mm" : "h:mm", java.util.Locale.US).format(now)
                + (h24 ? "" : " " + new java.text.SimpleDateFormat("a", java.util.Locale.US).format(now)));
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(96);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL));
        l.addView(t);
        android.widget.TextView d = new android.widget.TextView(a);
        d.setText(new java.text.SimpleDateFormat("EEEE d 'de' MMMM", new java.util.Locale("es")).format(now));
        d.setTextColor(0x99FFFFFF);
        d.setTextSize(22);
        l.addView(d);
        return l;
    }

    private void hideSplash() {
        a.runOnUiThread(new Runnable() {
            public void run() {
                if (splash == null) return;
                final View s = splash;
                splash = null;
                s.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() { public void run() { root.removeView(s); } }).start();
            }
        });
    }

    private CanBridge can() {
        if (can == null) { can = new CanBridge(a); can.start(); }
        return can;
    }

    /** Arma la lista de apps (solo nombres; los íconos se piden aparte). Corre en segundo plano. */
    private final Runnable loadApps = new Runnable() {
        public void run() {
            JSONArray out = new JSONArray();
            try {
                PackageManager pm = a.getPackageManager();
                for (ResolveInfo r : a.webLaunchables()) {
                    String k = HomeActivity.keyOf(r);
                    byKey.put(k, r);
                    long ver = 0;
                    try { ver = pm.getPackageInfo(r.activityInfo.packageName, 0).lastUpdateTime; } catch (Exception e) { }
                    JSONObject o = new JSONObject();
                    o.put("key", k);
                    o.put("pkg", r.activityInfo.packageName);
                    o.put("label", String.valueOf(r.loadLabel(pm)));
                    o.put("icon", ICON_URL + Uri.encode(k) + "?v=" + ver);
                    o.put("hidden", a.webIsHidden(k));
                    o.put("pinned", a.webIsPinned(k));
                    out.put(o);
                }
            } catch (Exception e) { }
            appsJson = out.toString();
            if (tApps == 0) tApps = android.os.SystemClock.elapsedRealtime();
            js("RAV4.appsChanged()");
        }
    };

    /** Íconos: se dibujan una vez, se guardan en disco (cache/icons) y el WebView los pide en paralelo. */
    private WebResourceResponse intercept(String url) {
        if (url == null || !url.startsWith(ICON_URL)) return null;
        try {
            String rest = url.substring(ICON_URL.length());
            int q = rest.indexOf('?');
            String key = Uri.decode(q >= 0 ? rest.substring(0, q) : rest);
            String ver = q >= 0 ? rest.substring(q + 3) : "0";
            File dir = new File(a.getCacheDir(), "icons");
            dir.mkdirs();
            File f = new File(dir, Integer.toHexString((key + ver).hashCode()) + ".png");
            byte[] png;
            if (f.length() > 0) {
                FileInputStream in = new FileInputStream(f);
                png = new byte[(int) f.length()];
                int off = 0, n;
                while (off < png.length && (n = in.read(png, off, png.length - off)) > 0) off += n;
                in.close();
            } else {
                ResolveInfo r = byKey.get(key);
                if (r == null) return null;
                Drawable d = r.loadIcon(a.getPackageManager());
                Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, 128, 128);
                d.draw(new Canvas(b));
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.PNG, 100, bo);
                png = bo.toByteArray();
                FileOutputStream o = new FileOutputStream(f);
                o.write(png);
                o.close();
            }
            WebResourceResponse res = new WebResourceResponse("image/png", null, new ByteArrayInputStream(png));
            Map<String, String> h = new HashMap<String, String>();
            h.put("Cache-Control", "max-age=31536000");
            h.put("Access-Control-Allow-Origin", "*");
            res.setResponseHeaders(h);
            return res;
        } catch (Exception e) { return null; }
    }

    public void js(final String code) {
        a.runOnUiThread(new Runnable() { public void run() { web.evaluateJavascript("window.RAV4&&" + code, null); } });
    }

    /** Se instaló, desinstaló, ocultó o fijó una app: rehacer la lista en segundo plano. */
    public void appsChanged() { bgH.post(loadApps); }

    public void destroy() {
        bgH.removeCallbacks(refresh);
        bg.quitSafely();
        if (can != null) can.stop();
        web.removeJavascriptInterface("Radio");
        web.destroy();
    }

    private void ui(Runnable r) { a.runOnUiThread(r); }

    private ResolveInfo find(String key) {
        ResolveInfo r = byKey.get(key);
        if (r != null) return r;
        for (ResolveInfo x : a.webLaunchables()) if (HomeActivity.keyOf(x).equals(key)) return x;
        return null;
    }

    private boolean launchPackage(String pkg) {
        Intent i = a.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    private void sys(String action) {
        try { a.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { }
    }

    /** Todo lo que la página puede pedirle a la radio. Se llama desde el hilo del WebView. */
    private class Bridge {

        @JavascriptInterface
        public void ready() { ready = true; if (tReady == 0) tReady = android.os.SystemClock.elapsedRealtime(); hideSplash(); }

        @JavascriptInterface
        public String boot() {
            long proc = Build.VERSION.SDK_INT >= 24 ? tCreate - android.os.Process.getStartElapsedRealtime() : -1;
            return "{\"proc\":" + proc + ",\"page\":" + (tReady > 0 ? tReady - tCreate : -1) + ",\"apps\":" + (tApps > 0 ? tApps - tCreate : -1) + "}";
        }

        @JavascriptInterface
        public String canRecord(boolean on) {
            if (!on) return WebHome.this.can().stopRecording();
            if (!a.webHasStorage()) { ui(new Runnable() { public void run() { a.webAskStorage(); } }); return "PERMISO"; }
            return WebHome.this.can().startRecording("Launcher " + Updater.currentVersion(a) + " · Android " + Build.VERSION.RELEASE + " · " + Build.MODEL
                    + " · " + new java.util.Date());
        }

        @JavascriptInterface
        public void canMark(String text) { WebHome.this.can().mark(text); }

        @JavascriptInterface
        public String perms() { try { return a.webPerms().toString(); } catch (Exception e) { return "[]"; } }

        @JavascriptInterface
        public void grant(final String id) {
            ui(new Runnable() { public void run() { if ("all".equals(id)) a.startPermWizard(); else a.permRequest(id); } });
        }

        @JavascriptInterface
        public String can() {
            WebHome.this.can();
            return canJson;
        }

        @JavascriptInterface
        public String state() {
            return stateJson;
        }

        @JavascriptInterface
        public String apps() { return appsJson; }

        @JavascriptInterface
        public void launch(final String key) {
            ui(new Runnable() { public void run() { ResolveInfo r = find(key); if (r != null) a.webStart(r); } });
        }

        @JavascriptInterface
        public void appMenu(final String key) {
            ui(new Runnable() { public void run() { ResolveInfo r = find(key); if (r != null) a.webAppMenu(r); } });
        }

        @JavascriptInterface
        public void setVolume(final int v) { ui(new Runnable() { public void run() { a.webSetVolume(v); } }); }

        @JavascriptInterface
        public void mute() { ui(new Runnable() { public void run() { a.webMute(); } }); }

        @JavascriptInterface
        public void setBrightness(final int pct) { ui(new Runnable() { public void run() { a.webSetBrightness(pct); } }); }

        @JavascriptInterface
        public void media(final String what) {
            final int code = "next".equals(what) ? KeyEvent.KEYCODE_MEDIA_NEXT
                    : "prev".equals(what) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            ui(new Runnable() {
                public void run() {
                    android.media.AudioManager am = (android.media.AudioManager) a.getSystemService(HomeActivity.AUDIO_SERVICE);
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                }
            });
        }

        @JavascriptInterface
        public void open(final String what) {
            ui(new Runnable() {
                public void run() {
                    if ("android".equals(what)) sys("android.settings.SETTINGS");
                    else if ("home".equals(what)) sys("android.settings.HOME_SETTINGS");
                    else if ("car".equals(what)) { if (!launchPackage("com.nwd.can.setting")) sys("android.settings.SETTINGS"); }
                    else if ("phone".equals(what)) { if (!launchPackage("com.zjinnova.zlink")) sys("android.settings.SETTINGS"); }
                    else if ("wifi".equals(what)) sys("android.settings.WIFI_SETTINGS");
                    else if ("bt".equals(what)) sys("android.settings.BLUETOOTH_SETTINGS");
                }
            });
        }

        @JavascriptInterface
        public void toggle(final String what) {
            ui(new Runnable() {
                public void run() {
                    try {
                        if ("wifi".equals(what)) {
                            WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(HomeActivity.WIFI_SERVICE);
                            if (!wm.setWifiEnabled(!wm.isWifiEnabled())) sys("android.settings.WIFI_SETTINGS");
                        } else if ("bt".equals(what)) {
                            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                            if (!(bt.isEnabled() ? bt.disable() : bt.enable())) sys("android.settings.BLUETOOTH_SETTINGS");
                        }
                    } catch (Exception e) { sys("wifi".equals(what) ? "android.settings.WIFI_SETTINGS" : "android.settings.BLUETOOTH_SETTINGS"); }
                }
            });
        }

        @JavascriptInterface
        public String pref(String key, String def) { return a.webPrefs().getString("web." + key, def); }

        @JavascriptInterface
        public void setPref(String key, String val) { a.webPrefs().edit().putString("web." + key, val).apply(); }

        @JavascriptInterface
        public void resetTrip() { ui(new Runnable() { public void run() { a.webResetTrip(); } }); }

        @JavascriptInterface
        public void checkUpdate() { ui(new Runnable() { public void run() { a.webCheckUpdate(); } }); }

        @JavascriptInterface
        public void useNative() { ui(new Runnable() { public void run() { a.webUseNative(); } }); }

        @JavascriptInterface
        public String version() { return Updater.currentVersion(a) + " · Android " + Build.VERSION.RELEASE; }

        @JavascriptInterface
        public void permission(final String what) {
            ui(new Runnable() {
                public void run() {
                    if ("brightness".equals(what) && Build.VERSION.SDK_INT >= 23)
                        try {
                            a.startActivity(new Intent("android.settings.action.MANAGE_WRITE_SETTINGS",
                                    Uri.parse("package:" + a.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception e) { }
                }
            });
        }
    }
}
