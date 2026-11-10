package pe.radio.rav4.vol;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** El launcher pide "silencio" antes de cambiar el volumen: oculta la ventana de la radio y la repone después. */
public class Quiet extends BroadcastReceiver {
    static final String KEY = "show_toast_panel";

    static boolean put(Context c, int v) {
        try { return android.provider.Settings.System.putInt(c.getContentResolver(), KEY, v); }
        catch (Throwable t) { return false; }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        put(c, 1);
        int ms = Math.max(500, Math.min(10000, i.getIntExtra("ms", 1800)));
        // al terminar se vuelve a 0 para que la perilla física siga mostrando su ventana
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Restore.class), PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + ms, pi);
    }
}
