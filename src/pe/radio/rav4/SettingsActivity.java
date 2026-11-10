package pe.radio.rav4;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Ajustes RAV4": ventana aparte para configurar el launcher (tema, apariencia, interfaz, permisos, actualización).
 * Vive fuera del launcher para que la pantalla de inicio sea liviana; al volver, el launcher aplica lo que cambió.
 */
public class SettingsActivity extends Activity {

    private WebView web;
    private SharedPreferences prefs;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("rav4", MODE_PRIVATE);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0C10);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView v, String url) { return !url.startsWith("file:///android_asset/"); }
        });
        web.addJavascriptInterface(new Bridge(), "Radio");
        web.loadUrl("file:///android_asset/web/settings.html");
        setContentView(web);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        js("S.refresh()");
        continueWizard();
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) { continueWizard(); }

    @Override
    protected void onDestroy() { web.destroy(); super.onDestroy(); }

    private void js(final String code) {
        runOnUiThread(new Runnable() { public void run() { web.evaluateJavascript("window.S&&" + code, null); } });
    }

    /** Marca que algo cambió: el launcher lo compara al volver y solo entonces se rearma. */
    private void touch() { prefs.edit().putLong("stamp", System.currentTimeMillis()).apply(); }

    // ======================= permisos =======================
    // Android no deja que una app se dé todos los permisos sola: los especiales se activan en su propia pantalla.
    // El asistente pide cada uno que falte, en orden, y sigue solo cuando el usuario vuelve a esta ventana.
    // {id, nombre, para qué, permisos de Android (si es de diálogo; vacío = pantalla especial)}
    static final String[][] PERMS = {
            {"location", "Ubicación (GPS)", "Velocidad, viaje y rumbo", "android.permission.ACCESS_FINE_LOCATION"},
            {"storage", "Almacenamiento", "Grabar el bus CAN en el USB", "android.permission.WRITE_EXTERNAL_STORAGE,android.permission.READ_EXTERNAL_STORAGE"},
            {"mic", "Micrófono", "Comandos de voz", "android.permission.RECORD_AUDIO"},
            {"camera", "Cámara", "Cámara de retroceso / dashcam", "android.permission.CAMERA"},
            {"phone", "Teléfono y contactos", "Llamar desde el launcher", "android.permission.READ_CONTACTS,android.permission.READ_PHONE_STATE,android.permission.CALL_PHONE"},
            {"settings", "Modificar ajustes del sistema", "Brillo y su mínimo seguro", ""},
            {"install", "Instalar apps", "Actualizarse desde GitHub", ""},
            {"notif", "Acceso a notificaciones", "Ver y controlar la música que suena", ""},
            {"overlay", "Mostrar sobre otras apps", "Velocímetro flotante encima de Waze/CarPlay", ""},
            {"usage", "Acceso de uso", "Apps recientes y más usadas", ""},
            {"battery", "Sin optimización de batería", "Que Android no lo cierre (ni la grabación CAN)", ""},
            {"dnd", "No molestar", "Modo manejo", ""},
            {"home", "Launcher predeterminado", "Que el botón Inicio abra este launcher", ""}};
    private boolean permWizard = false;

    private boolean runtimeOk(String perms) {
        if (Build.VERSION.SDK_INT < 23) return true;
        for (String p : perms.split(",")) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    boolean permGranted(String id) {
        try {
            for (String[] p : PERMS) if (p[0].equals(id) && p[3].length() > 0) return runtimeOk(p[3]);
            if (Build.VERSION.SDK_INT < 23) return true;
            if ("settings".equals(id)) return android.provider.Settings.System.canWrite(this);
            if ("install".equals(id)) return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
            if ("overlay".equals(id)) return android.provider.Settings.canDrawOverlays(this);
            if ("notif".equals(id)) {
                String l = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
                return l != null && l.contains(getPackageName());
            }
            if ("usage".equals(id)) {
                android.app.AppOpsManager ao = (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
                return ao.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
            }
            if ("battery".equals(id)) return ((android.os.PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName());
            if ("dnd".equals(id)) return ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).isNotificationPolicyAccessGranted();
            if ("home".equals(id)) {
                ResolveInfo r = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
                return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
            }
        } catch (Exception e) { }
        return false;
    }

    void permRequest(String id) {
        try {
            Uri me = Uri.parse("package:" + getPackageName());
            for (String[] p : PERMS) if (p[0].equals(id) && p[3].length() > 0) {
                // todos los permisos "de diálogo" que falten, en una sola tanda
                List<String> l = new ArrayList<String>();
                for (String[] q : PERMS) if (q[3].length() > 0 && !runtimeOk(q[3])) for (String x : q[3].split(",")) l.add(x);
                if (Build.VERSION.SDK_INT >= 23 && !l.isEmpty()) requestPermissions(l.toArray(new String[0]), 3);
                return;
            }
            String action = "settings".equals(id) ? "android.settings.action.MANAGE_WRITE_SETTINGS"
                    : "install".equals(id) ? "android.settings.MANAGE_UNKNOWN_APP_SOURCES"
                    : "overlay".equals(id) ? "android.settings.action.MANAGE_OVERLAY_PERMISSION"
                    : "battery".equals(id) ? "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
                    : "notif".equals(id) ? "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
                    : "usage".equals(id) ? "android.settings.USAGE_ACCESS_SETTINGS"
                    : "dnd".equals(id) ? "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS"
                    : "android.settings.HOME_SETTINGS";
            boolean withPkg = !"notif".equals(id) && !"usage".equals(id) && !"dnd".equals(id) && !"home".equals(id);
            Intent it = withPkg ? new Intent(action, me) : new Intent(action);
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) { try { startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:" + getPackageName()))); } catch (Exception x) { } }
    }

    private final Set<String> permAsked = new HashSet<String>();

    void startPermWizard() { permAsked.clear(); permWizard = true; continueWizard(); }

    private void continueWizard() {
        if (!permWizard) return;
        web.postDelayed(new Runnable() {
            public void run() {
                if (!permWizard) return;
                int missing = 0;
                for (String[] p : PERMS) {
                    if (permGranted(p[0])) continue;
                    missing++;
                    if (permAsked.contains(p[0])) continue;
                    // los de diálogo se piden juntos: se marcan todos como ya preguntados
                    if (p[3].length() > 0) { for (String[] q : PERMS) if (q[3].length() > 0) permAsked.add(q[0]); }
                    else permAsked.add(p[0]);
                    android.widget.Toast.makeText(SettingsActivity.this, "Permiso: " + p[1] + " — " + p[2], android.widget.Toast.LENGTH_LONG).show();
                    permRequest(p[0]);
                    return;
                }
                permWizard = false;
                android.widget.Toast.makeText(SettingsActivity.this, missing == 0 ? "✅ Todos los permisos concedidos"
                        : "Quedaron " + missing + " permisos sin dar; puedes darlos uno por uno en Permisos.", android.widget.Toast.LENGTH_LONG).show();
                js("S.refresh()");
            }
        }, 700);
    }

    org.json.JSONArray webPerms() throws org.json.JSONException {
        org.json.JSONArray a = new org.json.JSONArray();
        for (String[] p : PERMS) {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("id", p[0]); o.put("name", p[1]); o.put("desc", p[2]); o.put("ok", permGranted(p[0]));
            a.put(o);
        }
        return a;
    }


    private class Bridge {
        @JavascriptInterface public String pref(String k, String d) { return prefs.getString(k, d); }
        @JavascriptInterface public void setPref(String k, String v) { prefs.edit().putString(k, v).apply(); touch(); }
        @JavascriptInterface public String perms() {
            try {
                org.json.JSONArray a = new org.json.JSONArray();
                for (String[] p : PERMS) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("id", p[0]); o.put("name", p[1]); o.put("desc", p[2]); o.put("ok", permGranted(p[0]));
                    a.put(o);
                }
                return a.toString();
            } catch (Exception e) { return "[]"; }
        }
        @JavascriptInterface public void grant(final String id) {
            runOnUiThread(new Runnable() { public void run() { if ("all".equals(id)) startPermWizard(); else permRequest(id); } });
        }
        @JavascriptInterface public void checkUpdate() {
            runOnUiThread(new Runnable() { public void run() { new Updater(SettingsActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert).check(); } });
        }
        @JavascriptInterface public void open(final String what) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        startActivity(new Intent("home".equals(what) ? "android.settings.HOME_SETTINGS" : "android.settings.SETTINGS"));
                    } catch (Exception e) { }
                }
            });
        }
        @JavascriptInterface public String version() { return Updater.currentVersion(SettingsActivity.this) + " · Android " + Build.VERSION.RELEASE; }
        @JavascriptInterface public void close() { runOnUiThread(new Runnable() { public void run() { finish(); } }); }
    }
}
