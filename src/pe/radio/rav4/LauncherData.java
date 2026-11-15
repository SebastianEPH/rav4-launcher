package pe.radio.rav4;

import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Datos y acciones comunes de las interfaces Compose, React Native y Flutter (la web usa WebHome, que hace lo mismo).
 * Igual que en la web: el estado se arma en un hilo aparte cada 500 ms y la interfaz solo lee la última copia,
 * así nunca espera a Android. Los íconos se guardan como PNG en cache/icons y se pasan como ruta de archivo.
 */
public class LauncherData {
    public interface Listener { void appsChanged(); }

    private final HomeActivity a;
    private final HandlerThread bg = new HandlerThread("orbita-datos");
    private final Handler bgH, ui = new Handler(Looper.getMainLooper());
    private final Map<String, ResolveInfo> byKey = new HashMap<String, ResolveInfo>();
    private volatile String state = "{}", apps = "[]";
    private Listener listener;
    private boolean alive = true;

    public LauncherData(HomeActivity activity) {
        a = activity;
        bg.start();
        bgH = new Handler(bg.getLooper());
        bgH.post(refresh);
        bgH.post(loadApps);
    }

    public void setListener(Listener l) { listener = l; }

    /** Último estado (JSON): kmh, bearing, heading, alt, satsUsed, satsSeen, fix, distKm, avgKmh, maxKmh, batteryV, vol, volMax, wifi, bt… */
    public String state() { return state; }

    /** Apps (JSON): [{key, label, icon (ruta PNG), hidden, pinned}] */
    public String apps() { return apps; }

    public void reloadApps() { bgH.post(loadApps); }

    public void destroy() { alive = false; bg.quitSafely(); }

    private final Runnable refresh = new Runnable() {
        public void run() {
            if (!alive) return;
            try { state = a.webState().toString(); } catch (Exception ignored) { }
            bgH.postDelayed(this, 500);
        }
    };

    private final Runnable loadApps = new Runnable() {
        public void run() {
            JSONArray out = new JSONArray();
            try {
                PackageManager pm = a.getPackageManager();
                File dir = new File(a.getCacheDir(), "icons");
                dir.mkdirs();
                for (ResolveInfo r : a.webLaunchables()) {
                    String k = HomeActivity.keyOf(r);
                    synchronized (byKey) { byKey.put(k, r); }
                    long ver = 0;
                    try { ver = pm.getPackageInfo(r.activityInfo.packageName, 0).lastUpdateTime; } catch (Exception ignored) { }
                    File f = new File(dir, Integer.toHexString((k + ver).hashCode()) + ".png");
                    if (f.length() == 0) {
                        // el ícono real, tal cual (sin placa ni contorno)
                        Drawable d = a.webIcon(r);
                        Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                        d.setBounds(0, 0, 128, 128);
                        d.draw(new Canvas(b));
                        FileOutputStream o = new FileOutputStream(f);
                        b.compress(Bitmap.CompressFormat.PNG, 100, o);
                        o.close();
                    }
                    JSONObject o = new JSONObject();
                    o.put("key", k);
                    o.put("label", String.valueOf(r.loadLabel(pm)));
                    o.put("icon", f.getAbsolutePath());
                    o.put("hidden", a.webIsHidden(k));
                    o.put("pinned", a.webIsPinned(k));
                    out.put(o);
                }
            } catch (Exception ignored) { }
            apps = out.toString();
            ui.post(new Runnable() { public void run() { if (listener != null) listener.appsChanged(); } });
        }
    };

    private ResolveInfo find(String key) { synchronized (byKey) { return byKey.get(key); } }

    // ---- acciones (todas en el hilo de la interfaz de Android) ----
    public void launch(final String key) { ui.post(new Runnable() { public void run() { ResolveInfo r = find(key); if (r != null) a.webStart(r); } }); }
    public void appMenu(final String key) { ui.post(new Runnable() { public void run() { ResolveInfo r = find(key); if (r != null) a.webAppMenu(r); } }); }
    public void openSettings() { ui.post(new Runnable() { public void run() { a.openLauncherSettings(); } }); }
    public void setVolume(final int v) { ui.post(new Runnable() { public void run() { a.webSetVolume(v); } }); }
    public void resetTrip() { ui.post(new Runnable() { public void run() { a.webResetTrip(); } }); }

    public void media(String what) {
        final int code = "next".equals(what) ? KeyEvent.KEYCODE_MEDIA_NEXT
                : "prev".equals(what) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        ui.post(new Runnable() {
            public void run() {
                android.media.AudioManager am = (android.media.AudioManager) a.getSystemService(HomeActivity.AUDIO_SERVICE);
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
        });
    }

    /** Preferencias compartidas con las demás interfaces (tema, modo, acento, reloj 12/24 h). */
    public String pref(String key, String def) { return a.webPrefs().getString("theme".equals(key) || "mode".equals(key) || "accent".equals(key) ? key : "web." + key, def); }
}
