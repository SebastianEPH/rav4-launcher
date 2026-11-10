package pe.radio.rav4;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * Lee el bus CAN a través del servicio de la radio (com.nwd.can.setting → CanService, exportado sin permiso).
 * La MCU/caja CAN traduce las tramas del auto y el servicio las reparte por AIDL en paquetes:
 * byte[1] = tipo (1 aire, 2 datos del auto, 3 puertas, 4 radar), datos desde byte[3], enteros big-endian.
 * Ver FINDINGS.md → "Bus CAN".
 */
public class CanBridge {

    private static final String FEATURE = "com.nwd.can.sdk.outer.adil.ICanRemote4OuterFeature";
    private static final String CALLBACK = "com.nwd.can.sdk.outer.adil.ICanRemoteModelCallback";
    private static final String OUTER_CALLBACK = "com.nwd.can.sdk.outer.adil.ICanRemote4OuterCallback";
    private static final String SERVICE_ACTION = "com.nwd.can.service.ACTION_CAN_SERVICE";
    private static final String SERVICE_PKG = "com.nwd.can.setting";
    // códigos de ICanRemote4OuterFeature.add*CallBack
    private static final int[] SUBSCRIBE = {17, 11, 15, 9, 27, 13, 23, 25, 19, 21, 29, 33, 35};
    private static final String[] SUB_NAMES = {"datos del auto", "puertas", "radar", "aire", "datos CAN", "volante",
            "datos crudos", "ajustes CAN", "volumen CAN", "cámara", "amplificador", "TPMS", "A/C CAN"};
    // Todos los broadcasts CAN que aparecen en las apps del sistema: se escuchan solo para registrarlos.
    private static final String[] BROADCASTS = {
            "com.nwd.ACTION_CAN_DOOR_STATE_CHANGE", "com.nwd.ACTION_CAN_DOOR_WINDOW", "com.nwd.ACTION_CAN_RADAR_STATE_CHANGE",
            "com.nwd.can.ACTION_CARTYPE_CHANGED", "com.nwd.action.CAN.ELECTRIC_VOLTAGE", "com.nwd.ACTION_BACKCAR_VIDEO_SIGNAL_STATE",
            "com.nwd.ACTION_CAN_AC_CONTROL", "com.nwd.ACTION_CAN_CENTRAL_CONTROL", "com.nwd.ACTION_CAN_CAMERA_CONTROL",
            "com.nwd.ACTION_CAN_TUNER_CONTROL", "com.nwd.ACTION_CAN_CD_CONTROL", "com.nwd.ACTION_CAN_USB_CLOSE", "com.nwd.ACTION_CAN_CD_CLOSE",
            "com.nwd.can.ACTION_RADAR_SOUND", "com.nwd.can.ACTION_BACK_SHOW_RADAR", "com.nwd.can.ACTION_SPEED_TEST",
            "com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE", "com.nwd.action.ACTION_PLAY_CAN_ALARM_SOUND", "com.nwd.action.ACTION_STOP_CAN_ALARM_SOUND",
            "com.nwd.action.ACTION_CARTYPE_TIME_CHANGE", "com.nwd.action.ACTION_CHECK_CARTYPE", "com.nwd.action.ACTION_BACKCAR_CONFIG_UPDATE",
            "com.nwd.action.ACTION_START_CAN_AUTO_PARK", "com.nwd.action.CAN_FLOAT_MENU_SHOW_ACTION", "com.nwd.netapp.ACTION_DEBUG_DOOR",
            "com.nwd.netapp.ACTION_DEBUG_DOOR_FEEBACK", "com.nwd.can.setting.service.ACTION_CAR_CAMERA_VIEW_CHANGE",
            "com.nwd.ACTION_CARPLAY_CALL_ON", "com.nwd.ACTION_CARPLAY_CALL_OFF", "com.nwd.ACTION_BT_VOICE_AT_CAR",
            "com.nwd.ACTION_START_BACKCAR_SERVICE"};

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private IBinder svc;
    private boolean bound, stopped;
    private volatile String status = "Conectando con el servicio CAN…";
    private volatile long lastPacket = 0;
    private volatile int packets = 0;
    private final LinkedList<String> log = new LinkedList<String>();

    // último estado conocido (-1 = sin dato)
    private volatile int speed = -1, rpm = -1, handbrake = -1, fuel = -1, gear = -1, beltDriver = -1, beltPassenger = -1;
    private final int[] doors = {-1, -1, -1, -1, -1, -1};      // del.izq, del.der, tras.izq, tras.der, maletero, capó
    private final int[] locks = {-1, -1, -1, -1, -1};
    private final int[] lights = {-1, -1, -1, -1, -1, -1, -1, -1}; // altas, bajas, neblin.del, neblin.tras, posición, emergencia, der, izq
    private final int[] radar = {-1, -1, -1, -1};

    public CanBridge(Context c) { ctx = c.getApplicationContext(); }

    public void start() {
        stopped = false;
        bind();
        IntentFilter f = new IntentFilter();
        for (String a : BROADCASTS) f.addAction(a);
        try { ctx.registerReceiver(rx, f); } catch (Exception e) { }
    }

    public void stop() {
        stopRecording();
        stopped = true;
        try { ctx.unregisterReceiver(rx); } catch (Exception e) { }
        if (bound) try { ctx.unbindService(conn); } catch (Exception e) { }
        bound = false;
    }

    private void bind() {
        if (stopped) return;
        try {
            bound = ctx.bindService(new Intent(SERVICE_ACTION).setPackage(SERVICE_PKG), conn, Context.BIND_AUTO_CREATE);
            if (!bound) status = "No se encontró el servicio CAN de la radio (com.nwd.can.setting).";
        } catch (Exception e) {
            status = "Android no dejó conectar con el servicio CAN: " + e.getMessage();
        }
    }

    private final ServiceConnection conn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            svc = b;
            StringBuilder ok = new StringBuilder();
            for (int i = 0; i < SUBSCRIBE.length; i++) {
                if (subscribe(b, SUBSCRIBE[i], callback)) ok.append(ok.length() > 0 ? ", " : "").append(SUB_NAMES[i]);
            }
            if (subscribe(b, 3, outerCallback)) ok.append(", canal crudo de la caja CAN");
            status = ok.length() > 0 ? "Conectado. Suscrito a: " + ok : "Conectado, pero el servicio rechazó las suscripciones.";
            add("sistema", "servicio CAN conectado · " + ok);
            try {
                b.linkToDeath(new IBinder.DeathRecipient() {
                    public void binderDied() { status = "El servicio CAN se reinició; reconectando…"; h.postDelayed(new Runnable() { public void run() { bind(); } }, 3000); }
                }, 0);
            } catch (Exception e) { }
        }

        public void onServiceDisconnected(ComponentName n) {
            svc = null;
            status = "Servicio CAN desconectado; reconectando…";
        }
    };

    private boolean subscribe(IBinder b, int code, Binder cb) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(FEATURE);
            d.writeStrongBinder(cb);
            b.transact(code, d, r, 0);
            r.readException();
            return true;
        } catch (Exception e) {
            add("error", "suscripción " + code + ": " + e.getMessage());
            return false;
        } finally { d.recycle(); r.recycle(); }
    }

    /** Implementación a mano de ICanRemoteModelCallback (sin la clase AIDL generada). */
    private final Binder callback = new Binder() {
        { attachInterface(null, CALLBACK); }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(CALLBACK); return true; }
            try {
                data.enforceInterface(CALLBACK);
                if (code == 1) onPacket(data.createByteArray());
                else if (code == 2) add("carinfo", "CarInfo (parcel) " + hex(data.marshall()));
                else if (code == 3) add("objeto", "estado del amplificador");
                else if (code == 4) add("objeto", "presión de neumáticos (TPMS)");
                else if (code == 5) add("objeto", "estado del aire acondicionado");
                if (reply != null) reply.writeNoException();
            } catch (Exception e) {
                add("error", "callback " + code + ": " + e.getMessage());
            }
            return true;
        }
    };

    /** ICanRemote4OuterCallback: 1 = datos crudos de la caja CAN, 2 = datos CAN ya separados. */
    private final Binder outerCallback = new Binder() {
        { attachInterface(null, OUTER_CALLBACK); }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(OUTER_CALLBACK); return true; }
            try {
                data.enforceInterface(OUTER_CALLBACK);
                byte[] b = data.createByteArray();
                if (code == 1) { packets++; lastPacket = SystemClock.elapsedRealtime(); add("crudo", b == null ? "null" : hex(b)); }
                else if (code == 2) onPacket(b);
                if (reply != null) reply.writeNoException();
            } catch (Exception e) {
                add("error", "callback crudo " + code + ": " + e.getMessage());
            }
            return true;
        }
    };

    private static int be(byte[] b, int off, int len) {
        if (b == null || off + len > b.length) return -1;
        int v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (b[off + i] & 255);
        return v;
    }

    private static int at(byte[] b, int i) { return b != null && i < b.length ? b[i] : -1; }

    private void onPacket(byte[] b) {
        if (b == null || b.length < 2) return;
        packets++;
        lastPacket = SystemClock.elapsedRealtime();
        int type = b[1];
        String name = type == 1 ? "aire" : type == 2 ? "auto" : type == 3 ? "puertas" : type == 4 ? "radar" : type == 6 ? "volante" : "tipo " + type;
        add(name, hex(b));
        if (type == 2) {
            int s = be(b, 3, 2), r = be(b, 5, 2);
            speed = s == 65535 ? -1 : s;
            rpm = r == 65535 ? -1 : r;
            beltPassenger = at(b, 12);
            beltDriver = at(b, 13);
            handbrake = at(b, 16);
            int f = be(b, 17, 1);
            fuel = f == 255 ? -1 : f;
            for (int i = 0; i < 5; i++) locks[i] = at(b, 19 + i);
            for (int i = 0; i < 8; i++) lights[i] = at(b, 24 + i);
            if (b.length > 74) gear = b[74];
        } else if (type == 3 && b.length > 3) {
            int d = b[3];
            for (int i = 0; i < 6; i++) doors[i] = (d >> (7 - i)) & 1;
        } else if (type == 4) {
            for (int i = 0; i < 4; i++) radar[i] = at(b, 3 + i);
        }
    }

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            StringBuilder s = new StringBuilder(i.getAction().replace("com.nwd.", ""));
            Bundle e = i.getExtras();
            if (e != null) for (String k : e.keySet()) {
                Object v = e.get(k);
                s.append("  ").append(k).append('=').append(v instanceof byte[] ? hex((byte[]) v) : String.valueOf(v));
            }
            add("broadcast", s.toString());
        }
    };

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i++) s.append(String.format(Locale.US, "%02X ", b[i] & 255));
        return s.append("(").append(b.length).append(" B)").toString();
    }

    private void add(String kind, String msg) {
        String t = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        synchronized (log) {
            log.add(t.substring(0, 8) + "|" + kind + "|" + (msg.length() > 160 ? msg.substring(0, 160) + "…" : msg));
            while (log.size() > 60) log.removeFirst();
        }
        write(t + "  " + kind + "  " + msg);
    }

    // ======================= grabación en .txt =======================
    private Writer out;
    private File outFile;
    private volatile int outLines = 0;

    /** Carpeta RAV4Launcher en el pendrive (mismas rutas que usa Radio Diagnóstico); null si no hay USB. */
    public static File usbDir() {
        java.util.List<File> c = new java.util.ArrayList<File>();
        for (String p : new String[]{"/mnt/media_rw/udisk", "/mnt/media_rw/udiskh", "/mnt/media_rw/udisk3",
                "/mnt/media_rw/udisk4", "/storage/udisk", "/storage/udiskh"}) c.add(new File(p));
        File[] st = new File("/storage").listFiles();
        if (st != null) for (File f : st) if (!f.getName().equals("emulated") && !f.getName().equals("self") && f.isDirectory()) c.add(f);
        for (File base : c) {
            if (!base.isDirectory()) continue;
            File d = new File(base, "RAV4Launcher");
            d.mkdirs();
            if (d.isDirectory() && d.canWrite()) return d;
        }
        return null;
    }

    /** Empieza a grabar todo lo que llega del bus. Devuelve la ruta del archivo o un mensaje de error. */
    public synchronized String startRecording(String header) {
        if (out != null) return outFile.getAbsolutePath();
        File dir = usbDir();
        boolean usb = dir != null;
        if (!usb) { dir = new File(android.os.Environment.getExternalStorageDirectory(), "RAV4Launcher"); dir.mkdirs(); }
        outFile = new File(dir, "can_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt");
        try {
            out = new OutputStreamWriter(new FileOutputStream(outFile), "UTF-8");
            outLines = 0;
            out.write("# RAV4 Launcher — registro del bus CAN\n# " + header + "\n# Estado del servicio: " + status
                    + "\n# Formato: hora  tipo  datos (hex completo). 'estado' = resumen decodificado cada 5 s.\n"
                    + "# Tipos: auto=datos del auto (tipo 2), puertas (3), radar (4), aire (1), crudo=caja CAN sin procesar,\n"
                    + "#        carinfo=objeto CarInfo serializado, broadcast=aviso del sistema con sus extras.\n\n");
            out.flush();
            h.post(snapshot);
            return (usb ? "USB: " : "Memoria interna (no se detectó el USB): ") + outFile.getAbsolutePath();
        } catch (Exception e) {
            out = null;
            return "ERROR: no se pudo crear el archivo (" + e.getMessage() + "). ¿Diste permiso de almacenamiento?";
        }
    }

    public synchronized String stopRecording() {
        if (out == null) return "";
        h.removeCallbacks(snapshot);
        write("# fin de la grabación · " + outLines + " líneas");
        try { out.close(); } catch (Exception e) { }
        out = null;
        return outFile.getAbsolutePath();
    }

    private synchronized void write(String line) {
        if (out == null) return;
        try { out.write(line); out.write('\n'); out.flush(); outLines++; }
        catch (Exception e) { out = null; status = "Se interrumpió la grabación (¿se quitó el USB?)"; }
    }

    private final Runnable snapshot = new Runnable() {
        public void run() {
            write(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + "  estado  vel=" + speed + " rpm=" + rpm
                    + " puertas=" + java.util.Arrays.toString(doors) + " seguros=" + java.util.Arrays.toString(locks)
                    + " luces=" + java.util.Arrays.toString(lights) + " freno=" + handbrake + " cinturon=" + beltDriver + "/" + beltPassenger
                    + " comb=" + fuel + " marcha=" + gear + " radar=" + java.util.Arrays.toString(radar) + " paquetes=" + packets);
            if (out != null) h.postDelayed(this, 5000);
        }
    };

    private static JSONArray arr(int[] a) {
        JSONArray j = new JSONArray();
        for (int v : a) j.put(v);
        return j;
    }

    public JSONObject state() throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("status", status);
        o.put("connected", svc != null);
        o.put("packets", packets);
        o.put("ageMs", lastPacket == 0 ? -1 : SystemClock.elapsedRealtime() - lastPacket);
        o.put("speed", speed);
        o.put("rpm", rpm);
        o.put("handbrake", handbrake);
        o.put("fuel", fuel);
        o.put("gear", gear);
        o.put("beltDriver", beltDriver);
        o.put("beltPassenger", beltPassenger);
        o.put("doors", arr(doors));
        o.put("locks", arr(locks));
        o.put("lights", arr(lights));
        o.put("radar", arr(radar));
        o.put("recording", out != null ? outFile.getAbsolutePath() : "");
        o.put("recLines", outLines);
        JSONArray l = new JSONArray();
        synchronized (log) { for (String s : log) l.put(s); }
        o.put("log", l);
        return o;
    }
}
