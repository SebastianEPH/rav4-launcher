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
    private static final String SERVICE_ACTION = "com.nwd.can.service.ACTION_CAN_SERVICE";
    private static final String SERVICE_PKG = "com.nwd.can.setting";
    // códigos de ICanRemote4OuterFeature.add*CallBack
    private static final int[][] SUBSCRIBE = {{17, 0}, {11, 0}, {15, 0}, {9, 0}, {27, 0}, {13, 0}};
    private static final String[] SUB_NAMES = {"datos del auto", "puertas", "radar", "aire", "datos CAN", "volante"};
    private static final String[] BROADCASTS = {
            "com.nwd.ACTION_CAN_DOOR_STATE_CHANGE", "com.nwd.ACTION_CAN_DOOR_WINDOW", "com.nwd.ACTION_CAN_RADAR_STATE_CHANGE",
            "com.nwd.can.ACTION_CARTYPE_CHANGED", "com.nwd.action.CAN.ELECTRIC_VOLTAGE", "com.nwd.ACTION_BACKCAR_VIDEO_SIGNAL_STATE",
            "com.nwd.ACTION_CAN_AC_CONTROL", "com.nwd.ACTION_CAN_CENTRAL_CONTROL"};

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
                if (subscribe(b, SUBSCRIBE[i][0])) ok.append(ok.length() > 0 ? ", " : "").append(SUB_NAMES[i]);
            }
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

    private boolean subscribe(IBinder b, int code) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(FEATURE);
            d.writeStrongBinder(callback);
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
                else if (code == 2) add("objeto", "CarInfo recibido (" + data.dataAvail() + " bytes)");
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
        for (int i = 0; i < b.length && i < 40; i++) s.append(String.format(Locale.US, "%02X ", b[i] & 255));
        if (b.length > 40) s.append("… (").append(b.length).append(" bytes)");
        return s.toString().trim();
    }

    private void add(String kind, String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (log) {
            log.add(t + "|" + kind + "|" + msg);
            while (log.size() > 60) log.removeFirst();
        }
    }

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
        JSONArray l = new JSONArray();
        synchronized (log) { for (String s : log) l.put(s); }
        o.put("log", l);
        return o;
    }
}
