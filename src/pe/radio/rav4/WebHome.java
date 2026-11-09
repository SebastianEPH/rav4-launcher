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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Interfaz HTML/CSS/JS (assets/web) con un puente "Radio" hacia los datos y controles reales de la radio. */
public class WebHome {

    private static final String PAGE = "file:///android_asset/web/index.html";

    private final HomeActivity a;
    private final WebView web;
    private final Map<String, String> iconCache = new HashMap<String, String>();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public WebHome(HomeActivity activity) {
        a = activity;
        web = new WebView(a);
        web.setBackgroundColor(0xFF000000);
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
        });
        web.addJavascriptInterface(new Bridge(), "Radio");
        web.loadUrl(PAGE);
    }

    public View view() { return web; }

    public void js(final String code) {
        a.runOnUiThread(new Runnable() { public void run() { web.evaluateJavascript("window.RAV4&&" + code, null); } });
    }

    public void appsChanged() { js("RAV4.appsChanged()"); }

    public void destroy() {
        web.removeJavascriptInterface("Radio");
        web.destroy();
    }

    private void ui(Runnable r) { a.runOnUiThread(r); }

    private String iconData(ResolveInfo r) {
        String k = HomeActivity.keyOf(r);
        String c = iconCache.get(k);
        if (c != null) return c;
        try {
            Drawable d = r.loadIcon(a.getPackageManager());
            int px = 128;
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, px, px);
            d.draw(new Canvas(b));
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            c = "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { c = ""; }
        iconCache.put(k, c);
        return c;
    }

    private ResolveInfo find(String key) {
        for (ResolveInfo r : a.webLaunchables()) if (HomeActivity.keyOf(r).equals(key)) return r;
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
        public String state() {
            try { return a.webState().toString(); } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public String apps() {
            JSONArray out = new JSONArray();
            try {
                PackageManager pm = a.getPackageManager();
                List<ResolveInfo> all = a.webLaunchables();
                for (ResolveInfo r : all) {
                    String k = HomeActivity.keyOf(r);
                    JSONObject o = new JSONObject();
                    o.put("key", k);
                    o.put("pkg", r.activityInfo.packageName);
                    o.put("label", String.valueOf(r.loadLabel(pm)));
                    o.put("icon", iconData(r));
                    o.put("hidden", a.webIsHidden(k));
                    o.put("pinned", a.webIsPinned(k));
                    out.put(o);
                }
            } catch (Exception e) { }
            return out.toString();
        }

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
