package pe.radio.rav4;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class HomeActivity extends Activity {

    /** Brillo mínimo: con valores menores la pantalla de esta radio se apaga. */
    /** Brillo mínimo: 18 % de 255. Por debajo la pantalla de esta radio se apaga. */
    private static final int MIN_BRIGHTNESS = 46;

    private static final String[] PROJECTION_KEYS = {"carlink", "carplay", "android auto", "androidauto",
            "zlink", "mirror", "autokit", "tlink", "easyconn", "carbit", "phonelink", "screen cast", "airplay"};
    private static final String[] DOCK_FIXED = {"com.ivicar.avm"};
    private static final String[] DEFAULT_HIDDEN = {"com.nwd.guidebookskin", "com.nwd.appmall", "com.nwd.can.setting",
            "salvapantalla", "screensaver", "protector", "cd coche", "car cd", "instrucciones", "appstore", "datos del autom"};

    private final Handler h = new Handler();
    private SharedPreferences prefs;
    private Set<String> hidden, pinned, dockKeys = new HashSet<String>();
    private Theme.Pal P;
    private WebHome web;   // != null cuando la interfaz es la web (assets/web)
    private boolean lastDark;

    // vistas (pueden ser null según el tema)
    private FrameLayout root;
    private Views.Gauge gauge;
    private Views.Spark spark;
    private TextView clock, date, prompt, chips, sysPanel, gnssMini;
    private TextView tDist, tTime, tAvg, tMax;
    // Cockpit (igual al de la interfaz web)
    private Views.CockpitGauge cockGauge;
    private Views.CockpitRoad cockRoad;
    private Views.Compass cockCompass;
    private Views.Bar cockBattBar;
    private TextView tripBig, tripSub, cockBattV, cockBattState, cockSats, cockGpsSub;
    private LinearLayout dock;
    private View drawer, settings;

    // estado GPS / viaje
    private Location last;
    private float curKmh = 0, distM = 0, maxKmh = 0;
    private long movingMs = 0, lastFix = 0;
    private final long sessionTs = SystemClock.elapsedRealtime();
    private int satsUsed = -1, satsSeen = -1;
    private float batteryV = -1;   // voltaje de la batería del auto (vía CAN de la radio NWD)
    private final android.content.BroadcastReceiver uninstallRx = new android.content.BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            String res = i.getStringExtra("result");
            boolean ok = "1".equals(res);
            android.widget.Toast.makeText(HomeActivity.this,
                    ok ? "✅ La radio aceptó la orden de desinstalar." : "⚠️ La radio no pudo desinstalar esa app.",
                    android.widget.Toast.LENGTH_LONG).show();
            h.postDelayed(new Runnable() { public void run() { rebuildDock(); refreshDrawer(); if (homeGrid != null) showPage(homePage); if (web != null) web.appsChanged(); } }, 1500);
        }
    };

    /** Se instaló o quitó una app: solo entonces se rehace la lista (antes se releía en cada vuelta a Inicio). */
    private final android.content.BroadcastReceiver packagesRx = new android.content.BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { if (web != null) web.appsChanged(); }
    };

    private final android.content.BroadcastReceiver voltageRx = new android.content.BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            int raw = i.getIntExtra("extra_voltage_value", -1);
            if (raw >= 0) batteryV = raw / 10f;
        }
    };

    /** Texto corto de batería: voltaje + estado aproximado. */
    private String batteryText() {
        if (batteryV < 0) return "";
        String st = batteryV >= 13.3f ? "cargando" : batteryV >= 12.4f ? "ok" : batteryV >= 12.0f ? "baja" : "muy baja";
        return String.format(Locale.US, "🔋 %.1fV %s", batteryV, st);
    }
    private boolean gpsOn = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("rav4", MODE_PRIVATE);
        if (!prefs.getBoolean("globalPrefs", false)) {   // antes la web guardaba tema/modo/acento aparte
            SharedPreferences.Editor e = prefs.edit().putBoolean("globalPrefs", true);
            if ("web".equals(prefs.getString("engine", "native")))
                for (String k : new String[]{"theme", "mode", "accent"}) { String v = prefs.getString("web." + k, null); if (v != null) e.putString(k, v); }
            e.apply();
        }
        loadSets();
        applyTheme();
        // Lo demás después del primer cuadro: que la pantalla aparezca primero.
        h.post(new Runnable() {
            public void run() {
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"}, 1);
                startGps();
                try { registerReceiver(voltageRx, new android.content.IntentFilter("com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE")); } catch (Exception e) { }
                try { registerReceiver(uninstallRx, new android.content.IntentFilter("com.nwd.action.ACTION_SILENT_UNINSTALL_APK_RESULT")); } catch (Exception e) { }
                android.content.IntentFilter pf = new android.content.IntentFilter();
                pf.addAction(Intent.ACTION_PACKAGE_ADDED);
                pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
                pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
                pf.addDataScheme("package");
                try { registerReceiver(packagesRx, pf); } catch (Exception e) { }
                tick.run();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        // solo se rearma si en "Ajustes Launcher" cambió algo
        long st = prefs.getLong("stamp", 0);
        if (st != appliedStamp) { appliedStamp = st; if (useWeb() != (web != null) || web == null) applyTheme(); else web.js("RAV4.reloadPrefs()"); }
        rebuildDock();
        if (web != null) web.js("RAV4.resume()");
    }

    private long appliedStamp = -1;
    private static boolean introDone = false;

    /** Abre la ventana aparte "Ajustes Launcher". */
    void openLauncherSettings() {
        try { startActivity(new Intent(this, SettingsActivity.class)); } catch (Exception e) { }
    }

    @Override
    public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) { startGps(); }

    @Override
    public void onBackPressed() { closeOverlays(); }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); closeOverlays(); }

    private void closeOverlays() {
        if (web != null) { web.js("RAV4.back()"); return; }
        if (settings != null && settings.getVisibility() == View.VISIBLE) { settings.setVisibility(View.GONE); immersive(); return; }
        if (isOpen(cc)) { closeCC(); return; }
        closeDrawer();
    }

    private static boolean isOpen(View v) { return v != null && v.getVisibility() == View.VISIBLE; }

    /** Gestos (igual al HTML): ← abre las apps; en Inicio ←/→ cambian de página. No hay gesto hacia abajo. */
    private final Views.GestureFrame.Listener gestures = new Views.GestureFrame.Listener() {
        public boolean wants(int dir) {
            if (isOpen(drawer)) return dir == Views.LEFT || dir == Views.RIGHT;
            return dir == Views.RIGHT || dir == Views.LEFT;
        }
        public void swipe(int dir) {
            if (isOpen(drawer)) {
                if (dir == Views.LEFT) showDrawerPage(drawerPage + 1);
                else if (drawerPage > 0) showDrawerPage(drawerPage - 1);
                else closeDrawer();
            }
            else if (P.home && dir == Views.LEFT) showPage(homePage + 1);
            else if (P.home && dir == Views.RIGHT) showPage(homePage - 1);
            else openDrawer(dir != Views.LEFT);
        }
    };

    // ======================= tema =======================
    private void applyTheme() {
        P = Theme.fromPrefs(prefs);
        Theme.P = P;
        Views.anim = "on".equals(prefs.getString("native.anim", "on"));
        lastDark = P.dark;
        if (useWeb()) {
            dock = null; drawer = null; cc = null; settings = null; homeGrid = null;
            volLabel = brLabel = null; gauge = null; spark = null; clock = date = prompt = chips = sysPanel = gnssMini = null;
            tDist = tTime = tAvg = tMax = null; cockGauge = null; cockRoad = null; cockCompass = null; cockBattBar = null; tripBig = tripSub = cockBattV = cockBattState = cockSats = cockGpsSub = null; cockGauge = null; cockRoad = null; cockCompass = null; cockBattBar = null; tripBig = tripSub = cockBattV = cockBattState = cockSats = cockGpsSub = null;
            cmpBig = null; cmpDir = cmpStreet = altTxt = altSub = accTxt = accSub = ctlVol = ctlBr = null; altLine = null; bigClocks.clear();
            if (web == null) web = new WebHome(this);
            setContentView(web.view());
            immersive();
            return;
        }
        if (web != null) { web.destroy(); web = null; }
        volLabel = brLabel = null; gauge = null; spark = null; clock = date = prompt = chips = sysPanel = gnssMini = null;
        tDist = tTime = tAvg = tMax = null;

        root = new Views.GestureFrame(this, gestures);
        GradientDrawable wall = P.dusk
                ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{P.bg, P.bg2, P.bg3})
                : new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{P.bg, P.bg2});
        root.setBackground(wall);
        if (P.cockpit || P.home) {
            android.graphics.Bitmap photo = null;
            if ("rav4".equals(prefs.getString("wall", "ondas")))
                photo = android.graphics.BitmapFactory.decodeResource(getResources(),
                        getResources().getIdentifier("rav4", "drawable", getPackageName()));
            root.addView(new Views.Wallpaper(this, prefs.getString("wall", "ondas"), photo));
        }
        View themeView = P.home ? buildHome() : P.cockpit ? buildCockpit() : P.dusk ? buildDusk() : P.glass ? buildGlass() : buildTerminal();
        root.addView(themeView);
        enterCards(themeView);
        if (gauge != null && !gaugeSwept) { gaugeSwept = true; gauge.sweep(); }
        root.addView(hints());
        drawer = buildDrawer();
        drawer.setVisibility(View.GONE);
        root.addView(drawer);
        settings = null;   // los ajustes viven en SettingsActivity
        setContentView(root);
        if (!introDone && Views.anim) { introDone = true; root.setAlpha(0f); root.setScaleX(1.03f); root.setScaleY(1.03f);
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).start(); }
        getWindow().setBackgroundDrawable(null);   // la foto de arranque ya no hace falta: evita dibujarla en cada cuadro
        rebuildDock();
        if (last != null) { updateTrip(); feedGauge(curKmh); }
        else feedGaugeSub(P.glass ? "Buscando GPS…" : "gps: searching...");
        immersive();
    }

    // ======================= preferencias =======================
    private void loadSets() {
        if (!prefs.getBoolean("init", false)) {
            Set<String> hs = new HashSet<String>();
            PackageManager pm = getPackageManager();
            for (ResolveInfo r : launchables()) {
                String pkg = r.activityInfo.packageName.toLowerCase(Locale.ROOT);
                String label = String.valueOf(r.loadLabel(pm)).toLowerCase(Locale.ROOT);
                for (String k : DEFAULT_HIDDEN) if (pkg.equals(k) || label.contains(k)) hs.add(key(r));
                if (label.equals("cd")) hs.add(key(r));
            }
            prefs.edit().putStringSet("hidden", hs).putBoolean("init", true).apply();
        }
        hidden = new HashSet<String>(prefs.getStringSet("hidden", new HashSet<String>()));
        hidden.remove(getPackageName() + "/" + SettingsActivity.class.getName());   // Ajustes Launcher nunca se oculta
        pinned = new HashSet<String>(prefs.getStringSet("pinned", new HashSet<String>()));
    }

    private void saveSets() {
        prefs.edit().putStringSet("hidden", new HashSet<String>(hidden))
                .putStringSet("pinned", new HashSet<String>(pinned)).apply();
    }

    private static String key(ResolveInfo r) { return r.activityInfo.packageName + "/" + r.activityInfo.name; }

    private List<ResolveInfo> launchables() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> out = new ArrayList<ResolveInfo>();
        for (ResolveInfo r : getPackageManager().queryIntentActivities(i, 0))
            if (!r.activityInfo.name.equals(HomeActivity.class.getName())) out.add(r);   // "Ajustes Launcher" sí aparece
        return out;
    }

    private List<ResolveInfo> sorted(List<ResolveInfo> l) {
        final PackageManager pm = getPackageManager();
        Collections.sort(l, new Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm)));
            }
        });
        return l;
    }

    // ======================= TEMA TERMINAL =======================
    private View buildTerminal() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(6), dp(14), dp(10));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        prompt = txt("", 15, P.ok);
        prompt.setSingleLine(true);
        prompt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(prompt, new LinearLayout.LayoutParams(0, -2, 1f));
        date = txt("", 14, P.dim);
        bar.addView(date);
        clock = txt("", 26, P.text);
        clock.setTypeface(P.fontBold);
        clock.setPadding(dp(14), 0, dp(14), 0);
        bar.addView(clock);
        chips = txt("", 13, P.dim);
        chips.setGravity(Gravity.END);
        chips.setSingleLine(true);
        chips.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(chips, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(bar, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout mid = new LinearLayout(this);
        col.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout speedP = tPanel("speed.gps");
        gauge = new Views.Gauge(this);
        speedP.addView(gauge, new LinearLayout.LayoutParams(-1, 0, 1f));
        speedP.addView(tLabel("history[120s]"));
        spark = new Views.Spark(this);
        speedP.addView(spark, new LinearLayout.LayoutParams(-1, dp(70)));
        mid.addView(speedP, weight(0.95f, dp(10)));

        LinearLayout carP = tPanel("vehicle  rav4.xa40 · 2013");
        carP.addView(carImage(), new LinearLayout.LayoutParams(-1, 0, 1f));
        carP.addView(tLabel("trip"));
        carP.addView(tripRow(false));
        TextView reset = txt("[ reset trip ]", 12, P.dim);
        reset.setPadding(0, dp(6), 0, 0);
        reset.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { resetTrip(); } });
        carP.addView(reset);
        addSlot(mid, "terminal.slot1", carP, weight(1.35f, dp(10)));

        LinearLayout sysP = tPanel("telemetry");
        sysPanel = txt("", 13, P.text);
        sysPanel.setLineSpacing(0, 1.18f);
        sysP.addView(sysPanel, new LinearLayout.LayoutParams(-1, 0, 1f));
        addSlot(mid, "terminal.slot2", sysP, weight(0.85f, 0));

        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackground(box(P.panel, P.border, P.radius));
        dock.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dp(104));
        dl.topMargin = dp(10);
        col.addView(dock, dl);
        return col;
    }

    private LinearLayout tPanel(String title) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(box(P.panel, P.border, P.radius));
        p.setPadding(dp(14), dp(10), dp(14), dp(12));
        TextView t = txt("▸ " + title, 13, P.accent);
        t.setTypeface(P.fontBold);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        p.addView(t);
        return p;
    }

    private TextView tLabel(String s) {
        TextView t = txt("# " + s, 11, P.dim);
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    // ======================= Barra lateral flotante (Cockpit / Inicio) =======================
    private LinearLayout sidebar(boolean showControls) {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        side.setBackground(box(P.dark ? 0xCC111114 : 0xCCFFFFFF, 0x33FFFFFF, 28));
        side.setPadding(0, dp(14), 0, dp(14));
        clock = txt("", 22, P.text);
        clock.setTypeface(P.fontBold);
        side.addView(clock);
        chips = txt("", 10, P.dim);
        side.addView(chips);
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, 0, 1f);
        dl.topMargin = dp(12);
        side.addView(dock, dl);
        // botón inicio: vuelve a la primera página / abre ajustes con toque largo
        TextView homeB = txt("▢", 30, P.text);
        homeB.setGravity(Gravity.CENTER);
        homeB.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (P.home) showPage(0); else openDrawer(true); } });
        homeB.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { openSettings(); return true; } });
        side.addView(homeB);
        return side;
    }

    // ======================= TEMA COCKPIT (tarjetas tipo panel) =======================
    private TextView cockTopMid, cockTopBatt;
    private static boolean bootAnimDone = false, gaugeSwept = false;

    private View buildCockpit() {
        final int ACC = 0xFF00E5FF;
        FrameLayout wrap = new FrameLayout(this);
        cockRoad = new Views.CockpitRoad(this);
        wrap.addView(cockRoad, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(10), dp(16), dp(16));
        wrap.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // ---- barra superior: hora/fecha · estado centro · batería ----
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(4), dp(8));
        clock = txt("--:--", 22, cTxt());
        clock.setTypeface(P.fontBold);
        top.addView(clock);
        date = txt("", 13, cDim());
        date.setPadding(dp(10), dp(6), 0, 0);
        top.addView(date);
        cockTopMid = txt("", 14, cDim());
        cockTopMid.setGravity(Gravity.CENTER);
        top.addView(cockTopMid, new LinearLayout.LayoutParams(0, -2, 1f));
        cockTopBatt = txt("", 15, ACC);
        cockTopBatt.setTypeface(P.fontBold);
        top.addView(cockTopBatt);
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // ---- cuerpo: dock · velocímetro · columna de tarjetas ----
        LinearLayout row = new LinearLayout(this);
        col.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));

        // dock de íconos (igual al web): íconos de apps + ajustes + cajón
        LinearLayout leftDock = new LinearLayout(this);
        leftDock.setOrientation(LinearLayout.VERTICAL);
        leftDock.setGravity(Gravity.CENTER_HORIZONTAL);
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setGravity(Gravity.CENTER);
        leftDock.addView(dock, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView gear = txt("⚙", 26, cTxt());
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(box(cCard(), 0x2900E5FF, 18));
        LinearLayout.LayoutParams gearL = new LinearLayout.LayoutParams(dp(58), dp(58));
        gearL.bottomMargin = dp(10);
        gear.setLayoutParams(gearL);
        gear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openSettings(); } });
        leftDock.addView(gear);
        TextView appsBtn = txt("▦", 28, 0xFFFFFFFF);
        appsBtn.setGravity(Gravity.CENTER);
        GradientDrawable ab = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{ACC, 0xFFBF5AF2});
        ab.setCornerRadius(dp(18));
        appsBtn.setBackground(ab);
        appsBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openDrawer(true); } });
        leftDock.addView(appsBtn, new LinearLayout.LayoutParams(dp(58), dp(58)));
        row.addView(leftDock, new LinearLayout.LayoutParams(dp(76), -1));

        // velocímetro grande
        LinearLayout gaugeCard = cockCard();
        gaugeCard.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, -1, 1.55f);
        gl.leftMargin = dp(12);
        cockGauge = new Views.CockpitGauge(this);
        String gs = prefs.getString("cockpit.size", "m");
        cockGauge.setScale("s".equals(gs) ? 0.74f : "l".equals(gs) ? 1f : 0.88f);
        if (!bootAnimDone) { bootAnimDone = true; cockGauge.sweep(); }
        try { cockGauge.setMax(Math.min(220, Integer.parseInt(prefs.getString("web.t.cockpit.scale", "180")))); } catch (Exception e) { }
        gaugeCard.addView(cockGauge, new LinearLayout.LayoutParams(-1, -1));
        row.addView(gaugeCard, gl);

        // columna derecha
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -1, 1f);
        rl.leftMargin = dp(12);
        row.addView(right, rl);

        // 4 espacios configurables (Ajustes Launcher → Tema actual)
        right.addView(cockSlot(prefs.getString("cockpit.slot1", "trip")), vWeight(1f, true));
        LinearLayout r2 = new LinearLayout(this);
        r2.addView(cockSlot(prefs.getString("cockpit.slot2", "batt")), hWeight(1f, true));
        r2.addView(cockSlot(prefs.getString("cockpit.slot3", "gps")), hWeight(1f, false));
        right.addView(r2, vWeight(1f, true));
        right.addView(cockSlot(prefs.getString("cockpit.slot4", "music")), vWeight(1f, false));
        feedCockpitTrip();
        feedCockpitLive();
        return wrap;
    }

    // ---- tarjetas intercambiables del Cockpit ----
    private Views.Compass cmpBig;
    private TextView cmpDir, cmpStreet, altTxt, altSub, accTxt, accSub, ctlVol, ctlBr;
    private Views.Line altLine;

    /** Espacio configurable de cualquier tema: si el usuario eligió una tarjeta, la pone en vez de la original. */
    private View slot(String key, View original) {
        String t = prefs.getString(key, "default");
        if ("default".equals(t) || "car".equals(t) && original != null) return original;
        if ("none".equals(t)) return null;
        return cockSlot(t);
    }
    private void addSlot(LinearLayout parent, String key, View original, LinearLayout.LayoutParams lp) {
        View v = slot(key, original);
        if (v != null) parent.addView(v, lp);
    }

    private View cockSlot(String type) {
        final int ACC = P.cockpit ? 0xFF00E5FF : P.accent;
        LinearLayout c = cockCard();
        if ("batt".equals(type)) {
            c.addView(cockLabel("BATERÍA"));
            cockBattV = txt("—", 32, ACC); cockBattV.setTypeface(P.fontBold); c.addView(cockBattV);
            cockBattBar = new Views.Bar(this);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(8)); bl.topMargin = dp(10);
            c.addView(cockBattBar, bl);
            cockBattState = txt("", 12, cDim()); cockBattState.setPadding(0, dp(6), 0, 0); c.addView(cockBattState);
        } else if ("gps".equals(type)) {
            c.addView(cockLabel("GPS"));
            cockSats = txt("—", 32, cTxt()); cockSats.setTypeface(P.fontBold); c.addView(cockSats);
            cockGpsSub = txt("", 13, cDim()); cockGpsSub.setLineSpacing(0, 1.2f); c.addView(cockGpsSub);
        } else if ("music".equals(type)) {
            c.addView(cockLabel("MÚSICA"));
            LinearLayout mc = new LinearLayout(this);
            mc.setGravity(Gravity.CENTER);
            TextView prevB = mediaBtn("⏮", android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); prevB.setTextColor(cTxt());
            mc.addView(prevB, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView play = txt("⏯", 30, 0xFFFFFFFF);
            play.setGravity(Gravity.CENTER);
            GradientDrawable pb = new GradientDrawable(); pb.setShape(GradientDrawable.OVAL); pb.setColor(ACC);
            play.setBackground(pb);
            play.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { media(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); } });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(64), dp(64));
            pl.leftMargin = pl.rightMargin = dp(20);
            mc.addView(play, pl);
            TextView nextB = mediaBtn("⏭", android.view.KeyEvent.KEYCODE_MEDIA_NEXT); nextB.setTextColor(cTxt());
            mc.addView(nextB, new LinearLayout.LayoutParams(0, -2, 1f));
            c.addView(mc, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else if ("compass".equals(type)) {
            c.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL);
            t.addView(cockLabel("RUMBO"));
            cmpDir = txt("—", 30, cTxt()); cmpDir.setTypeface(P.fontBold); t.addView(cmpDir);
            cmpStreet = txt("", 13, cDim()); cmpStreet.setMaxLines(2); t.addView(cmpStreet);
            c.addView(t, new LinearLayout.LayoutParams(0, -1, 1f));
            cmpBig = new Views.Compass(this);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(80), dp(80)); cl.gravity = Gravity.CENTER_VERTICAL;
            c.addView(cmpBig, cl);
        } else if ("alt".equals(type)) {
            c.addView(cockLabel("ALTITUD"));
            altTxt = txt("—", 28, cTxt()); altTxt.setTypeface(P.fontBold); c.addView(altTxt);
            altSub = txt("", 12, cDim()); c.addView(altSub);
            altLine = new Views.Line(this);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 0, 1f); ll.topMargin = dp(4);
            c.addView(altLine, ll);
        } else if ("accel".equals(type)) {
            c.addView(cockLabel("0 – 100 KM/H"));
            accTxt = txt("—", 30, ACC); accTxt.setTypeface(P.fontBold); c.addView(accTxt);
            accSub = txt("", 12, cDim()); accSub.setLineSpacing(0, 1.2f); c.addView(accSub);
            c.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) {
                prefs.edit().remove("accel.best").apply(); accelBest = 0; gPeak = 0; feedCockpitLive(); return true; } });
        } else if ("shortcuts".equals(type)) {
            c.addView(cockLabel("ACCESOS"));
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            int n = 0;
            for (final ResolveInfo ri : shortcutApps()) {
                if (n++ >= 4) break;
                LinearLayout b = new LinearLayout(this);
                b.setOrientation(LinearLayout.VERTICAL);
                b.setGravity(Gravity.CENTER);
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageDrawable(appIcon(ri));
                b.addView(iv, new LinearLayout.LayoutParams(dp(48), dp(48)));
                TextView l = txt(shortName(ri), 11, cTxt()); l.setSingleLine(true); l.setGravity(Gravity.CENTER);
                l.setEllipsize(android.text.TextUtils.TruncateAt.END);
                b.addView(l, new LinearLayout.LayoutParams(-1, -2));
                b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { start(ri); } });
                row.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            if (n == 0) row.addView(txt("No encontré ZLINK, CarPlay ni mapas", 13, cDim()));
            c.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else if ("controls".equals(type)) {
            c.addView(cockLabel("CONTROLES"));
            LinearLayout g = new LinearLayout(this); g.setOrientation(LinearLayout.VERTICAL); g.setGravity(Gravity.CENTER);
            LinearLayout a = new LinearLayout(this), b = new LinearLayout(this);
            a.setGravity(Gravity.CENTER_VERTICAL); b.setGravity(Gravity.CENTER_VERTICAL);
            ctlVol = txt("", 14, cTxt()); ctlBr = txt("", 14, cTxt());
            a.addView(cBtn("🔉", new View.OnClickListener() { public void onClick(View v) { vol(-1); ctlLater(); } }));
            a.addView(ctlVol, new LinearLayout.LayoutParams(0, -2, 1f)); ctlVol.setGravity(Gravity.CENTER);
            a.addView(cBtn("🔊", new View.OnClickListener() { public void onClick(View v) { vol(1); ctlLater(); } }));
            a.addView(cBtn("🔇", new View.OnClickListener() { public void onClick(View v) { mute(); ctlLater(); } }));
            b.addView(cBtn("☀−", new View.OnClickListener() { public void onClick(View v) { bright(-1); ctlLater(); } }));
            b.addView(ctlBr, new LinearLayout.LayoutParams(0, -2, 1f)); ctlBr.setGravity(Gravity.CENTER);
            b.addView(cBtn("☀+", new View.OnClickListener() { public void onClick(View v) { bright(1); ctlLater(); } }));
            b.addView(cBtn("⚙", new View.OnClickListener() { public void onClick(View v) { openLauncherSettings(); } }));
            g.addView(a, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(8);
            g.addView(b, bl);
            c.addView(g, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else if ("clock".equals(type)) {
            c.setGravity(Gravity.CENTER);
            TextView big = txt("", 40, cTxt()); big.setTypeface(P.fontBold); big.setGravity(Gravity.CENTER);
            final TextView sub = txt("", 13, cDim()); sub.setGravity(Gravity.CENTER);
            bigClocks.add(big); bigClocks.add(sub);
            c.addView(big); c.addView(sub);
        } else { // trip
            c.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout tripTxt = new LinearLayout(this);
            tripTxt.setOrientation(LinearLayout.VERTICAL);
            tripTxt.addView(cockLabel("VIAJE"));
            tripBig = txt("0.0 km", 30, cTxt()); tripBig.setTypeface(P.fontBold); tripTxt.addView(tripBig);
            tripSub = txt("", 13, cDim()); tripSub.setLineSpacing(0, 1.2f); tripTxt.addView(tripSub);
            c.addView(tripTxt, new LinearLayout.LayoutParams(0, -1, 1f));
            cockCompass = new Views.Compass(this);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(72), dp(72)); cl.gravity = Gravity.CENTER_VERTICAL;
            c.addView(cockCompass, cl);
        }
        return c;
    }

    private final List<TextView> bigClocks = new ArrayList<TextView>();

    private TextView cBtn(String t, View.OnClickListener l) {
        TextView b = txt(t, 18, cTxt());
        b.setGravity(Gravity.CENTER);
        b.setBackground(box(P.dark ? 0x22FFFFFF : 0x14000000, 0, 14));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(46));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void ctlLater() { h.postDelayed(new Runnable() { public void run() { feedControls(); } }, 300); }

    private void feedControls() {
        if (ctlVol == null) return;
        ctlVol.setText("Vol " + volNow());
        try {
            int b = android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS);
            ctlBr.setText("Brillo " + Math.round(b * 100f / 255f) + "%");
        } catch (Exception e) { ctlBr.setText("Brillo --"); }
    }

    /** CarPlay / Android Auto (ZLINK), proyección y mapas, para la tarjeta "Accesos". */
    private List<ResolveInfo> shortcutApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> out = new ArrayList<ResolveInfo>(), maps = new ArrayList<ResolveInfo>();
        for (ResolveInfo r : sorted(launchables())) {
            String s = (r.activityInfo.packageName + " " + r.activityInfo.name + " " + r.loadLabel(pm)).toLowerCase(Locale.ROOT);
            boolean proj = false;
            for (String k : PROJECTION_KEYS) if (s.contains(k)) { proj = true; break; }
            if (proj) out.add(r);
            else if (s.contains("maps") || s.contains("waze") || s.contains("mapa") || s.contains("navi")) maps.add(r);
        }
        out.addAll(maps);
        return out;
    }

    private String shortName(ResolveInfo r) {
        String n = r.activityInfo.name.toLowerCase(Locale.ROOT);
        if (n.contains("carplay")) return "CarPlay";
        if (n.contains("hicar") || n.contains("androidauto") || n.contains("auto")) return "Android Auto";
        return String.valueOf(r.loadLabel(getPackageManager()));
    }

    // aceleración (0-100) y altitud
    private long accelT0 = 0; private float accelBest = 0, gNow = 0, gPeak = 0, lastAccKmh = 0; private long lastAccT = 0;
    private float altLastDist = 0, altLastAlt = Float.NaN, grade = 0;
    private Location geoAt; private String street = ""; private boolean geoBusy = false;

    private void trackExtras(Location l, float kmh, long now) {
        if (accelBest == 0) accelBest = prefs.getFloat("accel.best", 0);
        if (kmh < 3) accelT0 = -1;
        else if (accelT0 == -1) accelT0 = now;
        if (accelT0 > 0 && kmh >= 100) {
            float t = (now - accelT0) / 1000f;
            if (accelBest == 0 || t < accelBest) { accelBest = t; prefs.edit().putFloat("accel.best", t).apply(); }
            lastRun = t; accelT0 = 0;
        }
        if (lastAccT > 0 && now > lastAccT) {
            float g = ((kmh - lastAccKmh) / 3.6f) / ((now - lastAccT) / 1000f) / 9.81f;
            gNow = gNow * 0.6f + g * 0.4f;
            if (Math.abs(gNow) > Math.abs(gPeak)) gPeak = gNow;
        }
        lastAccKmh = kmh; lastAccT = now;
        if (l.hasAltitude()) {
            float a = (float) l.getAltitude();
            if (Float.isNaN(altLastAlt)) { altLastAlt = a; altLastDist = distM; }
            else if (distM - altLastDist > 60) { grade = (a - altLastAlt) / (distM - altLastDist) * 100f; altLastAlt = a; altLastDist = distM; }
            Views.Line.push(a);
        }
        if ((geoAt == null || geoAt.distanceTo(l) > 120) && !geoBusy && cmpStreet != null && android.location.Geocoder.isPresent()) {
            geoBusy = true; geoAt = l;
            final double la = l.getLatitude(), lo = l.getLongitude();
            new Thread(new Runnable() { public void run() {
                String s = "";
                try {
                    List<android.location.Address> a = new android.location.Geocoder(HomeActivity.this, new Locale("es")).getFromLocation(la, lo, 1);
                    if (a != null && !a.isEmpty()) {
                        android.location.Address ad = a.get(0);
                        String st = ad.getThoroughfare(), dist = ad.getSubLocality() != null ? ad.getSubLocality() : ad.getLocality();
                        s = (st != null ? st : "") + (dist != null ? (st != null ? "\n" : "") + dist : "");
                    }
                } catch (Exception e) { }
                street = s; geoBusy = false;
            } }).start();
        }
    }
    private float lastRun = 0;

    private void media(int code) {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
    }


    /** Tarjeta del Cockpit: fondo oscuro translúcido, bordes redondeados (igual al web). */
    // Colores del Cockpit según el modo (Oscuro / Claro / Automático)
    private int cTxt() { return P.dark ? 0xFFFFFFFF : 0xFF0B0C10; }
    private int cDim() { return P.dark ? 0x99FFFFFF : 0x99000000; }
    private int cCard() { return P.dark ? 0xD10E1016 : 0xE6FFFFFF; }

    private LinearLayout cockCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        if (P.cockpit) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(cCard());
            g.setCornerRadius(dp(20));
            g.setStroke(1, 0x2900E5FF);
            c.setBackground(g);
        } else c.setBackground(box(P.panel, P.border, P.radius));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setTag(R_CARD, "card");
        return c;
    }

    private TextView cockLabel(String s) {
        TextView t = txt(s, 12, cDim());
        t.setTypeface(P.fontBold);
        t.setAllCaps(true);
        t.setLetterSpacing(0.1f);
        t.setSingleLine(true);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.bottomMargin = dp(6);
        t.setLayoutParams(l);
        return t;
    }

    private LinearLayout cTile(String title, int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(P.panel, P.border, P.radius));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setClipToPadding(true);
        c.setTag(R_CARD, "card");
        TextView t = txt(title, 13, color);
        t.setTypeface(P.fontBold);
        t.setSingleLine(true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.04f);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.bottomMargin = dp(8);
        c.addView(t, tl);
        return c;
    }

    private TextView miniBtn(String label, View.OnClickListener l) {
        TextView b = txt(label, 13, P.text);
        b.setGravity(Gravity.CENTER);
        b.setBackground(box(P.track, 0, 14));
        b.setOnClickListener(l);
        return b;
    }

    private TextView mediaBtn(String t, final int code) {
        TextView b = txt(t, 30, 0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
            }
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));
        return b;
    }

    private LinearLayout.LayoutParams vWeight(float w, boolean gap) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, 0, w);
        if (gap) l.bottomMargin = dp(12);
        return l;
    }

    private LinearLayout.LayoutParams hWeight(float w, boolean gap) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -1, w);
        if (gap) l.rightMargin = dp(12);
        return l;
    }

    // ======================= TEMA INICIO (cuadrícula de íconos con páginas) =======================
    private int homePage = 0;
    private LinearLayout homeGrid, homeDots;
    private static final int HOME_COLS = 5, HOME_ROWS = 2;

    private View buildHome() {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(12), dp(12), dp(12), dp(8));
        row.addView(sidebar(false), new LinearLayout.LayoutParams(dp(96), -1));
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -1, 1f);
        ml.leftMargin = dp(20);
        row.addView(main, ml);
        homeGrid = new LinearLayout(this);
        homeGrid.setOrientation(LinearLayout.VERTICAL);
        main.addView(homeGrid, new LinearLayout.LayoutParams(-1, 0, 1f));
        homeDots = new LinearLayout(this);
        homeDots.setGravity(Gravity.CENTER);
        main.addView(homeDots, new LinearLayout.LayoutParams(-1, dp(28)));
        // columna opcional de tarjetas a la derecha (Ajustes Launcher → Tema actual)
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        addSlot(side, "home.slot1", null, vWeight(1f, true));
        addSlot(side, "home.slot2", null, vWeight(1f, false));
        if (side.getChildCount() > 0) {
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(330), -1);
            sl.leftMargin = dp(16);
            row.addView(side, sl);
        }
        homePage = 0;
        h.post(new Runnable() { public void run() { showPage(homePage); } });
        return row;
    }

    private List<ResolveInfo> homeApps() {
        List<ResolveInfo> out = new ArrayList<ResolveInfo>();
        for (ResolveInfo r : sorted(launchables())) if (!hidden.contains(key(r))) out.add(r);
        // fijadas primero
        List<ResolveInfo> first = new ArrayList<ResolveInfo>();
        for (ResolveInfo r : out) if (pinned.contains(key(r))) first.add(r);
        out.removeAll(first);
        first.addAll(out);
        return first;
    }

    private void showPage(int page) {
        if (homeGrid == null) return;
        List<ResolveInfo> apps = homeApps();
        int per = HOME_COLS * HOME_ROWS;
        int pages = Math.max(1, (apps.size() + per - 1) / per);
        page = Math.max(0, Math.min(pages - 1, page));
        homePage = page;
        homeGrid.removeAllViews();
        for (int rI = 0; rI < HOME_ROWS; rI++) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            for (int c = 0; c < HOME_COLS; c++) {
                int idx = page * per + rI * HOME_COLS + c;
                View cell;
                if (idx < apps.size()) cell = homeTile(apps.get(idx));
                else cell = new View(this);
                line.addView(cell, new LinearLayout.LayoutParams(0, -1, 1f));
            }
            homeGrid.addView(line, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        homeDots.removeAllViews();
        for (int i = 0; i < pages; i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == page ? (P.dark ? 0xFFFFFFFF : 0xFF000000) : (P.dark ? 0x66FFFFFF : 0x44000000));
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(9), dp(9));
            lp.setMargins(dp(5), 0, dp(5), 0);
            homeDots.addView(d, lp);
        }
    }

    private View homeTile(final ResolveInfo r) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(Theme.squircle(getResources(), r.loadIcon(getPackageManager()), dp(110), P.iconBg));
        t.addView(iv, new LinearLayout.LayoutParams(dp(110), dp(110)));
        TextView tv = txt(String.valueOf(r.loadLabel(getPackageManager())), 17, 0xFFFFFFFF);
        tv.setSingleLine(true);
        tv.setPadding(dp(10), dp(3), dp(10), dp(3));
        tv.setBackground(box(0x99202024, 0, 8));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(8);
        t.addView(tv, tl);
        t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { start(r); } });
        t.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { appMenu(r); return true; } });
        return t;
    }

    // ======================= TEMA DUSK (fondo degradado + tarjetas + barra inferior) =======================
    private View buildDusk() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // cabecera: hora grande + fecha a la izquierda, estado a la derecha
        LinearLayout head = new LinearLayout(this);
        head.setPadding(dp(28), dp(22), dp(28), 0);
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        clock = txt("", 40, P.text);
        clock.setTypeface(P.fontBold);
        when.addView(clock);
        date = txt("", 20, P.text);
        when.addView(date);
        head.addView(when, new LinearLayout.LayoutParams(0, -2, 1f));
        chips = txt("", 14, P.text);
        chips.setGravity(Gravity.CENTER);
        chips.setPadding(dp(18), dp(10), dp(18), dp(10));
        chips.setBackground(box(P.panel, P.border, 22));
        head.addView(chips, new LinearLayout.LayoutParams(-2, -2));
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // fila de tarjetas: ocupa el alto restante con peso (no alto fijo -> no desborda)
        LinearLayout cards = new LinearLayout(this);
        cards.setPadding(dp(24), dp(16), dp(24), dp(14));

        LinearLayout sp = dCard("Velocidad");
        gauge = new Views.Gauge(this);
        sp.addView(gauge, new LinearLayout.LayoutParams(-1, 0, 1f));
        cards.addView(sp, dWeight(0.9f, true));

        LinearLayout car = dCard("Mi RAV4");
        car.addView(carImage(), new LinearLayout.LayoutParams(-1, 0, 1f));
        car.addView(tripRow(true));
        addSlot(cards, "dusk.slot1", car, dWeight(1.45f, true));

        LinearLayout ctl = dCard("Controles");
        View q = quickControls();
        ctl.addView(q, new LinearLayout.LayoutParams(-1, 0, 1f));
        addSlot(cards, "dusk.slot2", ctl, dWeight(0.8f, true));

        LinearLayout gps = dCard("GPS");
        gnssMini = txt("", 15, P.text);
        gnssMini.setLineSpacing(0, 1.3f);
        gps.addView(gnssMini, new LinearLayout.LayoutParams(-1, 0, 1f));
        addSlot(cards, "dusk.slot3", gps, dWeight(0.85f, false));
        col.addView(cards, new LinearLayout.LayoutParams(-1, 0, 1f));

        // barra inferior con las apps (empieza por la izquierda, lado del conductor)
        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(16), dp(6), dp(16), dp(6));
        dock.setBackgroundColor(P.dark ? 0xF0161618 : 0xF2FFFFFF);
        col.addView(dock, new LinearLayout.LayoutParams(-1, dp(96)));
        return col;
    }

    private LinearLayout dCard(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(P.panel, P.border, P.radius));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setClipToPadding(true);
        TextView t = txt(title, 13, P.dim);
        t.setTypeface(P.fontBold);
        t.setSingleLine(true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.bottomMargin = dp(10);
        c.addView(t, tl);
        return c;
    }

    private LinearLayout.LayoutParams dWeight(float w, boolean gap) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -1, w);
        if (gap) l.rightMargin = dp(12);
        return l;
    }

    // ======================= TEMA GLASS (estilo iOS / CarPlay) =======================
    private View buildGlass() {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));

        // barra lateral tipo CarPlay
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        side.setBackground(box(P.panel, P.border, P.radius));
        side.setPadding(0, dp(14), 0, dp(14));
        clock = txt("", 22, P.text);
        clock.setTypeface(P.fontBold);
        side.addView(clock);
        chips = txt("", 11, P.dim);
        chips.setGravity(Gravity.CENTER);
        side.addView(chips);
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        dlp.topMargin = dp(10);
        side.addView(dock, dlp);
        row.addView(side, new LinearLayout.LayoutParams(dp(124), -1));

        // widgets
        LinearLayout grid = new LinearLayout(this);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(0, -1, 1f);
        gp.leftMargin = dp(14);
        row.addView(grid, gp);

        // velocidad
        LinearLayout sp = gCard();
        sp.addView(gTitle("Velocidad"));
        gauge = new Views.Gauge(this);
        sp.addView(gauge, new LinearLayout.LayoutParams(-1, 0, 1f));
        grid.addView(sp, weight(1f, dp(14)));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        grid.addView(right, new LinearLayout.LayoutParams(0, -1, 1.35f));

        // auto
        LinearLayout car = gCard();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(gTitle("Mi RAV4"), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView reset = txt("Reiniciar", 14, P.accent);
        reset.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { resetTrip(); } });
        head.addView(reset);
        car.addView(head);
        car.addView(carImage(), new LinearLayout.LayoutParams(-1, 0, 1f));
        car.addView(tripRow(true));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, 0, 1.45f);
        cl.bottomMargin = dp(14);
        addSlot(right, "glass.slot1", car, cl);

        // fila inferior: fecha + gps
        LinearLayout bottom = new LinearLayout(this);
        right.addView(bottom, new LinearLayout.LayoutParams(-1, 0, 0.8f));
        LinearLayout dc = gCard();
        dc.setGravity(Gravity.CENTER_VERTICAL);
        date = txt("", 16, P.err);
        date.setTypeface(P.fontBold);
        dc.addView(date);
        TextView bigTime = txt("", 44, P.text);
        bigTime.setTypeface(Typeface_light());
        bigTime.setTag("bigclock");
        dc.addView(bigTime);
        addSlot(bottom, "glass.slot2", dc, weight(1f, dp(14)));
        LinearLayout gc = gCard();
        gc.addView(gTitle("GPS"));
        gnssMini = txt("", 15, P.text);
        gnssMini.setLineSpacing(0, 1.2f);
        gc.addView(gnssMini);
        addSlot(bottom, "glass.slot3", gc, weight(1f, 0));
        return row;
    }

    private android.graphics.Typeface Typeface_light() {
        return android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL);
    }

    private LinearLayout gCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(P.panel, P.border, P.radius));
        c.setPadding(dp(20), dp(16), dp(20), dp(16));
        c.setClipToPadding(true);
        return c;
    }

    private TextView gTitle(String s) {
        TextView t = txt(s, 17, P.text);
        t.setTypeface(P.fontBold);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    // ======================= piezas compartidas =======================
    private View carImage() {
        ImageView img = new ImageView(this);
        img.setImageResource(getResources().getIdentifier("rav4", "drawable", getPackageName()));
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setPadding(dp(6), dp(4), dp(6), dp(4));
        return img;
    }

    private View tripRow(boolean glass) {
        LinearLayout trip = new LinearLayout(this);
        tDist = stat(trip, glass ? "Distancia" : "dist");
        tTime = stat(trip, glass ? "En movimiento" : "moving");
        tAvg = stat(trip, glass ? "Promedio" : "avg");
        tMax = stat(trip, glass ? "Máxima" : "max");
        return trip;
    }

    private TextView stat(LinearLayout parent, String k) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(2), 0, dp(2), 0);
        TextView lab = txt(k, 12, P.dim);
        lab.setSingleLine(true);
        lab.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.addView(lab);
        TextView v = txt("--", 20, P.text);
        v.setTypeface(P.fontBold);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        parent.addView(b, lp);
        return v;
    }

    private View gearButton() {
        TextView g = txt("⚙", 26, P.dim);
        g.setGravity(Gravity.CENTER);
        g.setPadding(dp(12), dp(4), dp(8), dp(4));
        g.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openSettings(); } });
        return g;
    }

    private LinearLayout.LayoutParams weight(float w, int right) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -1, w);
        l.rightMargin = right;
        if (!P.glass) l.topMargin = dp(6);
        return l;
    }

    // ======================= dock =======================
    private void rebuildDock() {
        if (dock == null) return;
        dock.removeAllViews();
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> items = new LinkedHashMap<String, ResolveInfo>();
        List<ResolveInfo> all = launchables();
        for (ResolveInfo r : all) {
            String s = (r.activityInfo.packageName + " " + r.loadLabel(pm)).toLowerCase(Locale.ROOT);
            for (String k : PROJECTION_KEYS) if (s.contains(k)) { items.put(key(r), r); break; }
        }
        for (String pkg : DOCK_FIXED) for (ResolveInfo r : all) if (r.activityInfo.packageName.equals(pkg)) items.put(key(r), r);
        // las que el usuario quitó del dock (aunque se agreguen solas) no vuelven
        Set<String> undock = prefs.getStringSet("undock", new HashSet<String>());
        for (String k : undock) items.remove(k);
        for (ResolveInfo r : all) if (pinned.contains(key(r))) items.put(key(r), r);
        // si quedó vacío, usar las primeras apps visibles (igual que el dock del web)
        if (items.isEmpty()) for (ResolveInfo r : sorted(all)) { if (!hidden.contains(key(r)) && !undock.contains(key(r))) items.put(key(r), r); if (items.size() >= 5) break; }
        // Ajustes Launcher siempre en el dock, en todos los temas
        for (ResolveInfo r : all) if (r.activityInfo.name.equals(SettingsActivity.class.getName())) items.put(key(r), r);
        dockKeys = new HashSet<String>(items.keySet());

        // 3) apps
        int max = P.dusk ? 10 : P.glass ? 6 : 10, i = 0;
        for (ResolveInfo r : items.values()) {
            if (i++ >= max) break;
            if (P.cockpit || P.home) {
                if (i > 5) break;
                View t = tile(r, 64, false);
                dock.addView(t, new LinearLayout.LayoutParams(-1, dp(84)));
                continue;
            }
            if (P.dusk) {
                View t = tile(r, 52, false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(78), -1);
                dock.addView(t, lp);
                continue;
            }
            View t = tile(r, P.glass ? 60 : 54, !P.glass);
            dock.addView(t, P.glass ? new LinearLayout.LayoutParams(-1, 0, 1f) : new LinearLayout.LayoutParams(0, -1, 1f));
        }
    }

    // ======================= controles rápidos =======================
    private TextView volLabel, brLabel;

    /** Bloque 2x3: volumen −/+ , brillo −/+ , modo claro/oscuro, silencio. */
    private View quickControls() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setGravity(Gravity.CENTER);
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER); r2.setGravity(Gravity.CENTER);
        volLabel = txt("", P.glass ? 12 : 13, P.text);
        brLabel = txt("", P.glass ? 12 : 13, P.text);
        r1.addView(qBtn("−", new View.OnClickListener() { public void onClick(View v) { vol(-1); } }));
        r1.addView(qLabel(volLabel));
        r1.addView(qBtn("+", new View.OnClickListener() { public void onClick(View v) { vol(1); } }));
        r2.addView(qBtn("☀−", new View.OnClickListener() { public void onClick(View v) { bright(-1); } }));
        r2.addView(qLabel(brLabel));
        r2.addView(qBtn("☀+", new View.OnClickListener() { public void onClick(View v) { bright(1); } }));
        g.addView(r1);
        g.addView(r2);
        View modeB = qBtn(P.dark ? "☀" : "☾", new View.OnClickListener() { public void onClick(View v) { toggleMode(); } });
        View muteB = qBtn("🔇", new View.OnClickListener() { public void onClick(View v) { mute(); } });
        if (P.glass) {
            LinearLayout r3 = new LinearLayout(this);
            r3.setGravity(Gravity.CENTER);
            r3.addView(modeB);
            r3.addView(muteB);
            g.addView(r3);
        } else { r1.addView(muteB); r2.addView(modeB); }
        updateQuick();
        return g;
    }

    private TextView qBtn(String t, View.OnClickListener l) {
        TextView b = txt(t, P.glass ? 15 : 16, P.text);
        b.setGravity(Gravity.CENTER);
        b.setBackground(box(P.glass ? P.track : P.iconBg, P.glass ? 0 : P.border, P.glass ? 18 : 8));
        int s = P.glass ? 34 : 40;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(s), dp(s));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private View qLabel(TextView t) {
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(P.glass ? 30 : 46), -2));
        return t;
    }

    // ======================= volumen real de la radio NWD (MCU) =======================
    // Descubierto en com.nwd.setting.service: el volumen vive en Settings.System "mcu_system_volume"
    // (0..mcu_max_volume, normalmente 40) y se cambia con el broadcast ACTION_REQUEST_SET_SYSTEM_VOLUME.
    private int nwdMax() {
        return android.provider.Settings.System.getInt(getContentResolver(), "mcu_max_volume", 40);
    }

    private int nwdVolume() {
        return android.provider.Settings.System.getInt(getContentResolver(), "mcu_system_volume", -1);
    }

    private boolean isNwd() { return nwdVolume() >= 0; }

    private void nwdSetVolume(int v) {
        v = Math.max(0, Math.min(nwdMax(), v));
        Intent i = new Intent("com.nwd.action.ACTION_REQUEST_SET_SYSTEM_VOLUME");
        i.putExtra("extra_volume", v);
        sendBroadcast(i);
    }

    private int volNow() {
        if (isNwd()) return nwdVolume();
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        return am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
    }

    private int volMax() {
        if (isNwd()) return nwdMax();
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        return am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
    }

    private void updateQuick() {
        if (volLabel == null) return;
        int v = volNow();
        volLabel.setText(P.glass ? String.valueOf(v) : "v" + v);
        try {
            int b = android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS);
            int pct = Math.round(b * 100f / 255f);
            brLabel.setText(P.glass ? String.valueOf(pct) : pct + "%");
        } catch (Exception e) { brLabel.setText("--"); }
    }

    private void vol(int dir) {
        if (isNwd()) {
            nwdSetVolume(nwdVolume() + (dir > 0 ? 1 : -1));
            h.postDelayed(new Runnable() { public void run() { updateQuick(); if (isOpen(cc)) updateCC(); } }, 250);
            return;
        }
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        int before = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int d = dir > 0 ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER;
        // 1) igual que las teclas físicas de volumen (deja que el sistema/fabricante elija el canal)
        try { am.adjustSuggestedStreamVolume(d, android.media.AudioManager.USE_DEFAULT_STREAM_TYPE, android.media.AudioManager.FLAG_SHOW_UI); } catch (Exception e) { }
        // 2) respaldo: canal de música directo
        if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == before)
            try { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, d, 0); } catch (Exception e) { }
        updateQuick();
        if (isOpen(cc)) updateCC();
    }

    private int volBeforeMute = -1;

    private void mute() {
        if (isNwd()) {
            if (nwdVolume() > 0) { volBeforeMute = nwdVolume(); nwdSetVolume(0); }
            else nwdSetVolume(volBeforeMute > 0 ? volBeforeMute : 10);
            h.postDelayed(new Runnable() { public void run() { updateQuick(); if (isOpen(cc)) updateCC(); } }, 250);
            return;
        }
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_TOGGLE_MUTE,
                android.media.AudioManager.FLAG_SHOW_UI);
        updateQuick();
    }

    private void bright(int dir) {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.System.canWrite(this)) {
            // permiso especial: Android pide autorizarlo una sola vez
            try {
                startActivity(new Intent("android.settings.action.MANAGE_WRITE_SETTINGS",
                        Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) { }
            return;
        }
        try {
            android.content.ContentResolver cr = getContentResolver();
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            int b = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
            b = Math.max(MIN_BRIGHTNESS, Math.min(255, b + dir * 25));
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, b);
        } catch (Exception e) { }
        updateQuick();
    }

    /** Si algo (el launcher, la radio u otra app) dejó el brillo por debajo del mínimo, lo sube. */
    private void enforceMinBrightness() {
        try {
            android.content.ContentResolver cr = getContentResolver();
            int b = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
            if (b < MIN_BRIGHTNESS && (Build.VERSION.SDK_INT < 23 || android.provider.Settings.System.canWrite(this)))
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, MIN_BRIGHTNESS);
        } catch (Exception e) { }
        // también la ventana del launcher nunca por debajo del mínimo
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.screenBrightness >= 0 && lp.screenBrightness < MIN_BRIGHTNESS / 255f) {
            lp.screenBrightness = MIN_BRIGHTNESS / 255f;
            getWindow().setAttributes(lp);
        }
    }

    private int lastAutoPct = -1;

    /** Brillo automático por tramos horarios (pref "autobright" on/off, "autobright.bands" = "hora:porcentaje,..").
     *  El tramo vigente es el de mayor hora <= ahora (con vuelta a medianoche). Se aplica solo al cambiar de
     *  tramo, así un ajuste manual dentro del mismo tramo se respeta hasta la próxima frontera. */
    private void applyAutoBrightness() {
        if (!"on".equals(prefs.getString("autobright", "off"))) { lastAutoPct = -1; return; }
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.System.canWrite(this)) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        float hr = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f;
        int target = Views.brightAt(prefs.getString("autobright.bands", "7:90,19:55,22:25"),
                prefs.getString("autobright.shape", "curve"), hr);
        if (target < 0) return;
        int goal = Math.max(MIN_BRIGHTNESS, Math.min(255, Math.round(target * 255f / 100f)));
        if (target != lastAutoPct) { lastAutoPct = target; fadeGoal = goal; }
        if (fadeGoal < 0) return;
        try {
            android.content.ContentResolver cr = getContentResolver();
            int cur = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, goal);
            int next = goal;
            // transición suave: llega al tramo nuevo en ~30 s (un paso por segundo)
            if ("on".equals(prefs.getString("autobright.fade", "on")) && Math.abs(goal - cur) > 3) {
                int step = Math.max(2, Math.abs(goal - cur) / 30 + 1);
                next = cur + (goal > cur ? Math.min(step, goal - cur) : -Math.min(step, cur - goal));
            }
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, next);
            if (next == goal) fadeGoal = -1;   // llegó: desde aquí se respeta el ajuste manual
        } catch (Exception e) { fadeGoal = -1; }
    }

    private int fadeGoal = -1;

    /** Porcentaje de brillo del tramo que cubre la hora dada; -1 si no hay tramos definidos. */
    private int autoBrightnessFor(int hour) {
        String[] bands = prefs.getString("autobright.bands", "7:90,19:55,22:25").split(",");
        int best = -1, bestHour = -1, lastPct = -1, lastHour = -1;
        for (String band : bands) {
            String[] hp = band.split(":");
            if (hp.length != 2) continue;
            try {
                int bh = Integer.parseInt(hp[0].trim()), bp = Integer.parseInt(hp[1].trim());
                if (bh > lastHour) { lastHour = bh; lastPct = bp; }   // tramo más tardío, por si ninguno cubre
                if (bh <= hour && bh > bestHour) { bestHour = bh; best = bp; }
            } catch (Exception e) { }
        }
        return best >= 0 ? best : lastPct;   // antes del primer tramo del día: aplica el último (envuelve medianoche)
    }

    private void toggleMode() {
        prefs.edit().putString("mode", P.dark ? Theme.LIGHT : Theme.DARK).apply();
        applyTheme();
    }

    private Drawable icon(ResolveInfo r, int sizeDp) { return appIcon(r); }

    private final Map<String, Drawable> customIcons = new java.util.HashMap<String, Drawable>();

    /** Ícono real de la app, sin placa; o el que el usuario eligió en Ajustes Launcher → Apps (pref "icon.<clave>"). */
    private Drawable appIcon(ResolveInfo r) {
        String path = prefs.getString("icon." + key(r), null);
        if (path != null) {
            Drawable d = customIcons.get(path);
            if (d == null) {
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(path);
                if (b != null) { d = new android.graphics.drawable.BitmapDrawable(getResources(), b); customIcons.put(path, d); }
            }
            if (d != null) return d;
        }
        return r.loadIcon(getPackageManager());
    }

    private View tile(final ResolveInfo r, int iconDp, boolean label) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon(r, iconDp));
        t.addView(iv, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
        if (label) {
            TextView tv = txt(String.valueOf(r.loadLabel(getPackageManager())), 12, P.glass ? P.text : P.dim);
            tv.setTypeface(P.glass ? P.font : android.graphics.Typeface.DEFAULT);
            tv.setSingleLine(true);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(4), dp(6), dp(4), 0);
            t.addView(tv, new LinearLayout.LayoutParams(-1, -2));
        }
        t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { start(r); } });
        t.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { appMenu(r); return true; }
        });
        return t;
    }

    private void start(ResolveInfo r) {
        if (r.activityInfo.name.equals(SettingsActivity.class.getName())) { openLauncherSettings(); return; }
        Intent it = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(r.activityInfo.packageName, r.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(it); } catch (Exception e) { }
    }

    // Apps del sistema que NUNCA se deben quitar: la radio no arrancaría bien.
    private static final java.util.Set<String> PROTECTED = new java.util.HashSet<String>(java.util.Arrays.asList(
            "com.nwd.kernel", "com.nwd.setting.service", "com.nwd.can.setting", "com.nwd.can.help",
            "com.android.systemui", "com.nwd.audioset", "com.nwd.media.scanner", "com.nwd.install.ex.service",
            "com.spreadtrum.sgps", "com.unisoc.reversecamera", "com.nwd.mycar", "android"));

    private int alertStyle() {
        return P.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    private void appMenu(final ResolveInfo r) {
        final String k = key(r);
        final String pkg = r.activityInfo.packageName;
        final boolean isHidden = hidden.contains(k), isPinned = pinned.contains(k) || dockKeys.contains(k);
        final boolean isSettings = r.activityInfo.name.equals(SettingsActivity.class.getName());
        final String[] opts = isSettings ? new String[]{"Abrir Ajustes Launcher"} : new String[]{isPinned ? "Quitar del dock" : "Fijar en el dock",
                isHidden ? "Mostrar" : "Ocultar", "Info de la app", "Desinstalar de la radio"};
        new AlertDialog.Builder(this, alertStyle())
                .setTitle(r.loadLabel(getPackageManager()))
                .setItems(opts, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (isSettings) { openLauncherSettings(); return; }
                        if (w == 0) setDocked(k, !isPinned);
                        if (w == 1) toggleHidden(k);
                        if (w == 2) appInfo(pkg);
                        if (w == 3) confirmUninstall(r, pkg);
                    }
                }).show();
    }

    private void confirmUninstall(ResolveInfo r, final String pkg) {
        String label = String.valueOf(r.loadLabel(getPackageManager()));
        if (PROTECTED.contains(pkg)) {
            new AlertDialog.Builder(this, alertStyle())
                    .setTitle("No se puede quitar")
                    .setMessage(label + " es parte del sistema de la radio. Quitarla puede dejarla sin arrancar, sin sonido o sin cámara de reversa, así que está protegida.")
                    .setPositiveButton("Entendido", null).show();
            return;
        }
        boolean system = false;
        try { system = (getPackageManager().getApplicationInfo(pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0; }
        catch (Exception e) { }
        final boolean sys = system;
        new AlertDialog.Builder(this, alertStyle())
                .setTitle("Desinstalar " + label + "?")
                .setMessage((sys ? "⚠️  Es una app del sistema NWD.\n\n" : "")
                        + "La radio ejecutará:\n   pm uninstall " + pkg + "\n\n"
                        + "Se intentará quitar como administrador del sistema. "
                        + (sys ? "Si la radio queda inestable, restaura el firmware. " : "")
                        + "¿Continuar?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desinstalar", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { nwdUninstall(pkg); }
                }).show();
    }

    /** Pide a la radio (servicio KernelService, con privilegios de sistema) ejecutar pm uninstall. */
    private void nwdUninstall(String pkg) {
        Intent i = new Intent("com.nwd.action.ACTION_SILENT_UNINSTALL_APK");
        i.putExtra("pack_name", pkg);
        try { sendBroadcast(i); } catch (Exception e) { }
        android.widget.Toast.makeText(this, "Orden enviada: se intentará quitar " + pkg
                + ". Si sigue apareciendo, la radio no permitió quitarla.", android.widget.Toast.LENGTH_LONG).show();
        h.postDelayed(new Runnable() { public void run() { rebuildDock(); refreshDrawer(); if (homeGrid != null) showPage(homePage); if (web != null) web.appsChanged(); } }, 2500);
    }

    private void setDocked(String k, boolean on) {
        Set<String> undock = new HashSet<String>(prefs.getStringSet("undock", new HashSet<String>()));
        if (on) { pinned.add(k); undock.remove(k); } else { pinned.remove(k); undock.add(k); }
        prefs.edit().putStringSet("undock", undock).apply();
        saveSets(); rebuildDock(); refreshDrawer(); if (web != null) web.appsChanged();
    }

    private void togglePin(String k) { if (!pinned.remove(k)) pinned.add(k); saveSets(); rebuildDock(); refreshDrawer(); if (web != null) web.appsChanged(); }
    private void toggleHidden(String k) { if (!hidden.remove(k)) hidden.add(k); saveSets(); rebuildDock(); refreshDrawer(); if (web != null) web.appsChanged(); }

    private void appInfo(String pkg) {
        try {
            startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) { }
    }

    // ======================= cajón de apps (páginas horizontales, como el Home) =======================
    private final List<ResolveInfo> shown = new ArrayList<ResolveInfo>();
    private LinearLayout drawerGrid, drawerDots;
    private int drawerPage = 0;
    private int DRAWER_COLS = 6, DRAWER_ROWS = 3, drawerIconDp = 104;
    private TextView drawerCount;

    private View buildDrawer() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        int bgc = P.dark ? 0xFF0E0F13 : 0xFFF2F2F7;
        d.setBackgroundColor(bgc);
        d.setPadding(dp(40), dp(26), dp(40), dp(10));
        d.setClickable(true);

        // cabecera: título grande + contador + botón cerrar
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView dt = txt("Aplicaciones", 32, P.text);
        dt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titles.addView(dt);
        drawerCount = txt("", 15, P.dim);
        titles.addView(drawerCount);
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = txt("Cerrar", 17, 0xFFFFFFFF);
        close.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(26), dp(12), dp(26), dp(12));
        close.setBackground(box(P.accent, P.accent, 24));
        close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeDrawer(); } });
        top.addView(close);
        d.addView(top);

        // separador fino
        View sep = new View(this);
        sep.setBackgroundColor(P.dark ? 0x22FFFFFF : 0x22000000);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 1);
        sl.setMargins(0, dp(18), 0, dp(8));
        d.addView(sep, sl);

        drawerGrid = new LinearLayout(this);
        drawerGrid.setOrientation(LinearLayout.VERTICAL);
        d.addView(drawerGrid, new LinearLayout.LayoutParams(-1, 0, 1f));

        drawerDots = new LinearLayout(this);
        drawerDots.setGravity(Gravity.CENTER);
        d.addView(drawerDots, new LinearLayout.LayoutParams(-1, dp(26)));

        TextView hint = txt("Desliza ← / → para ver más apps · mantén presionada una app para fijarla, ocultarla o ver su información", 13, P.dim);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, dp(4));
        d.addView(hint);
        return d;
    }

    /** Ícono grande con forma redondeada + nombre en dos líneas, sin cajas. */
    private View drawerTile(final ResolveInfo r) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(appIcon(r));   // ícono real (o personalizado), sin placa
        t.addView(iv, new LinearLayout.LayoutParams(dp(drawerIconDp), dp(drawerIconDp)));
        TextView tv = txt(String.valueOf(r.loadLabel(getPackageManager())), 14, P.text);
        tv.setTypeface(Typeface.DEFAULT);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(2);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(10);
        t.addView(tv, tl);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction(new Runnable() {
                    public void run() { start(r); }
                }).start();
            }
        });
        t.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { appMenu(r); return true; } });
        return t;
    }

    /** true = entra desde la izquierda (deslizar →), false = desde la derecha (deslizar ←). */
    private boolean drawerFromLeft = true;

    private void openDrawer(boolean fromLeft) {
        drawerFromLeft = fromLeft;
        drawerPage = 0;
        applyDrawerBackground();
        refreshDrawer();
        drawer.setTranslationX(fromLeft ? -root.getWidth() : root.getWidth());
        drawer.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0).setDuration(220).start();
    }

    /** Fondo del cajón: opacidad configurable y, si se pide, la pantalla de atrás difuminada (una sola captura al abrir). */
    private void applyDrawerBackground() {
        int alpha = 100;
        try { alpha = Integer.parseInt(prefs.getString("drawer.alpha", "100")); } catch (Exception e) { }
        int base = (P.dark ? 0x0E0F13 : 0xF2F2F7) | (Math.round(alpha * 2.55f) << 24);
        if (!"on".equals(prefs.getString("drawer.blur", "off")) || root.getWidth() == 0) { drawer.setBackgroundColor(base); return; }
        try {
            int w = root.getWidth() / 8, hh = root.getHeight() / 8;
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, hh, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            c.scale(1 / 8f, 1 / 8f);
            root.draw(c);
            Views.boxBlur(b, 3);
            android.graphics.drawable.Drawable[] layers = {
                    new android.graphics.drawable.BitmapDrawable(getResources(), b), new android.graphics.drawable.ColorDrawable(base)};
            drawer.setBackground(new android.graphics.drawable.LayerDrawable(layers));
        } catch (Exception e) { drawer.setBackgroundColor(base); }
    }

    private void closeDrawer() {
        if (!isOpen(drawer)) return;
        drawer.animate().translationX(drawerFromLeft ? -root.getWidth() : root.getWidth()).setDuration(200).withEndAction(new Runnable() {
            public void run() { drawer.setVisibility(View.GONE); immersive(); }
        }).start();
    }

    /** Indicadores discretos de los gestos. */
    private View hints() {
        FrameLayout f = new FrameLayout(this);
        TextView top = txt("︾", 14, (P.dim & 0x00FFFFFF) | 0x66000000);
        f.addView(top, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        TextView left = txt("›", 30, (P.dim & 0x00FFFFFF) | 0x66000000);
        left.setPadding(dp(2), 0, 0, 0);
        f.addView(left, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        TextView right = txt("‹", 30, (P.dim & 0x00FFFFFF) | 0x66000000);
        right.setPadding(0, 0, dp(2), 0);
        f.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        return f;
    }

    // ======================= CENTRO DE CONTROL (deslizar ↓) =======================
    private View cc;
    private TextView ccNet, ccBt, ccSys, ccWifiT, ccBtT, ccModeT, ccThemeT, ccMuteT, ccGpsT, ccVolV, ccBrV;
    private android.widget.SeekBar ccVol, ccBr;
    private boolean ccUserTouch = false;

    private View buildCC() {
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        int bgc = P.glass ? (P.dark ? 0xF21C1C1E : 0xF2F2F2F7) : ((P.bg & 0x00FFFFFF) | 0xF7000000);
        sheet.setBackground(box(bgc, P.border, P.glass ? 28 : P.radius));
        sheet.setPadding(dp(22), dp(16), dp(22), dp(8));
        sheet.setClickable(true);

        // título
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = txt(P.glass ? "Centro de control" : "$ systemctl status rav4", 20, P.text);
        t.setTypeface(P.fontBold);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView gear = txt(P.glass ? "Ajustes del launcher ›" : "> launcher --config", 15, P.accent);
        gear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeCC(); openSettings(); } });
        head.addView(gear);
        sheet.addView(head);

        // fila 1: interruptores
        LinearLayout toggles = new LinearLayout(this);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, dp(84));
        tl.topMargin = dp(12);
        sheet.addView(toggles, tl);
        ccWifiT = toggle(toggles, "WiFi", new View.OnClickListener() { public void onClick(View v) { toggleWifi(); } });
        ccBtT = toggle(toggles, "Bluetooth", new View.OnClickListener() { public void onClick(View v) { toggleBt(); } });
        ccModeT = toggle(toggles, "Modo", new View.OnClickListener() { public void onClick(View v) { cycleMode(); } });
        ccThemeT = toggle(toggles, "Tema", new View.OnClickListener() { public void onClick(View v) { switchTheme(); } });
        ccMuteT = toggle(toggles, "Silencio", new View.OnClickListener() { public void onClick(View v) { mute(); updateCC(); } });
        ccGpsT = toggle(toggles, "GPS", new View.OnClickListener() {
            public void onClick(View v) { sysIntent("android.settings.LOCATION_SOURCE_SETTINGS"); }
        });

        // fila 2: deslizadores
        LinearLayout sliders = new LinearLayout(this);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(78));
        sl.topMargin = dp(10);
        sheet.addView(sliders, sl);
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        ccVolV = new TextView(this);
        ccVol = slider(sliders, "🔊 Volumen", ccVolV, volMax(), dp(10),
                new SliderCb() { public void set(int v) { setVolume(v); } });
        ccBrV = new TextView(this);
        ccBr = slider(sliders, "☀ Brillo", ccBrV, 255, 0, new SliderCb() { public void set(int v) { setBrightness(v); } });
        if (Build.VERSION.SDK_INT >= 26) ccBr.setMin(MIN_BRIGHTNESS);

        // fila 3: detalles
        LinearLayout info = new LinearLayout(this);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, 0, 1f);
        il.topMargin = dp(10);
        sheet.addView(info, il);
        ccNet = infoCard(info, P.glass ? "Red" : "# net", dp(10));
        ccBt = infoCard(info, P.glass ? "Bluetooth" : "# bluetooth", dp(10));
        ccSys = infoCard(info, P.glass ? "Sistema" : "# system", 0);

        // fila 4: accesos a ajustes
        LinearLayout sc = new LinearLayout(this);
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(-1, dp(46));
        scl.topMargin = dp(8);
        sheet.addView(sc, scl);
        shortcut(sc, "WiFi", "android.settings.WIFI_SETTINGS");
        shortcut(sc, "Bluetooth", "android.settings.BLUETOOTH_SETTINGS");
        shortcut(sc, "Pantalla", "android.settings.DISPLAY_SETTINGS");
        shortcut(sc, "Sonido", "android.settings.SOUND_SETTINGS");
        shortcut(sc, "Fecha/hora", "android.settings.DATE_SETTINGS");
        shortcut(sc, "Android", "android.settings.SETTINGS");

        TextView handle = txt(P.glass ? "━━━" : "︽  swipe up to close  ︽", 12, P.dim);
        handle.setGravity(Gravity.CENTER);
        handle.setPadding(0, dp(6), 0, 0);
        handle.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeCC(); } });
        sheet.addView(handle, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(0x66000000);
        wrap.setClickable(true);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(-1, -1);
        wl.setMargins(dp(16), dp(10), dp(16), dp(40));
        wrap.addView(sheet, wl);
        return wrap;
    }

    private TextView toggle(LinearLayout parent, String label, View.OnClickListener l) {
        TextView v = txt(label, 13, P.text);
        v.setGravity(Gravity.CENTER);
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.rightMargin = dp(10);
        parent.addView(v, lp);
        return v;
    }

    private void setToggle(TextView v, String title, String state, boolean on) {
        v.setText(title + "\n" + state);
        v.setTextColor(on ? 0xFFFFFFFF : P.text);
        v.setBackground(box(on ? P.accent : (P.glass ? P.track : P.panel), on ? P.accent : P.border, P.glass ? 22 : 10));
    }

    interface SliderCb { void set(int v); }

    private android.widget.SeekBar slider(LinearLayout parent, String label, TextView val, int max, int rightMargin, final SliderCb cb) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(P.glass ? P.track : P.panel, P.border, P.glass ? 22 : 10));
        card.setPadding(dp(14), dp(8), dp(14), dp(4));
        LinearLayout top = new LinearLayout(this);
        top.addView(txt(label, 13, P.dim), new LinearLayout.LayoutParams(0, -2, 1f));
        val.setTextColor(P.text);
        val.setTypeface(P.fontBold);
        val.setTextSize(13);
        top.addView(val);
        if (label.startsWith("🔊")) {
            TextView mn = txt("  −  ", 18, P.accent), pl = txt("  +  ", 18, P.accent);
            mn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { vol(-1); } });
            pl.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { vol(1); } });
            top.addView(mn, 0 + top.getChildCount() - 1);
            top.addView(pl);
        }
        card.addView(top);
        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(max);
        sb.getProgressDrawable().setColorFilter(P.accent, android.graphics.PorterDuff.Mode.SRC_IN);
        sb.getThumb().setColorFilter(P.accent, android.graphics.PorterDuff.Mode.SRC_IN);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) { if (user) cb.set(p); }
            public void onStartTrackingTouch(android.widget.SeekBar s) { ccUserTouch = true; }
            public void onStopTrackingTouch(android.widget.SeekBar s) { ccUserTouch = false; updateCC(); }
        });
        card.addView(sb, new LinearLayout.LayoutParams(-1, dp(40)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.rightMargin = rightMargin;
        parent.addView(card, lp);
        return sb;
    }

    private TextView infoCard(LinearLayout parent, String title, int rightMargin) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(P.glass ? P.track : P.panel, P.border, P.glass ? 22 : 10));
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView tt = txt(title, 13, P.glass ? P.text : P.accent);
        tt.setTypeface(P.fontBold);
        c.addView(tt);
        TextView body = txt("", 12, P.text);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setLineSpacing(0, 1.15f);
        body.setPadding(0, dp(6), 0, 0);
        c.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.rightMargin = rightMargin;
        parent.addView(c, lp);
        return body;
    }

    private void shortcut(LinearLayout parent, String label, final String action) {
        TextView v = txt(P.glass ? label + " ›" : "> " + label.toLowerCase(Locale.ROOT), 13, P.accent);
        v.setGravity(Gravity.CENTER);
        v.setBackground(box(P.glass ? P.track : P.panel, P.border, P.glass ? 18 : 8));
        v.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { sysIntent(action); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.rightMargin = dp(8);
        parent.addView(v, lp);
    }

    private void sysIntent(String action) {
        try { startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { }
    }

    private void openCC(boolean animate) {
        if (cc == null) return;
        updateCC();
        cc.setVisibility(View.VISIBLE);
        if (animate) {
            cc.setTranslationY(-root.getHeight());
            cc.animate().translationY(0).setDuration(240).start();
        } else cc.setTranslationY(0);
    }

    private void closeCC() {
        if (!isOpen(cc)) return;
        cc.animate().translationY(-root.getHeight()).setDuration(200).withEndAction(new Runnable() {
            public void run() { cc.setVisibility(View.GONE); immersive(); }
        }).start();
    }

    private void updateCC() {
        if (cc == null) return;
        if (cc == null || ccNet == null) return;
        // interruptores
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        boolean wOn = wm != null && wm.isWifiEnabled();
        WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
        String ssid = wi != null && wi.getIpAddress() != 0 && wi.getSSID() != null ? wi.getSSID().replace("\"", "") : null;
        setToggle(ccWifiT, "WiFi", !wOn ? "Apagado" : ssid != null ? clip(ssid, 12) : "Sin conexión", wOn);
        BluetoothAdapter bt = null;
        try { bt = BluetoothAdapter.getDefaultAdapter(); } catch (Exception e) { }
        boolean bOn = bt != null && bt.isEnabled();
        setToggle(ccBtT, "Bluetooth", bOn ? "Encendido" : "Apagado", bOn);
        String mode = prefs.getString("mode", Theme.DARK);
        setToggle(ccModeT, P.dark ? "☾ Modo" : "☀ Modo", Theme.AUTO.equals(mode) ? "Auto" : P.dark ? "Oscuro" : "Claro", P.dark);
        setToggle(ccThemeT, "Tema", P.home ? "Inicio" : P.cockpit ? "Cockpit" : P.dusk ? "Dusk" : P.glass ? "Glass" : "Terminal", false);
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        boolean muted = Build.VERSION.SDK_INT >= 23 && am.isStreamMute(android.media.AudioManager.STREAM_MUSIC);
        setToggle(ccMuteT, "Silencio", muted ? "Activado" : "No", muted);
        setToggle(ccGpsT, "GPS", fresh() ? "Con señal" : "Sin señal", fresh());

        // deslizadores
        if (!ccUserTouch) {
            int vol = volNow();
            ccVol.setProgress(vol);
            ccVolV.setText(vol + "/" + volMax());
            try {
                int b = android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS);
                ccBr.setProgress(b);
                ccBrV.setText(Math.round(b * 100f / 255f) + "%");
            } catch (Exception e) { ccBrV.setText("--"); }
        }

        // red
        StringBuilder n = new StringBuilder();
        if (wi != null && wi.getIpAddress() != 0) {
            n.append(kv("ssid", clip(ssid, 16)));
            n.append(kv("ip", ip4(wi.getIpAddress())));
            try {
                android.net.DhcpInfo d = wm.getDhcpInfo();
                n.append(kv("gateway", ip4(d.gateway)));
                n.append(kv("dns", ip4(d.dns1)));
            } catch (Exception e) { }
            n.append(kv("rssi", wi.getRssi() + " dBm " + bars(wi.getRssi())));
            n.append(kv("link", wi.getLinkSpeed() + " Mbps"));
            if (Build.VERSION.SDK_INT >= 21) n.append(kv("freq", wi.getFrequency() + " MHz"));
        } else n.append(kv("wlan0", wOn ? "no conectado" : "apagado"));
        n.append("\n").append(ifaces());
        ccNet.setText(n.toString());

        // bluetooth
        StringBuilder b = new StringBuilder();
        if (bt == null) b.append("no disponible");
        else {
            try { b.append(kv("nombre", clip(bt.getName(), 16))); } catch (Exception e) { }
            b.append(kv("estado", bOn ? "on" : "off"));
            try {
                Set<android.bluetooth.BluetoothDevice> paired = bt.getBondedDevices();
                b.append(kv("vinculados", String.valueOf(paired == null ? 0 : paired.size())));
                if (paired != null) for (android.bluetooth.BluetoothDevice d : paired) b.append("  · ").append(clip(d.getName(), 22)).append('\n');
            } catch (Exception e) { }
        }
        ccBt.setText(b.toString());

        // sistema
        StringBuilder s = new StringBuilder();
        s.append(kv("android", "10 (API " + Build.VERSION.SDK_INT + ")"));
        s.append(kv("soc", "Unisoc UIS8581A"));
        s.append(kv("cpu.temp", cpuTemp()));
        s.append(kv("battery", batteryV >= 0 ? String.format(Locale.US, "%.1f V", batteryV) : "n/a"));
        try {
            ActivityManager acm = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            acm.getMemoryInfo(mi);
            s.append(kv("mem.free", String.format(Locale.US, "%.1f GB", mi.availMem / 1073741824f)));
        } catch (Exception e) { }
        try {
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            s.append(kv("disk.free", String.format(Locale.US, "%.1f GB", st.getAvailableBytes() / 1073741824f)));
        } catch (Exception e) { }
        s.append(kv("uptime", dur(SystemClock.elapsedRealtime())));
        s.append(kv("gnss", satsUsed < 0 ? "n/a" : satsUsed + "/" + satsSeen + " sats"));
        s.append(kv("audio", audioStreams()));
        s.append(kv("launcher", "v1.1"));
        ccSys.setText(s.toString());
    }

    /** Nivel de cada canal de audio: m=música s=sistema r=timbre a=alarma n=notif v=voz. */
    private String audioStreams() {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        int[] st = {3, 1, 2, 4, 5, 0};
        String[] nm = {"m", "s", "r", "a", "n", "v"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < st.length; i++)
            try { b.append(nm[i]).append(am.getStreamVolume(st[i])).append('/').append(am.getStreamMaxVolume(st[i])).append(' '); } catch (Exception e) { }
        return b.toString().trim();
    }

    private String ifaces() {
        StringBuilder s = new StringBuilder();
        try {
            for (java.net.NetworkInterface ni : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address) s.append(kv(ni.getName(), a.getHostAddress()));
            }
        } catch (Exception e) { }
        return s.toString();
    }

    private static String ip4(int ip) {
        return String.format(Locale.US, "%d.%d.%d.%d", ip & 255, ip >> 8 & 255, ip >> 16 & 255, ip >> 24 & 255);
    }

    private static String bars(int rssi) {
        return rssi > -55 ? "▂▄▆█" : rssi > -67 ? "▂▄▆_" : rssi > -78 ? "▂▄__" : "▂___";
    }

    private static String clip(String s, int n) {
        if (s == null) return "?";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    @SuppressWarnings("deprecation")
    private void toggleWifi() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (!wm.setWifiEnabled(!wm.isWifiEnabled())) sysIntent("android.settings.WIFI_SETTINGS");
        } catch (Exception e) { sysIntent("android.settings.WIFI_SETTINGS"); }
        h.postDelayed(new Runnable() { public void run() { updateCC(); } }, 800);
    }

    private void toggleBt() {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            boolean ok = bt.isEnabled() ? bt.disable() : bt.enable();
            if (!ok) sysIntent("android.settings.BLUETOOTH_SETTINGS");
        } catch (Exception e) { sysIntent("android.settings.BLUETOOTH_SETTINGS"); }
        h.postDelayed(new Runnable() { public void run() { updateCC(); } }, 800);
    }

    private void cycleMode() {
        String m = prefs.getString("mode", Theme.DARK);
        String next = Theme.DARK.equals(m) ? Theme.LIGHT : Theme.LIGHT.equals(m) ? Theme.AUTO : Theme.DARK;
        prefs.edit().putString("mode", next).apply();
        applyTheme();
        openCC(false);
    }

    private void switchTheme() {
        String cur = prefs.getString("theme", Theme.TERMINAL);
        String[] order = {Theme.TERMINAL, Theme.GLASS, Theme.DUSK, Theme.COCKPIT, Theme.HOME};
        int ix = 0;
        for (int k = 0; k < order.length; k++) if (order[k].equals(cur)) ix = k;
        String nx = order[(ix + 1) % order.length];
        prefs.edit().putString("theme", nx).apply();
        applyTheme();
        openCC(false);
    }

    private void setVolume(int v) {
        if (isNwd()) nwdSetVolume(v);
        else {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0);
        }
        ccVolV.setText(v + "/" + volMax());
    }

    private void setBrightness(int v) {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.System.canWrite(this)) {
            ccUserTouch = false;
            try {
                startActivity(new Intent("android.settings.action.MANAGE_WRITE_SETTINGS",
                        Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) { }
            return;
        }
        try {
            android.content.ContentResolver cr = getContentResolver();
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, Math.max(MIN_BRIGHTNESS, v));
            ccBrV.setText(Math.round(Math.max(MIN_BRIGHTNESS, v) * 100f / 255f) + "%");
            if (v < MIN_BRIGHTNESS) ccBr.setProgress(MIN_BRIGHTNESS);
        } catch (Exception e) { }
    }

    private void refreshDrawer() {
        if (drawerGrid == null) return;
        shown.clear();
        for (ResolveInfo r : launchables()) if (!hidden.contains(key(r))) shown.add(r);
        sorted(shown);
        // orden personalizado (Ajustes Launcher → Cajón de apps → Orden): las de la lista primero, en ese orden
        if ("custom".equals(prefs.getString("drawer.sort", "name"))) {
            final List<String> order = java.util.Arrays.asList(prefs.getString("drawer.order", "").split(","));
            Collections.sort(shown, new Comparator<ResolveInfo>() {
                public int compare(ResolveInfo a, ResolveInfo b) {
                    int ia = order.indexOf(key(a)), ib = order.indexOf(key(b));
                    if (ia < 0) ia = 9999; if (ib < 0) ib = 9999;
                    return ia - ib;
                }
            });
        }
        if (drawerCount != null) drawerCount.setText(shown.size() + " apps · " + hidden.size() + " ocultas");
        showDrawerPage(drawerPage);
    }

    private void showDrawerPage(int page) {
        if (drawerGrid == null) return;
        String sz = prefs.getString("drawer.size", "m");
        DRAWER_COLS = "s".equals(sz) ? 7 : "l".equals(sz) ? 5 : 6;
        drawerIconDp = "s".equals(sz) ? 84 : "l".equals(sz) ? 124 : 104;
        int per = DRAWER_COLS * DRAWER_ROWS;
        int pages = Math.max(1, (shown.size() + per - 1) / per);
        drawerPage = Math.max(0, Math.min(pages - 1, page));
        drawerGrid.removeAllViews();
        for (int rI = 0; rI < DRAWER_ROWS; rI++) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            for (int c = 0; c < DRAWER_COLS; c++) {
                int idx = drawerPage * per + rI * DRAWER_COLS + c;
                View cell = idx < shown.size() ? drawerTile(shown.get(idx)) : new View(this);
                line.addView(cell, new LinearLayout.LayoutParams(0, -1, 1f));
            }
            drawerGrid.addView(line, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        drawerDots.removeAllViews();
        for (int i = 0; pages > 1 && i < pages; i++) {
            View dt = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == drawerPage ? (P.dark ? 0xFFFFFFFF : 0xFF000000) : (P.dark ? 0x66FFFFFF : 0x44000000));
            dt.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(9), dp(9));
            lp.setMargins(dp(5), 0, dp(5), 0);
            drawerDots.addView(dt, lp);
        }
    }

    // ======================= ajustes del launcher =======================
    private LinearLayout appList;
    private boolean listHiddenOnly = false;

    private View buildSettings() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(0x99000000);
        wrap.setClickable(true);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(box(P.glass ? (P.dark ? 0xFF1C1C1E : 0xFFF2F2F7) : P.bg, P.border, P.glass ? 24 : 14));
        sheet.setPadding(dp(24), dp(18), dp(24), dp(18));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, -1);
        sl.setMargins(dp(60), dp(30), dp(60), dp(30));
        wrap.addView(sheet, sl);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = txt(P.glass ? "Ajustes del launcher" : "$ launcher --config", 22, P.text);
        title.setTypeface(P.fontBold);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = txt(P.glass ? "Listo" : "[ close ]", 18, P.accent);
        done.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { settings.setVisibility(View.GONE); immersive(); }
        });
        head.addView(done);
        sheet.addView(head);

        LinearLayout cols = new LinearLayout(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cp.topMargin = dp(14);
        sheet.addView(cols, cp);

        // --- columna izquierda: apariencia ---
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        ScrollView leftScroll = new ScrollView(this);
        leftScroll.addView(left);
        cols.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, 0.85f));

        left.addView(section("INTERFAZ"));
        left.addView(choice(new String[]{"Nativa", "Web (animada)"}, new String[]{"native", "web"},
                prefs.getString("engine", "native"), "engine"));
        left.addView(section("RELOJ"));
        left.addView(choice(new String[]{"12 h (AM/PM)", "24 h"}, new String[]{"12", "24"},
                prefs.getString("web.clock", "12"), "web.clock"));
        left.addView(section("TEMA"));
        String th = prefs.getString("theme", Theme.TERMINAL);
        String[][] themes = {
                {Theme.TERMINAL, "Terminal", "Estilo consola: telemetría, gráficos y datos técnicos"},
                {Theme.GLASS, "Glass", "Tarjetas translúcidas y barra lateral"},
                {Theme.DUSK, "Dusk", "Fondo degradado, hora grande y tarjetas abajo"},
                {Theme.COCKPIT, "Cockpit", "Panel de tarjetas: velocidad, viaje, auto y música"},
                {Theme.HOME, "Inicio", "Cuadrícula de íconos grandes con páginas"}};
        for (final String[] t : themes) {
            boolean on = t[0].equals(th);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            row.setBackground(box(on ? P.accent : (P.dark ? 0xFF2C2C2E : 0xFFFFFFFF), on ? P.accent : 0x00000000, 14));
            TextView n = txt((on ? "✓  " : "") + t[1], 17, on ? 0xFFFFFFFF : P.text);
            n.setTypeface(P.fontBold);
            row.addView(n);
            row.addView(txt(t[2], 12, on ? 0xDDFFFFFF : P.dim));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    prefs.edit().putString("theme", t[0]).apply();
                    applyTheme();
                    openSettings();
                }
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.bottomMargin = dp(6);
            left.addView(row, rl);
        }
        left.addView(section("FONDO (Cockpit / Inicio)"));
        left.addView(choice(new String[]{"Ondas", "Noche", "Atardecer", "Mi RAV4"},
                new String[]{"ondas", "noche", "atardecer", "rav4"}, prefs.getString("wall", "ondas"), "wall"));
        left.addView(section("APARIENCIA"));
        left.addView(choice(new String[]{"Oscuro", "Claro", "Auto"},
                new String[]{Theme.DARK, Theme.LIGHT, Theme.AUTO}, prefs.getString("mode", Theme.DARK), "mode"));
        TextView autoHint = txt("Auto: oscuro de 18:00 a 06:00", 12, P.dim);
        autoHint.setPadding(0, dp(6), 0, 0);
        left.addView(autoHint);

        left.addView(section("SISTEMA"));
        left.addView(action("Elegir launcher predeterminado", new View.OnClickListener() {
            public void onClick(View v) {
                try { startActivity(new Intent("android.settings.HOME_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { appInfo(getPackageName()); }
            }
        }));
        left.addView(action("Ajustes de Android", new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent("android.settings.SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }));
        left.addView(action("Reiniciar viaje", new View.OnClickListener() { public void onClick(View v) { resetTrip(); } }));
        left.addView(action("Buscar actualización", new View.OnClickListener() {
            public void onClick(View v) { new Updater(HomeActivity.this, alertStyle()).check(); }
        }));
        TextView ver = txt("RAV4 Launcher v" + Updater.currentVersion(this), 12, P.dim);
        ver.setPadding(0, dp(16), 0, 0);
        left.addView(ver);

        // --- columna derecha: apps ---
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1.4f);
        rp.leftMargin = dp(24);
        cols.addView(right, rp);
        LinearLayout ah = new LinearLayout(this);
        ah.setGravity(Gravity.CENTER_VERTICAL);
        ah.addView(section("APPS"), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView filter = txt("", 13, P.accent);
        filter.setPadding(dp(10), dp(12), 0, dp(4));
        filter.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { listHiddenOnly = !listHiddenOnly; fillAppList(filter); }
        });
        ah.addView(filter);
        right.addView(ah);
        ScrollView sv = new ScrollView(this);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(appList);
        right.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        filter.setTag("filter");
        return wrap;
    }

    private void openSettings() {
        if (settings == null) { openLauncherSettings(); return; }
        View f = settings.findViewWithTag("filter");
        listHiddenOnly = false;
        fillAppList((TextView) f);
        settings.setVisibility(View.VISIBLE);
    }

    private void fillAppList(final TextView filter) {
        appList.removeAllViews();
        PackageManager pm = getPackageManager();
        int hc = 0;
        List<ResolveInfo> all = sorted(launchables());
        for (ResolveInfo r : all) if (hidden.contains(key(r))) hc++;
        filter.setText(listHiddenOnly ? "Ver todas" : "Solo ocultas (" + hc + ")");
        for (final ResolveInfo r : all) {
            final String k = key(r);
            if (listHiddenOnly && !hidden.contains(k)) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(6), dp(10), dp(6));
            row.setBackground(box(P.glass ? (P.dark ? 0xFF2C2C2E : 0xFFFFFFFF) : P.panel, P.glass ? 0x00000000 : P.border, P.glass ? 12 : 8));
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon(r, 40));
            row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout names = new LinearLayout(this);
            names.setOrientation(LinearLayout.VERTICAL);
            names.setPadding(dp(12), 0, 0, 0);
            names.addView(txt(String.valueOf(r.loadLabel(pm)), 15, P.text));
            TextView pk = txt(r.activityInfo.packageName, 11, P.dim);
            pk.setTypeface(android.graphics.Typeface.MONOSPACE);
            pk.setSingleLine(true);
            names.addView(pk);
            row.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
            boolean hd = hidden.contains(k), pn = pinned.contains(k);
            row.addView(pill(pn ? "★ En dock" : "☆ Dock", pn, new View.OnClickListener() {
                public void onClick(View v) { setDocked(k, !(pinned.contains(k) || dockKeys.contains(k))); fillAppList(filter); }
            }));
            row.addView(pill(hd ? "Oculta" : "Visible", !hd, new View.OnClickListener() {
                public void onClick(View v) { toggleHidden(k); fillAppList(filter); }
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(6);
            appList.addView(row, lp);
        }
    }

    private TextView pill(String t, boolean on, View.OnClickListener l) {
        TextView v = txt(t, 13, on ? 0xFFFFFFFF : P.dim);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(6), dp(12), dp(6));
        v.setBackground(box(on ? P.accent : 0x00000000, on ? P.accent : P.dim, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104), -2);
        lp.leftMargin = dp(8);
        v.setLayoutParams(lp);
        v.setOnClickListener(l);
        return v;
    }

    private TextView section(String s) {
        TextView t = txt(P.glass ? s : "# " + s.toLowerCase(Locale.ROOT), 12, P.dim);
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private View choice(String[] labels, final String[] values, String current, final String prefKey) {
        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(box(P.glass ? (P.dark ? 0xFF2C2C2E : 0xFFE3E3E8) : P.panel, P.border, 12));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        for (int i = 0; i < labels.length; i++) {
            final String val = values[i];
            boolean on = val.equals(current);
            TextView b = txt(labels[i], 14, on ? 0xFFFFFFFF : P.text);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(8), dp(10), dp(8), dp(10));
            if (on) b.setBackground(box(P.accent, P.accent, 10));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    prefs.edit().putString(prefKey, val).apply();
                    applyTheme();
                    openSettings();
                }
            });
            seg.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return seg;
    }

    private View action(String t, View.OnClickListener l) {
        TextView v = txt(P.glass ? t + "  ›" : "> " + t, 15, P.accent);
        v.setPadding(dp(4), dp(10), dp(4), dp(10));
        v.setOnClickListener(l);
        return v;
    }

    // ======================= datos en vivo =======================
    private final Runnable tick = new Runnable() {
        public void run() {
            if (web != null) {
                enforceMinBrightness();
            applyAutoBrightness();
                applyAutoBrightness();
                if ((SystemClock.elapsedRealtime() / 1000) % 3 == 0)
                    try { sendBroadcast(new Intent("com.nwd.action.ACTION_QUERY_CAN_BATTERY_VOLTAGE")); } catch (Exception e) { }
                if (gpsOn && !fresh()) curKmh = 0;
                h.postDelayed(this, 1000);
                return;
            }
            // modo auto: cambia claro/oscuro según la hora
            if (Theme.AUTO.equals(prefs.getString("mode", Theme.DARK)) && Theme.isDarkNow(Theme.AUTO) != lastDark
                    && (settings == null || settings.getVisibility() != View.VISIBLE)) applyTheme();

            Date now = new Date();
            boolean fr = fresh();
            enforceMinBrightness();
            if ((SystemClock.elapsedRealtime() / 1000) % 3 == 0)
                try { sendBroadcast(new Intent("com.nwd.action.ACTION_QUERY_CAN_BATTERY_VOLTAGE")); } catch (Exception e) { }
            if (gpsOn && !fr) { curKmh = 0; feedGauge(0); if (lastFix > 0) feedGaugeSub(P.glass ? "Sin señal GPS" : "gps: signal lost"); }
            Views.Spark.push(curKmh);
            feedCockpitLive();
            if (isOpen(cc)) updateCC();
            updateQuick();
            if (spark != null) spark.invalidate();

            if (!P.glass) {
                clock.setText(clockText(now, true));
                date.setText(new SimpleDateFormat("EEE dd/MM/yyyy", new Locale("es")).format(now));
                prompt.setText("rav4@k4811:~$ " + (fr ? "driving" : "idle"));
                chips.setText(chipLine());
                if (sysPanel != null) sysPanel.setText(telemetry());
            } else {
                if (clock != null) clock.setText(clockText(now, false));
                if (chips != null) chips.setText((wifiConnected() ? "WiFi" : "—") + "  " + (btOn() ? "BT" : "") + (batteryV >= 0 ? "\n" + batteryText() : ""));
                if (date != null) date.setText(new SimpleDateFormat("EEEE d 'de' MMMM", new Locale("es")).format(now).toUpperCase(new Locale("es")));
                View big = root.findViewWithTag("bigclock");
                if (big != null) ((TextView) big).setText(clockText(now, false));
                View dn = root.findViewWithTag("dayname");
                if (dn != null) ((TextView) dn).setText(new SimpleDateFormat("EEEE", new Locale("es")).format(now));
                View dd = root.findViewWithTag("daynum");
                if (dd != null) ((TextView) dd).setText(new SimpleDateFormat("d", Locale.US).format(now));
                View dm = root.findViewWithTag("month");
                if (dm != null) ((TextView) dm).setText(new SimpleDateFormat("MMMM yyyy", new Locale("es")).format(now));
                if (gnssMini != null) gnssMini.setText(gpsMini());
            }
            h.postDelayed(this, 1000);
        }
    };

    /** Hora en 12 h con AM/PM pequeño (por defecto) o en 24 h; misma preferencia que la interfaz web. */
    private CharSequence clockText(Date d, boolean seconds) {
        if ("24".equals(prefs.getString("web.clock", "12")))
            return new SimpleDateFormat(seconds ? "HH:mm:ss" : "HH:mm", Locale.US).format(d);
        String t = new SimpleDateFormat(seconds ? "h:mm:ss" : "h:mm", Locale.US).format(d);
        String ap = new SimpleDateFormat("a", Locale.US).format(d);
        android.text.SpannableString s = new android.text.SpannableString(t + " " + ap);
        s.setSpan(new android.text.style.RelativeSizeSpan(0.45f), t.length() + 1, s.length(), 0);
        return s;
    }

    private boolean fresh() { return lastFix > 0 && SystemClock.elapsedRealtime() - lastFix < 5000; }

    private boolean btOn() {
        try { BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter(); return bt != null && bt.isEnabled(); }
        catch (Exception e) { return false; }
    }

    private String chipLine() {
        return "wifi:" + (wifiConnected() ? "up" : "down") + "  bt:" + (btOn() ? "on" : "off")
                + "  gnss:" + (fresh() ? "fix" : "nofix")
                + (batteryV >= 0 ? String.format(Locale.US, "  batt:%.1fV", batteryV) : "");
    }

    private boolean wifiConnected() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            return wi != null && wi.getIpAddress() != 0;
        } catch (Exception e) { return false; }
    }

    private String gpsMini() {
        if (last == null) return "Satélites  " + (satsUsed < 0 ? "—" : satsUsed + "/" + satsSeen) + "\nEsperando señal…";
        return "Satélites  " + (satsUsed < 0 ? "—" : satsUsed + "/" + satsSeen)
                + "\nRumbo      " + (last.hasBearing() ? heading(last) + " " + Math.round(last.getBearing()) + "°" : "—")
                + String.format(Locale.US, "\nAltitud    %.0f m", last.getAltitude());
    }

    private String telemetry() {
        StringBuilder s = new StringBuilder();
        s.append("# gnss\n");
        s.append(kv("sats", satsUsed < 0 ? "n/a" : satsUsed + "/" + satsSeen));
        if (last != null) {
            s.append(kv("lat", String.format(Locale.US, "%.5f", last.getLatitude())));
            s.append(kv("lon", String.format(Locale.US, "%.5f", last.getLongitude())));
            s.append(kv("alt", String.format(Locale.US, "%.0f m", last.getAltitude())));
            s.append(kv("acc", String.format(Locale.US, "±%.0f m", last.getAccuracy())));
            s.append(kv("hdg", last.hasBearing() ? heading(last) + " " + Math.round(last.getBearing()) + "°" : "--"));
        } else s.append(kv("fix", "waiting..."));
        s.append("\n# system\n");
        s.append(kv("cpu.temp", cpuTemp()));
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            s.append(kv("mem.free", String.format(Locale.US, "%.1f GB", mi.availMem / 1073741824f)));
        } catch (Exception e) { }
        try {
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            s.append(kv("disk.free", String.format(Locale.US, "%.1f GB", st.getAvailableBytes() / 1073741824f)));
        } catch (Exception e) { }
        s.append(kv("uptime", dur(SystemClock.elapsedRealtime())));
        s.append(kv("session", dur(SystemClock.elapsedRealtime() - sessionTs)));
        s.append("\n# net\n");
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            int ip = wi != null ? wi.getIpAddress() : 0;
            if (ip != 0) {
                String ssid = wi.getSSID() == null ? "?" : wi.getSSID().replace("\"", "");
                s.append(kv("ssid", ssid.length() > 12 ? ssid.substring(0, 12) + "…" : ssid));
                s.append(kv("ip", String.format(Locale.US, "%d.%d.%d.%d", ip & 255, ip >> 8 & 255, ip >> 16 & 255, ip >> 24 & 255)));
                s.append(kv("rssi", wi.getRssi() + " dBm"));
            } else s.append(kv("wlan0", "disconnected"));
        } catch (Exception e) { s.append(kv("wlan0", "n/a")); }
        return s.toString();
    }

    private String kv(String k, String v) {
        StringBuilder b = new StringBuilder(k);
        while (b.length() < 10) b.append(' ');
        return b.append(v).append('\n').toString();
    }

    private String dur(long ms) {
        long m = ms / 60000;
        return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
    }

    private String cpuTemp() {
        float best = -1;
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones != null) for (File z : zones) {
            if (!z.getName().startsWith("thermal_zone")) continue;
            try {
                BufferedReader r = new BufferedReader(new FileReader(new File(z, "temp")));
                float v = Float.parseFloat(r.readLine().trim());
                r.close();
                if (v > 1000) v /= 1000f;
                if (v > 0 && v < 130 && v > best) best = v;
            } catch (Exception e) { }
        }
        return best < 0 ? "n/a" : String.format(Locale.US, "%.0f °C", best);
    }

    // ======================= GPS =======================
    private void startGps() {
        if (gpsOn) return;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, new LocationListener() {
                public void onLocationChanged(Location l) { onFix(l); }
                public void onStatusChanged(String p, int s, Bundle e) { }
                public void onProviderEnabled(String p) { }
                public void onProviderDisabled(String p) { }
            });
            if (Build.VERSION.SDK_INT >= 24) {
                lm.registerGnssStatusCallback(new GnssStatus.Callback() {
                    public void onSatelliteStatusChanged(GnssStatus st) {
                        int used = 0;
                        for (int i = 0; i < st.getSatelliteCount(); i++) if (st.usedInFix(i)) used++;
                        satsUsed = used;
                        satsSeen = st.getSatelliteCount();
                    }
                });
            }
            gpsOn = true;
        } catch (Exception e) {
            if (gauge != null) gauge.setSub("gps: permission denied");
        }
    }

    private void onFix(Location l) {
        long now = SystemClock.elapsedRealtime();
        float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0;
        if (kmh < 2) kmh = 0;
        if (last != null && kmh > 0) { distM += last.distanceTo(l); movingMs += now - lastFix; }
        last = l;
        lastFix = now;
        curKmh = kmh;
        if (kmh > maxKmh) maxKmh = kmh;
        trackExtras(l, kmh, now);
        feedGauge(kmh);
        feedGaugeSub(P.glass ? String.format(Locale.US, "%s · ±%.0f m", heading(l), l.getAccuracy())
                : String.format(Locale.US, "hdg %s · ±%.0fm", heading(l), l.getAccuracy()));
        if (cockCompass != null && l.hasBearing()) cockCompass.setBearing(l.getBearing());
        updateTrip();
    }

    private String heading(Location l) {
        if (!l.hasBearing()) return "--";
        String[] d = {"N", "NE", "E", "SE", "S", "SO", "O", "NO"};
        return d[Math.round(l.getBearing() / 45f) % 8];
    }

    private void resetTrip() { distM = 0; movingMs = 0; maxKmh = 0; updateTrip(); }

    private void updateTrip() {
        if (tDist == null) return;
        tDist.setText(String.format(Locale.US, "%.1f km", distM / 1000f));
        tTime.setText(dur(movingMs));
        float hrs = movingMs / 3600000f;
        tAvg.setText(hrs > 0.003f ? String.format(Locale.US, "%.0f", (distM / 1000f) / hrs) : "--");
        tMax.setText(String.format(Locale.US, "%.0f", maxKmh));
        feedCockpitTrip();
    }

    // ======================= Cockpit: alimentar velocímetro y tarjetas =======================
    private void feedGauge(float kmh) {
        if (gauge != null) gauge.setSpeed(kmh);
        if (cockGauge != null) cockGauge.setSpeed(kmh);
        if (cockRoad != null) cockRoad.setSpeed(kmh);
    }

    private void feedGaugeSub(String s) {
        if (gauge != null) gauge.setSub(s);
        if (cockGauge != null) cockGauge.setSub(s);
    }

    private void feedCockpitTrip() {
        if (tripBig == null) return;
        roll(tripBig, distM / 1000f, "%.1f km");
        float hrs = movingMs / 3600000f;
        String avg = hrs > 0.003f ? String.format(Locale.US, "%.0f", (distM / 1000f) / hrs) : "--";
        tripSub.setText(dur(movingMs) + " · prom. " + avg + " · máx. " + Math.round(maxKmh) + " km/h");
    }

    /** Actualiza batería y GPS del Cockpit (se llama desde el tick). */
    private void feedCockpitLive() {
        if (cockTopBatt != null) cockTopBatt.setText(batteryV >= 0 ? String.format(Locale.US, "%.1f V", batteryV) : "");
        if (cockTopMid != null) cockTopMid.setText(last != null ? "Rumbo " + heading(last) + String.format(Locale.US, " · %.0f m s. n. m.", last.getAltitude()) : (fresh() ? "" : "Buscando GPS…"));
        feedExtras();
        if (cockBattV != null) {
        if (batteryV >= 0) roll(cockBattV, batteryV, "%.1f V"); else cockBattV.setText("—");
        cockBattState.setText(batteryV < 0 ? "Sin dato" : batteryV >= 13.3f ? "Cargando" : batteryV >= 12.4f ? "Bien" : batteryV >= 12.0f ? "Baja" : "Muy baja");
        if (cockBattBar != null) cockBattBar.setFraction(batteryV < 0 ? 0 : (batteryV - 11.6f) / (14.4f - 11.6f));
        }
        if (cockSats == null) return;
        cockSats.setText(satsUsed < 0 ? "—" : String.valueOf(satsUsed));
        if (last != null) {
            cockGpsSub.setText("sats " + (satsUsed < 0 ? "—" : satsUsed + "/" + satsSeen)
                    + "\n" + String.format(Locale.US, "%.0f m · %s", last.getAltitude(), heading(last)));
            if (cockCompass != null && last.hasBearing()) cockCompass.setBearing(last.getBearing());
        } else cockGpsSub.setText("Buscando señal…");
    }

    private void feedExtras() {
        if (cmpDir != null) {
            cmpDir.setText(last != null && last.hasBearing() ? heading(last) + String.format(Locale.US, " · %.0f°", last.getBearing()) : "—");
            cmpStreet.setText(street.length() > 0 ? street : (last == null ? "Buscando GPS…" : android.location.Geocoder.isPresent() ? "Sin nombre de calle (requiere internet)" : "Esta radio no da nombres de calles"));
            if (last != null && last.hasBearing()) cmpBig.setBearing(last.getBearing());
        }
        if (altTxt != null) {
            if (last != null && last.hasAltitude()) roll(altTxt, (float) last.getAltitude(), "%.0f m"); else altTxt.setText("—");
            altSub.setText(Math.abs(grade) < 0.5f ? "Plano" : String.format(Locale.US, "%s %.0f %% de pendiente", grade > 0 ? "▲ Subida" : "▼ Bajada", Math.abs(grade)));
            altLine.invalidate();
        }
        if (accTxt != null) {
            if (accelBest == 0) accelBest = prefs.getFloat("accel.best", 0);
            float live = accelT0 > 0 ? (SystemClock.elapsedRealtime() - accelT0) / 1000f : 0;
            accTxt.setText(live > 0 ? String.format(Locale.US, "%.1f s…", live) : lastRun > 0 ? String.format(Locale.US, "%.1f s", lastRun) : "Listo");
            accSub.setText("Récord " + (accelBest > 0 ? String.format(Locale.US, "%.1f s", accelBest) : "—")
                    + String.format(Locale.US, "\nFuerza %.2f G · pico %.2f G", gNow, gPeak) + "\nArranca desde 0 · mantén presionado para borrar");
        }
        feedControls();
        if (!bigClocks.isEmpty() && clock != null) {
            bigClocks.get(0).setText(clock.getText());
            if (date != null) bigClocks.get(1).setText(date.getText());
        }
    }

    /** "Números que ruedan": el valor llega contando en vez de saltar. */
    private void roll(final TextView t, final float to, final String fmt) {
        Object o = t.getTag();
        float from = o instanceof Float ? (Float) o : to;
        t.setTag(to);
        if (!Views.anim || !prefs.getBoolean("anim.roll", true) || Math.abs(to - from) < 0.0001f) { t.setText(String.format(Locale.US, fmt, to)); return; }
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(from, to);
        va.setDuration(600);
        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator a) { t.setText(String.format(Locale.US, fmt, (Float) a.getAnimatedValue())); }
        });
        va.start();
    }

    /** "Entrada de tarjetas": suben y aparecen una tras otra al armar el tema. */
    private void enterCards(View root) {
        if (!Views.anim || !prefs.getBoolean("anim.cards", true)) return;
        List<View> cards = new ArrayList<View>();
        collectCards(root, cards);
        for (int i = 0; i < cards.size(); i++) {
            View v = cards.get(i);
            v.setAlpha(0f); v.setTranslationY(dp(26));
            v.animate().alpha(1f).translationY(0).setStartDelay(80 + i * 70).setDuration(380)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        }
    }

    private void collectCards(View v, List<View> out) {
        if ("card".equals(v.getTag(R_CARD))) { out.add(v); return; }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectCards(g.getChildAt(i), out);
        }
    }
    private static final int R_CARD = 0x7f0f0f01;

    // ======================= puente para la interfaz web (WebHome) =======================
    private boolean useWeb() { return "web".equals(prefs.getString("engine", "native")); }

    static String keyOf(ResolveInfo r) { return key(r); }
    List<ResolveInfo> webLaunchables() { return launchables(); }
    boolean webIsHidden(String k) { return hidden.contains(k); }
    boolean webIsPinned(String k) { return pinned.contains(k) || dockKeys.contains(k); }
    SharedPreferences webPrefs() { return prefs; }
    void webStart(ResolveInfo r) { start(r); }
    void webAppMenu(ResolveInfo r) { appMenu(r); }
    void webMute() { mute(); }
    void webResetTrip() { resetTrip(); }
    void webCheckUpdate() { new Updater(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).check(); }
    boolean webHasStorage() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
    }
    void webAskStorage() {
        if (Build.VERSION.SDK_INT >= 23)
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"}, 2);
    }
    void webUseNative() { prefs.edit().putString("engine", "native").apply(); applyTheme(); }

    void webSetVolume(int v) {
        v = Math.max(0, Math.min(volMax(), v));
        if (isNwd()) nwdSetVolume(v);
        else {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0);
        }
    }

    /** Brillo en %, nunca por debajo del mínimo (por debajo la pantalla de la radio se apaga). */
    void webSetBrightness(int pct) {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.System.canWrite(this)) return;
        int v = Math.max(MIN_BRIGHTNESS, Math.min(255, Math.round(pct * 255f / 100f)));
        try {
            android.content.ContentResolver cr = getContentResolver();
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, v);
        } catch (Exception e) { }
    }

    private String cpuTempCache = "n/a";
    private long cpuTempAt = -60000;

    org.json.JSONObject webState() throws org.json.JSONException {
        org.json.JSONObject o = new org.json.JSONObject();
        boolean fr = fresh();
        o.put("kmh", fr ? curKmh : 0);
        o.put("maxKmh", maxKmh);
        o.put("distKm", distM / 1000f);
        o.put("movingMin", movingMs / 60000f);
        float hrs = movingMs / 3600000f;
        o.put("avgKmh", hrs > 0.003f ? (distM / 1000f) / hrs : 0);
        o.put("fix", fr);
        o.put("satsUsed", satsUsed);
        o.put("satsSeen", satsSeen);
        if (last != null) {
            o.put("lat", last.getLatitude());
            o.put("lon", last.getLongitude());
            o.put("alt", last.getAltitude());
            o.put("acc", last.getAccuracy());
            o.put("bearing", last.hasBearing() ? last.getBearing() : -1);
            o.put("heading", heading(last));
        }
        o.put("batteryV", batteryV);
        o.put("vol", volNow());
        o.put("volMax", volMax());
        int b = 128;
        try { b = android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS); } catch (Exception e) { }
        o.put("bright", Math.round(b * 100f / 255f));
        o.put("brightMin", Math.round(MIN_BRIGHTNESS * 100f / 255f));
        o.put("canBright", Build.VERSION.SDK_INT < 23 || android.provider.Settings.System.canWrite(this));
        o.put("wifi", wifiConnected());
        o.put("bt", btOn());
        // leer los sensores térmicos es lento en este Unisoc: como mucho cada 15 s
        if (SystemClock.elapsedRealtime() - cpuTempAt > 15000) { cpuTempCache = cpuTemp(); cpuTempAt = SystemClock.elapsedRealtime(); }
        o.put("cpuTemp", cpuTempCache);
        o.put("sessionMin", (SystemClock.elapsedRealtime() - sessionTs) / 60000f);
        return o;
    }

    // ======================= helpers =======================
    private GradientDrawable box(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        g.setStroke(1, stroke);
        return g;
    }

    private TextView txt(String t, float sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(P.font);
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
