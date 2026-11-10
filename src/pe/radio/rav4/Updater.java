package pe.radio.rav4;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Busca el último Release en GitHub, descarga el APK y lo instala encima del launcher actual. */
public class Updater {

    private static final String API = "https://api.github.com/repos/SebastianEPH/rav4-launcher/releases/latest";
    private static final String ACTION_STATUS = "pe.radio.rav4.INSTALL_STATUS";

    private final Activity act;
    private final int style;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AlertDialog progress;

    public Updater(Activity act, int style) {
        this.act = act;
        this.style = style;
    }

    public static String currentVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    public void check() {
        progress = new AlertDialog.Builder(act, style).setTitle("Buscar actualización")
                .setMessage("Consultando GitHub…").setCancelable(false).show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject rel = new JSONObject(new String(get(API), "UTF-8"));
                    String tag = rel.getString("tag_name");
                    String notes = rel.optString("body", "");
                    String apkUrl = null;
                    JSONArray assets = rel.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.getString("name").startsWith("RAV4Launcher") && a.getString("name").endsWith(".apk")) { /* no la ayudante RAV4Volumen */ apkUrl = a.getString("browser_download_url"); break; }
                    }
                    final String fTag = tag, fNotes = notes, fUrl = apkUrl;
                    ui.post(new Runnable() { public void run() { onRelease(fTag, fNotes, fUrl); } });
                } catch (final Exception e) {
                    fail("No se pudo consultar GitHub. Revisa que la radio tenga internet (WiFi).\n\n" + e.getMessage());
                }
            }
        }).start();
    }

    private void onRelease(String tag, String notes, final String apkUrl) {
        progress.dismiss();
        String mine = "v" + currentVersion(act);
        if (tag.equals(mine)) {
            new AlertDialog.Builder(act, style).setTitle("Estás al día")
                    .setMessage("Ya tienes la última versión (" + mine + ").").setPositiveButton("OK", null).show();
            return;
        }
        if (apkUrl == null) { fail("La versión " + tag + " todavía no tiene APK publicado. Intenta en unos minutos."); return; }
        String shortNotes = notes.length() > 600 ? notes.substring(0, 600) + "…" : notes;
        new AlertDialog.Builder(act, style).setTitle("Nueva versión: " + tag)
                .setMessage("Instalada: " + mine + "\n\n" + shortNotes)
                .setNegativeButton("Ahora no", null)
                .setPositiveButton("Descargar e instalar", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { download(apkUrl); }
                }).show();
    }

    private void download(final String apkUrl) {
        // Android 8+: el launcher necesita permiso para "instalar apps desconocidas" (se pide una sola vez).
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(act, style).setTitle("Falta un permiso")
                    .setMessage("Para actualizarse solo, el launcher necesita permiso para instalar apps.\n\n"
                            + "Activa \"Permitir de esta fuente\" en la pantalla que se abrirá, vuelve atrás "
                            + "y pulsa otra vez \"Buscar actualización\".")
                    .setPositiveButton("Abrir permiso", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        }
                    }).setNegativeButton("Cancelar", null).show();
            return;
        }
        progress = new AlertDialog.Builder(act, style).setTitle("Descargando actualización")
                .setMessage("0 %").setCancelable(false).show();
        new Thread(new Runnable() {
            public void run() {
                PackageInstaller.Session session = null;
                try {
                    HttpURLConnection c = open(apkUrl);
                    long total = c.getContentLength();
                    PackageInstaller pi = act.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    sp.setAppPackageName(act.getPackageName());
                    session = pi.openSession(pi.createSession(sp));
                    InputStream in = c.getInputStream();
                    OutputStream out = session.openWrite("launcher.apk", 0, total);
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int n, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        final int pct = total > 0 ? (int) (done * 100 / total) : -1;
                        if (pct != lastPct) {
                            lastPct = pct;
                            ui.post(new Runnable() { public void run() { progress.setMessage(pct >= 0 ? pct + " %" : "Descargando…"); } });
                        }
                    }
                    session.fsync(out);
                    out.close();
                    in.close();
                    ui.post(new Runnable() { public void run() { progress.setMessage("Instalando…"); } });
                    listenForResult();
                    Intent status = new Intent(ACTION_STATUS).setPackage(act.getPackageName());
                    session.commit(PendingIntent.getBroadcast(act, 0, status, PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
                    session.close();
                } catch (Exception e) {
                    if (session != null) session.abandon();
                    fail("Falló la descarga: " + e.getMessage());
                }
            }
        }).start();
    }

    private void listenForResult() {
        act.getApplicationContext().registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // Android muestra su pantalla de confirmación; al aceptar, el launcher se reinicia ya actualizado.
                    Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) act.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
                try { c.unregisterReceiver(this); } catch (Exception e) { }
                if (st != PackageInstaller.STATUS_SUCCESS)
                    fail("Android no instaló la actualización: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                else if (progress != null) progress.dismiss();
            }
        }, new IntentFilter(ACTION_STATUS));
    }

    private void fail(final String msg) {
        ui.post(new Runnable() {
            public void run() {
                if (progress != null) progress.dismiss();
                if (act.isFinishing()) return;
                new AlertDialog.Builder(act, style).setTitle("Actualización").setMessage(msg).setPositiveButton("OK", null).show();
            }
        });
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "RAV4-Launcher");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        return c;
    }

    private static byte[] get(String url) throws Exception {
        InputStream in = open(url).getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
