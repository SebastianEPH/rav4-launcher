package pe.radio.rav4;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Ajustes Launcher" — ventana nativa (sin WebView) para configurar el launcher: interfaz, tema, apariencia,
 * brillo automático, permisos y sistema. Al volver, el launcher compara "stamp" y solo se rearma si algo cambió.
 */
public class SettingsActivity extends Activity {

    private static final int ACC = 0xFF0A84FF, OK = 0xFF30D158, WARN = 0xFFFF9F0A;
    // Paleta según el modo global (Oscuro / Claro / Automático), igual que el launcher
    private int BG, CARD, LINE, TXT, DIM, SOFT;
    private boolean darkUi;

    private void palette() {
        darkUi = Theme.fromPrefs(prefs).dark;
        BG = darkUi ? 0xFF0B0C10 : 0xFFF2F2F7;
        CARD = darkUi ? 0xFF17181E : 0xFFFFFFFF;
        LINE = darkUi ? 0x14FFFFFF : 0x14000000;
        TXT = darkUi ? 0xFFF2F4F8 : 0xFF111216;
        DIM = darkUi ? 0x8CF2F4F8 : 0x8C111216;
        SOFT = darkUi ? 0x14FFFFFF : 0x10000000;
    }

    private SharedPreferences prefs;
    private LinearLayout content;
    private final Handler h = new Handler();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("rav4", MODE_PRIVATE);
        palette();

        LinearLayout rootc = new LinearLayout(this);
        rootc.setOrientation(LinearLayout.VERTICAL);
        rootc.setBackgroundColor(BG);

        // encabezado
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(16), dp(20), dp(12));
        TextView title = tv("Ajustes Launcher", 24, TXT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = tv("Listo", 17, 0xFFFFFFFF);
        done.setPadding(dp(22), dp(10), dp(22), dp(10));
        GradientDrawable db = new GradientDrawable(); db.setColor(ACC); db.setCornerRadius(dp(22));
        done.setBackground(db);
        done.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        head.addView(done);
        rootc.addView(head, new LinearLayout.LayoutParams(-1, -2));
        tabBar = new LinearLayout(this);
        tabBar.setPadding(dp(16), 0, dp(16), dp(10));
        rootc.addView(tabBar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(28));
        sv.addView(content);
        rootc.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(rootc);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        rebuild();
        continueWizard();
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) { continueWizard(); }

    private void touch() { prefs.edit().putLong("stamp", System.currentTimeMillis()).apply(); }

    private boolean web() { return "web".equals(prefs.getString("engine", "native")); }

    // ======================= construcción de la pantalla =======================
    // Pestañas (se cambian tocándolas o deslizando a los lados)
    private static final String[] TABS = {"General", "Tema actual", "Brillo", "Apps", "Permisos", "Sistema"};
    private static int tab = 0;
    private LinearLayout tabBar;

    private void renderTabs() {
        tabBar.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int t = i;
            boolean on = i == tab;
            TextView b = tv(TABS[i], 16, on ? 0xFFFFFFFF : DIM);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(18), dp(10), dp(18), dp(10));
            if (on) { GradientDrawable g = new GradientDrawable(); g.setColor(ACC); g.setCornerRadius(dp(20)); b.setBackground(g); }
            b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { tab = t; rebuild(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            tabBar.addView(b, lp);
        }
    }

    private void rebuild() {
        if (Theme.fromPrefs(prefs).dark != darkUi) { recreate(); return; }
        renderTabs();
        content.removeAllViews();
        if (tab == 0) tabGeneral();
        else if (tab == 1) tabTema();
        else if (tab == 2) tabBrillo();
        else if (tab == 3) tabApps();
        else if (tab == 4) { section("Permisos"); content.addView(permsCard()); }
        else tabSistema();
    }

    private void tabGeneral() {
        section("Interfaz");
        LinearLayout ic = card();
        ic.addView(segRow("Tipo de interfaz", "La nativa es la más liviana", "engine",
                new String[]{"Nativa", "Web (animada)"}, new String[]{"native", "web"}, "native"));
        content.addView(ic);

        section("Tema");
        content.addView(themeGrid());

        section("Apariencia");
        LinearLayout ap = card();
        ap.addView(segRow("Modo", null, "mode",
                new String[]{"Oscuro", "Claro", "Automático"}, new String[]{"dark", "light", "auto"}, "dark"));
        if ("auto".equals(prefs.getString("mode", "dark"))) ap.addView(hoursRow());
        ap.addView(segRow("Reloj", null, "web.clock", new String[]{"12 h (AM/PM)", "24 h"}, new String[]{"12", "24"}, "12"));
        ap.addView(segRow("Fondo (Cockpit / Inicio)", null, "wall",
                new String[]{"Ondas", "Noche", "Atardecer", "Mi RAV4"}, new String[]{"ondas", "noche", "atardecer", "rav4"}, "ondas"));
        ap.addView(segRow("Color de acento", null, "accent",
                new String[]{"Tema", "Azul", "Verde", "Naranja", "Rosa"}, new String[]{"theme", "blue", "green", "orange", "pink"}, "theme"));
        if (web()) ap.addView(segRow("Efectos (web)", "\"Altos\" agrega desenfoque; más lento", "web.fx",
                new String[]{"Altos", "Suaves", "No"}, new String[]{"high", "low", "off"}, "low"));
        content.addView(ap);

    }

    // ---- pestaña "Tema actual": opciones propias del tema elegido ----
    static final String[] CARD_LABELS = {"Viaje", "Batería", "GPS", "Música", "Rumbo + calle", "Altitud y pendiente",
            "0–100 y fuerza G", "Accesos (CarPlay, mapas)", "Controles (volumen, brillo)", "Reloj grande"};
    static final String[] CARD_VALUES = {"trip", "batt", "gps", "music", "compass", "alt", "accel", "shortcuts", "controls", "clock"};

    private void tabTema() {
        String th = prefs.getString("theme", "cockpit");
        boolean cock = "cockpit".equals(th) || "auto".equals(th);
        String name = "terminal".equals(th) ? "Terminal" : "glass".equals(th) ? "Glass" : "dusk".equals(th) ? "Dusk"
                : "home".equals(th) ? "Inicio" : "Cockpit";
        section("Tema " + name);
        if (cock) {
            LinearLayout g = card();
            g.addView(segRow("Tamaño del velocímetro", null, "cockpit.size",
                    new String[]{"Pequeño", "Mediano", "Grande"}, new String[]{"s", "m", "l"}, "m"));
            g.addView(segRow("Velocidad máxima del velocímetro", null, "web.t.cockpit.scale",
                    new String[]{"160", "180", "200", "220"}, new String[]{"160", "180", "200", "220"}, "180"));
            g.addView(segRow("Fondo", null, "wall",
                    new String[]{"Ondas", "Noche", "Atardecer", "Mi RAV4"}, new String[]{"ondas", "noche", "atardecer", "rav4"}, "ondas"));
            content.addView(g);
            section("Tarjetas (qué se muestra en cada espacio)");
            LinearLayout t = card();
            t.addView(slotPicker("Arriba (ancha)", "cockpit.slot1", "trip"));
            t.addView(slotPicker("Medio · izquierda", "cockpit.slot2", "batt"));
            t.addView(slotPicker("Medio · derecha", "cockpit.slot3", "gps"));
            t.addView(slotPicker("Abajo (ancha)", "cockpit.slot4", "music"));
            content.addView(t);
        } else {
            if ("home".equals(th)) {
                LinearLayout g = card();
                g.addView(segRow("Fondo", null, "wall",
                        new String[]{"Ondas", "Noche", "Atardecer", "Mi RAV4"}, new String[]{"ondas", "noche", "atardecer", "rav4"}, "ondas"));
                content.addView(g);
            }
            section("Tarjetas (qué se muestra en cada espacio)");
            LinearLayout t = card();
            if ("terminal".equals(th)) {
                t.addView(slotPicker("Centro (vehículo y viaje)", "terminal.slot1", "default", "Original: vehículo"));
                t.addView(slotPicker("Derecha (telemetría)", "terminal.slot2", "default", "Original: telemetría"));
            } else if ("glass".equals(th)) {
                t.addView(slotPicker("Arriba a la derecha (Mi RAV4)", "glass.slot1", "default", "Original: Mi RAV4"));
                t.addView(slotPicker("Abajo · izquierda (fecha y hora)", "glass.slot2", "default", "Original: fecha"));
                t.addView(slotPicker("Abajo · derecha (GPS)", "glass.slot3", "default", "Original: GPS"));
            } else if ("dusk".equals(th)) {
                t.addView(slotPicker("Segunda tarjeta (Mi RAV4)", "dusk.slot1", "default", "Original: Mi RAV4"));
                t.addView(slotPicker("Tercera tarjeta (controles)", "dusk.slot2", "default", "Original: controles"));
                t.addView(slotPicker("Cuarta tarjeta (GPS)", "dusk.slot3", "default", "Original: GPS"));
            } else {
                t.addView(slotPicker("Columna derecha · arriba", "home.slot1", "default", "Ninguna"));
                t.addView(slotPicker("Columna derecha · abajo", "home.slot2", "default", "Ninguna"));
            }
            content.addView(t);
        }
        section("Animaciones");
        LinearLayout a = card();
        a.addView(segRow("Animaciones", "Carretera, estela de la aguja, barrido al encender", "native.anim",
                new String[]{"Sí", "No"}, new String[]{"on", "off"}, "on"));
        a.addView(boolRow("Entrada de tarjetas", "Aparecen deslizándose al volver a Inicio", "anim.cards"));
        a.addView(boolRow("Números que ruedan", "Velocidad, voltaje y km cuentan en vez de saltar", "anim.roll"));
        content.addView(a);
    }

    private LinearLayout boolRow(String label, String desc, final String key) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(tv(label, 16, TXT));
        txt.addView(tv(desc, 12, DIM));
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        final boolean on = prefs.getBoolean(key, true);
        LinearLayout seg = new LinearLayout(this);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(SOFT); bg.setCornerRadius(dp(12));
        seg.setBackground(bg); seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        for (int i = 0; i < 2; i++) {
            final boolean v = i == 0;
            TextView b = tv(v ? "Sí" : "No", 14, v == on ? 0xFFFFFFFF : TXT);
            b.setPadding(dp(14), dp(9), dp(14), dp(9));
            if (v == on) { GradientDrawable sg = new GradientDrawable(); sg.setColor(ACC); sg.setCornerRadius(dp(10)); b.setBackground(sg); }
            b.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { prefs.edit().putBoolean(key, v).apply(); touch(); rebuild(); } });
            seg.addView(b);
        }
        row.addView(seg);
        return row;
    }

    /** Selector de tarjeta: título y una cuadrícula de opciones (5 por fila). */
    private LinearLayout slotPicker(String label, final String key, String def) { return slotPicker(label, key, def, null); }

    private LinearLayout slotPicker(String label, final String key, String def, String original) {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(dp(14), dp(12), dp(14), dp(12));
        w.addView(tv(label, 16, TXT));
        android.widget.GridLayout g = new android.widget.GridLayout(this);
        String cur = prefs.getString(key, def);
        int off = original != null ? 1 : 0;
        final int cols = off > 0 ? 6 : 5;
        g.setColumnCount(cols);
        for (int i = 0; i < CARD_VALUES.length + off; i++) {
            final String val = i < off ? "default" : CARD_VALUES[i - off];
            String lab = i < off ? original : CARD_LABELS[i - off];
            boolean on = val.equals(cur);
            TextView b = tv(lab, 14, on ? 0xFFFFFFFF : TXT);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setPadding(dp(8), dp(12), dp(8), dp(12));
            GradientDrawable sg = new GradientDrawable(); sg.setColor(on ? ACC : SOFT); sg.setCornerRadius(dp(10));
            b.setBackground(sg);
            b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { prefs.edit().putString(key, val).apply(); touch(); rebuild(); } });
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(i / cols), android.widget.GridLayout.spec(i % cols, 1f));
            lp.width = 0;
            lp.setMargins(dp(3), dp(6), dp(3), 0);
            g.addView(b, lp);
        }
        w.addView(g, new LinearLayout.LayoutParams(-1, -2));
        return w;
    }

    private void tabBrillo() {
        section("Brillo ahora");
        LinearLayout now = card();
        now.addView(brightnessNowRow());
        content.addView(now);

        section("Brillo automático por horario");
        LinearLayout bc = card();
        bc.addView(segRow("Ajustar el brillo según la hora", "El launcher sube o baja el brillo solo", "autobright",
                new String[]{"Sí", "No"}, new String[]{"on", "off"}, "off"));
        if ("on".equals(prefs.getString("autobright", "off"))) {
            bc.addView(segRow("Forma del brillo", "Arco: sube y baja de a poco entre los puntos", "autobright.shape",
                    new String[]{"Arco suave", "Escalones"}, new String[]{"curve", "steps"}, "curve"));
            bc.addView(segRow("Transición entre tramos", "Suave: cambia de a poco en ~30 s", "autobright.fade",
                    new String[]{"Suave", "Inmediata"}, new String[]{"on", "off"}, "on"));
            bc.addView(presetsRow());
        }
        content.addView(bc);
        if ("on".equals(prefs.getString("autobright", "off"))) content.addView(bandsEditor());
    }

    private void tabApps() {
        section("Cajón de apps");
        LinearLayout dc = card();
        dc.addView(segRow("Tamaño de los íconos", null, "drawer.size",
                new String[]{"Pequeño", "Mediano", "Grande"}, new String[]{"s", "m", "l"}, "m"));
        dc.addView(sliderRow("Opacidad del fondo", "drawer.alpha", 1, 100, 100, "%"));
        dc.addView(segRow("Difuminar el fondo", "Se calcula una vez al abrir (no da lag)", "drawer.blur",
                new String[]{"Sí", "No"}, new String[]{"on", "off"}, "off"));
        dc.addView(segRow("Orden de las apps", "Personalizado: usa ▲▼ en la lista de abajo", "drawer.sort",
                new String[]{"Por nombre", "Personalizado"}, new String[]{"name", "custom"}, "name"));
        content.addView(dc);

        section("Apps: mostrar, ocultar y cambiar ícono");
        content.addView(appsCard());
    }

    private void tabSistema() {
        section("Sistema");
        LinearLayout sc = card();
        sc.addView(linkRow("Buscar actualización", new View.OnClickListener() {
            public void onClick(View v) { new Updater(SettingsActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert).check(); }
        }));
        sc.addView(linkRow("Elegir launcher predeterminado", new View.OnClickListener() {
            public void onClick(View v) { open("android.settings.HOME_SETTINGS"); }
        }));
        sc.addView(linkRow("Ajustes de Android", new View.OnClickListener() {
            public void onClick(View v) { open("android.settings.SETTINGS"); }
        }));
        content.addView(sc);
        TextView ver = tv("RAV4 Launcher " + Updater.currentVersion(this) + " · Android " + Build.VERSION.RELEASE, 12, DIM);
        ver.setPadding(dp(4), dp(10), 0, 0);
        content.addView(ver);
    }

    // ---------- brillo: ahora, perfiles ----------
    private boolean canWrite() { return Build.VERSION.SDK_INT < 23 || android.provider.Settings.System.canWrite(this); }

    private void setScreenBrightness(int pct) {
        if (!canWrite()) { permRequest("settings"); return; }
        int v = Math.max(46, Math.min(255, Math.round(Math.max(18, pct) * 2.55f)));
        try {
            android.provider.Settings.System.putInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            android.provider.Settings.System.putInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS, v);
        } catch (Exception e) { }
    }

    private LinearLayout brightnessNowRow() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(12));
        int cur = 50;
        try { cur = Math.round(android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) / 2.55f); } catch (Exception e) { }
        final TextView lab = tv("Brillo de la pantalla: " + cur + " %", 16, TXT);
        col.addView(lab);
        SeekBar sb = new SeekBar(this);
        sb.setMax(100); sb.setProgress(cur);
        tint(sb);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean u) { if (u) { int v = Math.max(18, pr); lab.setText("Brillo de la pantalla: " + v + " %"); setScreenBrightness(v); } }
            public void onStartTrackingTouch(SeekBar s) { touchingWidget = true; }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        col.addView(sb, new LinearLayout.LayoutParams(-1, dp(44)));
        TextView n = tv("Nunca baja de 18 % (por debajo la pantalla de la radio se apaga).", 12, DIM);
        col.addView(n);
        if ("on".equals(prefs.getString("autobright", "off"))) {
            TextView apply = tv("Aplicar ahora el brillo del horario", 15, 0xFFFFFFFF);
            apply.setGravity(Gravity.CENTER);
            apply.setPadding(0, dp(11), 0, dp(11));
            GradientDrawable g = new GradientDrawable(); g.setColor(ACC); g.setCornerRadius(dp(14));
            apply.setBackground(g);
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
            al.topMargin = dp(10);
            apply.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    int hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                    java.util.Calendar cl = java.util.Calendar.getInstance();
                    int pct = curveAt(bandList(), hr + cl.get(java.util.Calendar.MINUTE) / 60f);
                    setScreenBrightness(pct);
                    android.widget.Toast.makeText(SettingsActivity.this, "Brillo del horario aplicado: " + pct + " %", android.widget.Toast.LENGTH_SHORT).show();
                    rebuild();
                }
            });
            col.addView(apply, al);
        }
        return col;
    }

    private LinearLayout presetsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(4), dp(14), dp(12));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(tv("Perfiles rápidos", 16, TXT));
        tx.addView(tv("Reemplazan los tramos; luego puedes ajustarlos", 12, DIM));
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        String[][] pr = {{"Día brillante", "6:100,18:70,21:45"}, {"Equilibrado", "7:90,19:55,22:25"}, {"Nocturno", "7:70,18:40,21:20"}};
        for (final String[] x : pr) {
            TextView b = smallBtn(x[0]);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { prefs.edit().putString("autobright.bands", x[1]).apply(); touch(); rebuild(); }
            });
            row.addView(b);
        }
        return row;
    }

    /** Fila con deslizador numérico (ej. opacidad 1–100 %). */
    private LinearLayout sliderRow(String label, final String key, final int min, int max, int def, final String unit) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(8));
        int cur = def;
        try { cur = Integer.parseInt(prefs.getString(key, String.valueOf(def))); } catch (Exception e) { }
        final TextView lab = tv(label + ": " + cur + " " + unit, 16, TXT);
        col.addView(lab);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max); sb.setProgress(cur);
        tint(sb);
        final String flabel = label;
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean u) { lab.setText(flabel + ": " + Math.max(min, pr) + " " + unit); }
            public void onStartTrackingTouch(SeekBar s) { touchingWidget = true; }
            public void onStopTrackingTouch(SeekBar s) { prefs.edit().putString(key, String.valueOf(Math.max(min, s.getProgress()))).apply(); touch(); }
        });
        col.addView(sb, new LinearLayout.LayoutParams(-1, dp(44)));
        return col;
    }

    private void tint(SeekBar sb) {
        sb.getProgressDrawable().setColorFilter(ACC, android.graphics.PorterDuff.Mode.SRC_IN);
        sb.getThumb().setColorFilter(ACC, android.graphics.PorterDuff.Mode.SRC_IN);
    }

    // ---------- deslizar a los lados para cambiar de pestaña ----------
    private boolean touchingWidget = false;
    private float swipeX, swipeY;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { swipeX = e.getX(); swipeY = e.getY(); touchingWidget = false; }
        boolean r = super.dispatchTouchEvent(e);
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && !touchingWidget) {
            float dx = e.getX() - swipeX, dy = e.getY() - swipeY;
            if (Math.abs(dx) > dp(140) && Math.abs(dx) > 2.2f * Math.abs(dy)) {
                int nt = tab + (dx < 0 ? 1 : -1);
                if (nt >= 0 && nt < TABS.length) { tab = nt; rebuild(); }
            }
        }
        return r;
    }

    private void open(String action) { try { startActivity(new Intent(action)); } catch (Exception e) { } }

    // ---------- piezas de UI ----------
    private void section(String s) {
        TextView t = tv(s.toUpperCase(Locale.ROOT), 12, DIM);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(4), dp(18), 0, dp(8));
        content.addView(t);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(16));
        c.setBackground(g);
        return c;
    }

    /** Fila: título (+ descripción) a la izquierda y un control segmentado a la derecha. */
    private LinearLayout segRow(String label, String desc, final String key, String[] labels, final String[] values, String def) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView l = tv(label, 16, TXT);
        txt.addView(l);
        if (desc != null) txt.addView(tv(desc, 12, DIM));
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(seg(key, labels, values, def));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }

    private LinearLayout seg(final String key, String[] labels, final String[] values, String def) {
        LinearLayout seg = new LinearLayout(this);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(SOFT); bg.setCornerRadius(dp(12));
        seg.setBackground(bg);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        String cur = prefs.getString(key, def);
        for (int i = 0; i < labels.length; i++) {
            final String val = values[i];
            boolean on = val.equals(cur);
            TextView b = tv(labels[i], 14, on ? 0xFFFFFFFF : TXT);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(14), dp(9), dp(14), dp(9));
            if (on) { GradientDrawable s = new GradientDrawable(); s.setColor(ACC); s.setCornerRadius(dp(10)); b.setBackground(s); }
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { prefs.edit().putString(key, val).apply(); touch(); rebuild(); }
            });
            seg.addView(b);
        }
        return seg;
    }

    private LinearLayout linkRow(String label, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(15), dp(14), dp(15));
        row.addView(tv(label, 16, ACC), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(tv("›", 20, DIM));
        row.setOnClickListener(l);
        return row;
    }

    private LinearLayout themeGrid() {
        boolean w = web();
        String key = "theme";   // global: nativa y web
        String cur = prefs.getString(key, w ? "cockpit" : "terminal");
        String[][] themes = w
                ? new String[][]{{"terminal", "Terminal"}, {"glass", "Glass"}, {"dusk", "Dusk"}, {"cockpit", "Cockpit"}, {"home", "Inicio"}, {"auto", "Auto · CAN"}}
                : new String[][]{{"terminal", "Terminal"}, {"glass", "Glass"}, {"dusk", "Dusk"}, {"cockpit", "Cockpit"}, {"home", "Inicio"}};
        int[][] cols = {{0xFF0B0E13, 0xFF58A6FF}, {0xFF0B1426, 0xFF2B1747}, {0xFFE58FA6, 0xFF231A3A},
                {0xFF030406, 0xFF00E5FF}, {0xFF120C2A, 0xFFFF375F}, {0xFF0B1A3A, 0xFFF97316}};
        LinearLayout gridWrap = new LinearLayout(this);
        gridWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rowl = null;
        for (int i = 0; i < themes.length; i++) {
            if (i % 3 == 0) {
                rowl = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rp.topMargin = dp(10);
                gridWrap.addView(rowl, rp);
            }
            final String val = themes[i][0];
            boolean on = val.equals(cur);
            final String fkey = key;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable cg = new GradientDrawable(); cg.setColor(CARD); cg.setCornerRadius(dp(14));
            if (on) cg.setStroke(dp(2), ACC);
            cell.setBackground(cg);
            cell.setClipToOutline(true);
            View sw = new View(this);
            GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{cols[i][0], cols[i][1]});
            sg.setCornerRadius(dp(12));
            sw.setBackground(sg);
            cell.addView(sw, new LinearLayout.LayoutParams(-1, dp(56)));
            TextView nm = tv((on ? "✓ " : "") + themes[i][1], 14, on ? ACC : TXT);
            nm.setPadding(dp(10), dp(8), dp(10), dp(8));
            cell.addView(nm);
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { prefs.edit().putString(fkey, val).apply(); touch(); rebuild(); }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i % 3 != 2) cp.rightMargin = dp(10);
            rowl.addView(cell, cp);
        }
        // relleno para la última fila incompleta
        if (themes.length % 3 != 0 && rowl != null)
            for (int k = themes.length % 3; k < 3; k++) {
                View spacer = new View(this);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (k != 2) sp.rightMargin = dp(10);
                rowl.addView(spacer, sp);
            }
        return gridWrap;
    }

    // ---------- brillo automático: horas del modo auto ----------
    private LinearLayout hoursRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(4), dp(14), dp(12));
        row.addView(tv("Oscuro de", 14, DIM));
        row.addView(hourStepper("web.darkFrom", 18));
        row.addView(tv("a", 14, DIM));
        row.addView(hourStepper("web.darkTo", 6));
        return row;
    }

    private LinearLayout hourStepper(final String key, int def) {
        LinearLayout s = new LinearLayout(this);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.setPadding(dp(8), 0, dp(8), 0);
        s.addView(stepBtn("−", key, -1, def));
        final TextView t = tv(hourTxt(getInt(key, def)), 15, TXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), 0, dp(8), 0);
        s.addView(t);
        s.addView(stepBtn("+", key, 1, def));
        return s;
    }

    private TextView stepBtn(String sign, final String key, final int delta, final int def) {
        TextView b = tv(sign, 18, TXT);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(SOFT);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int nv = (getInt(key, def) + delta + 24) % 24;
                prefs.edit().putString(key, String.valueOf(nv)).apply(); touch(); rebuild();
            }
        });
        return b;
    }

    // ---------- editor de tramos de brillo ----------
    private LinearLayout bandsEditor() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = dp(10);
        wrap.setLayoutParams(wl);

        LinearLayout graphCard = card();
        graphCard.setPadding(dp(12), dp(12), dp(12), dp(10));
        TextView how = tv("Arrastra un punto: ↕ brillo, ↔ hora · toca un espacio vacío para agregar · mantén presionado para borrar", 13, DIM);
        how.setPadding(dp(4), 0, 0, dp(8));
        graphCard.addView(how);
        graphCard.addView(new BandGraph(this), new LinearLayout.LayoutParams(-1, dp(440)));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.bottomMargin = dp(10);
        wrap.addView(graphCard, gl);

        final List<int[]> bands = bandList();
        for (int i = 0; i < bands.size(); i++) {
            final int idx = i;
            final int[] band = bands.get(i);
            LinearLayout b = card();
            b.setOrientation(LinearLayout.HORIZONTAL);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.bottomMargin = dp(8);
            b.setLayoutParams(bl);
            // hora −/+
            b.addView(bandHour("−", idx, -1));
            final TextView ht = tv(hourTxt(band[0]), 15, TXT);
            ht.setGravity(Gravity.CENTER); ht.setPadding(dp(6), 0, dp(6), 0);
            ht.setWidth(dp(68));
            b.addView(ht);
            b.addView(bandHour("+", idx, 1));
            // brillo
            LinearLayout br = new LinearLayout(this);
            br.setOrientation(LinearLayout.VERTICAL);
            final TextView lvl = tv("Brillo " + band[1] + " %", 13, DIM);
            br.addView(lvl);
            SeekBar sb = new SeekBar(this);
            sb.setMax(100); sb.setProgress(band[1]);
            sb.getProgressDrawable().setColorFilter(ACC, android.graphics.PorterDuff.Mode.SRC_IN);
            sb.getThumb().setColorFilter(ACC, android.graphics.PorterDuff.Mode.SRC_IN);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean u) {
                    int v = Math.max(18, p);
                    lvl.setText("Brillo " + v + " %");
                    band[1] = v;
                }
                public void onStartTrackingTouch(SeekBar s) { touchingWidget = true; }
                public void onStopTrackingTouch(SeekBar s) { saveBands(bands); rebuild(); }
            });
            br.addView(sb, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams brl = new LinearLayout.LayoutParams(0, -2, 1f);
            brl.leftMargin = brl.rightMargin = dp(10);
            b.addView(br, brl);
            // borrar
            TextView del = tv("✕", 16, 0xFFFF6961);
            del.setGravity(Gravity.CENTER);
            GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(0x33FF453A);
            del.setBackground(dg);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { if (bands.size() > 1) { bands.remove(idx); saveBands(bands); rebuild(); } }
            });
            b.addView(del, new LinearLayout.LayoutParams(dp(36), dp(36)));
            wrap.addView(b);
        }

        TextView add = tv("+ Agregar tramo", 15, ACC);
        add.setGravity(Gravity.CENTER);
        add.setPadding(0, dp(12), 0, dp(12));
        GradientDrawable ag = new GradientDrawable(); ag.setColor(CARD); ag.setCornerRadius(dp(14));
        add.setBackground(ag);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<int[]> l = bandList();
                Set<Integer> used = new HashSet<Integer>();
                for (int[] x : l) used.add(x[0]);
                int nh = 12; while (used.contains(nh)) nh = (nh + 1) % 24;
                l.add(new int[]{nh, 60}); saveBands(l); rebuild();
            }
        });
        wrap.addView(add);
        TextView note = tv("Cada tramo rige desde su hora hasta el siguiente. Mínimo 18 % (por debajo la pantalla se apaga).", 12, DIM);
        note.setPadding(dp(4), dp(8), dp(4), 0);
        wrap.addView(note);
        return wrap;
    }

    private TextView bandHour(String sign, final int idx, final int delta) {
        TextView b = tv(sign, 18, TXT);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(SOFT);
        b.setBackground(g);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<int[]> l = bandList();
                l.get(idx)[0] = (l.get(idx)[0] + delta + 24) % 24;
                saveBands(l); rebuild();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        b.setLayoutParams(lp);
        return b;
    }

    private List<int[]> bandList() {
        List<int[]> out = new ArrayList<int[]>();
        for (String s : prefs.getString("autobright.bands", "7:90,19:55,22:25").split(",")) {
            String[] hp = s.split(":");
            if (hp.length != 2) continue;
            try { out.add(new int[]{Integer.parseInt(hp[0].trim()), Integer.parseInt(hp[1].trim())}); } catch (Exception e) { }
        }
        Collections.sort(out, new java.util.Comparator<int[]>() { public int compare(int[] a, int[] b) { return a[0] - b[0]; } });
        return out;
    }

    private void saveBands(List<int[]> l) {
        Collections.sort(l, new java.util.Comparator<int[]>() { public int compare(int[] a, int[] b) { return a[0] - b[0]; } });
        StringBuilder sb = new StringBuilder();
        for (int[] x : l) { if (sb.length() > 0) sb.append(","); sb.append(x[0]).append(":").append(x[1]); }
        prefs.edit().putString("autobright.bands", sb.toString()).apply(); touch();
    }

    private String shape() { return prefs.getString("autobright.shape", "curve"); }

    /** % para una hora con fracción, según la forma elegida (arco suave o escalones). */
    private int curveAt(List<int[]> l, float hour) { return Math.round(curveF(l, hour)); }

    private float curveF(List<int[]> l, float hour) {
        StringBuilder sb = new StringBuilder();
        for (int[] x : l) { if (sb.length() > 0) sb.append(","); sb.append(x[0]).append(":").append(x[1]); }
        return Views.brightAtF(sb.toString(), shape(), hour);
    }

    private int brightAt(List<int[]> l, int hour) {
        int best = -1, bestH = -1, lastP = -1, lastH = -1;
        for (int[] b : l) {
            if (b[0] > lastH) { lastH = b[0]; lastP = b[1]; }
            if (b[0] <= hour && b[0] > bestH) { bestH = b[0]; best = b[1]; }
        }
        return best >= 0 ? best : lastP;
    }

    /** Gráfico escalonado del brillo a lo largo del día (igual al de la web). */
    /**
     * Editor táctil del brillo por horario (grande): arrastra un punto (↕ brillo, ↔ hora), toca un espacio vacío
     * para agregar un tramo y mantén presionado un punto para borrarlo. Se guarda al soltar.
     */
    private class BandGraph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<int[]> l = bandList();
        private int drag = -1;
        private boolean moved = false;
        private float downX, downY;
        private final Runnable longPress = new Runnable() {
            public void run() {
                if (drag >= 0 && !moved && l.size() > 1) {
                    l.remove(drag); drag = -1;
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    saveBands(l); rebuild();
                }
            }
        };

        BandGraph(android.content.Context c) { super(c); }

        private float left() { return dp(44); }
        private float right() { return getWidth() - dp(16); }
        private float top() { return dp(16); }
        private float bottom() { return getHeight() - dp(30); }
        private float xFor(float hour) { return left() + (right() - left()) * hour / 24f; }
        private float yFor(int pct) { return bottom() - (pct / 100f) * (bottom() - top()); }
        private float yForF(float pct) { return bottom() - (pct / 100f) * (bottom() - top()); }
        private int hourAt(float x) { return Math.max(0, Math.min(23, Math.round((x - left()) / (right() - left()) * 24f))); }
        private int pctAt(float y) { return Math.max(18, Math.min(100, Math.round((bottom() - y) / (bottom() - top()) * 100f))); }

        private int hit(float x, float y) {
            for (int i = 0; i < l.size(); i++)
                if (Math.hypot(x - xFor(l.get(i)[0]), y - yFor(l.get(i)[1])) < dp(30)) return i;
            return -1;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);   // que el scroll no se lleve el gesto
                    touchingWidget = true;
                    downX = x; downY = y; moved = false;
                    drag = hit(x, y);
                    if (drag >= 0) postDelayed(longPress, 650);
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (drag < 0) return true;
                    if (Math.hypot(x - downX, y - downY) > dp(8)) { moved = true; removeCallbacks(longPress); }
                    if (!moved) return true;
                    int nh = hourAt(x);
                    boolean taken = false;
                    for (int i = 0; i < l.size(); i++) if (i != drag && l.get(i)[0] == nh) taken = true;
                    if (!taken) l.get(drag)[0] = nh;
                    l.get(drag)[1] = pctAt(y);
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    removeCallbacks(longPress);
                    if (drag >= 0 && moved) { saveBands(l); rebuild(); }
                    else if (drag < 0 && Math.hypot(x - downX, y - downY) < dp(8) && x >= left() && x <= right()) {
                        int nh2 = hourAt(x);
                        for (int[] b : l) if (b[0] == nh2) { drag = -1; invalidate(); return true; }
                        l.add(new int[]{nh2, pctAt(y)});
                        saveBands(l); rebuild();
                    }
                    drag = -1; invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress); drag = -1; invalidate();
                    return true;
            }
            return true;
        }

        protected void onDraw(Canvas c) {
            Collections.sort(l, new java.util.Comparator<int[]>() { public int compare(int[] a, int[] b) { return a[0] - b[0]; } });
            float L = left(), R = right(), T = top(), B = bottom();
            // cuadrícula: 25/50/75/100 %
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(LINE);
            p.setPathEffect(null);
            for (int v = 25; v <= 100; v += 25) c.drawLine(L, yFor(v), R, yFor(v), p);
            for (int hr = 0; hr <= 24; hr += 3) c.drawLine(xFor(hr), T, xFor(hr), B, p);
            p.setStyle(Paint.Style.FILL); p.setColor(DIM); p.setTextSize(dp(11)); p.setTextAlign(Paint.Align.RIGHT);
            for (int v = 25; v <= 100; v += 25) c.drawText(v + "%", L - dp(8), yFor(v) + dp(4), p);
            p.setTextAlign(Paint.Align.CENTER);
            for (int hr = 0; hr <= 24; hr += 3) c.drawText(hourTxt(hr % 24), xFor(hr), B + dp(20), p);
            // curva escalonada + relleno
            android.graphics.Path area = new android.graphics.Path();
            if ("curve".equals(shape())) {
                area.moveTo(L, yForF(curveF(l, 0)));
                for (float hr = 0.1f; hr <= 24.001f; hr += 0.1f) area.lineTo(xFor(hr), yForF(curveF(l, Math.min(23.999f, hr))));
            } else {
                area.moveTo(L, yFor(brightAt(l, 0)));
                for (int hr = 1; hr <= 24; hr++) {
                    area.lineTo(xFor(hr), yFor(brightAt(l, hr - 1)));
                    if (hr < 24) area.lineTo(xFor(hr), yFor(brightAt(l, hr)));
                }
            }
            android.graphics.Path fill = new android.graphics.Path(area);
            fill.lineTo(R, B); fill.lineTo(L, B); fill.close();
            p.setShader(new LinearGradient(0, T, 0, B, 0x660A84FF, 0x0A0A84FF, Shader.TileMode.CLAMP));
            c.drawPath(fill, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(3)); p.setColor(ACC);
            c.drawPath(area, p);
            // piso 18 %
            p.setStrokeWidth(dp(1.5f)); p.setColor(WARN);
            p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(6), dp(4)}, 0));
            c.drawLine(L, yFor(18), R, yFor(18), p);
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.LEFT); p.setTextSize(dp(11));
            c.drawText("mínimo 18 % (por debajo la pantalla se apaga)", L + dp(6), yFor(18) - dp(5), p);
            // hora actual
            java.util.Calendar cal = java.util.Calendar.getInstance();
            float nx = xFor(cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.5f)); p.setColor((TXT & 0x00FFFFFF) | 0x88000000);
            c.drawLine(nx, T, nx, B, p);
            p.setStyle(Paint.Style.FILL); p.setColor(TXT); p.setTextAlign(Paint.Align.CENTER);
            c.drawText("ahora", nx, T - dp(4), p);
            // puntos grandes (tocables)
            for (int i = 0; i < l.size(); i++) {
                float px = xFor(l.get(i)[0]), py = yFor(l.get(i)[1]);
                boolean sel = i == drag;
                p.setStyle(Paint.Style.FILL);
                p.setColor(sel ? 0x550A84FF : 0x330A84FF);
                c.drawCircle(px, py, dp(sel ? 22 : 16), p);
                p.setColor(0xFFFFFFFF); c.drawCircle(px, py, dp(9), p);
                p.setColor(ACC); c.drawCircle(px, py, dp(5.5f), p);
                // etiqueta del punto
                String lab = hourTxt(l.get(i)[0]) + " · " + l.get(i)[1] + "%";
                p.setTextSize(dp(sel ? 14 : 12)); p.setColor(TXT);
                float ty = py - dp(22) < T + dp(10) ? py + dp(32) : py - dp(22);
                c.drawText(lab, Math.max(L + dp(40), Math.min(R - dp(40), px)), ty, p);
            }
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    // ======================= apps: mostrar / ocultar =======================
    private LinearLayout appsCard() {
        LinearLayout c = card();
        PackageManager pm = getPackageManager();
        Set<String> hidden = new HashSet<String>(prefs.getStringSet("hidden", new HashSet<String>()));
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = new ArrayList<ResolveInfo>();
        for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
            if (r.activityInfo.name.equals(HomeActivity.class.getName())) continue;   // el propio launcher no
            apps.add(r);
        }
        final PackageManager fpm = pm;
        Collections.sort(apps, new java.util.Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(fpm)).compareToIgnoreCase(String.valueOf(b.loadLabel(fpm)));
            }
        });
        if (apps.isEmpty()) { c.addView(pad(tv("No se pudieron leer las apps.", 14, DIM))); return c; }
        final boolean custom = "custom".equals(prefs.getString("drawer.sort", "name"));
        final List<String> keys = new ArrayList<String>();
        if (custom) {
            final List<String> order = java.util.Arrays.asList(prefs.getString("drawer.order", "").split(","));
            Collections.sort(apps, new java.util.Comparator<ResolveInfo>() {
                public int compare(ResolveInfo a, ResolveInfo b) {
                    int ia = order.indexOf(a.activityInfo.packageName + "/" + a.activityInfo.name);
                    int ib = order.indexOf(b.activityInfo.packageName + "/" + b.activityInfo.name);
                    if (ia < 0) ia = 9999; if (ib < 0) ib = 9999;
                    return ia - ib;
                }
            });
        }
        for (ResolveInfo r : apps) keys.add(r.activityInfo.packageName + "/" + r.activityInfo.name);
        int shown = 0;
        for (int i = 0; i < apps.size(); i++) {
            final ResolveInfo r = apps.get(i);
            final String k = r.activityInfo.packageName + "/" + r.activityInfo.name;
            final boolean isHidden = hidden.contains(k);
            if (i > 0) { View div = new View(this); div.setBackgroundColor(LINE); c.addView(div, new LinearLayout.LayoutParams(-1, 1)); }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            ImageView iv = new ImageView(this);
            final String customPath = prefs.getString("icon." + k, null);
            android.graphics.Bitmap cb = customPath != null ? android.graphics.BitmapFactory.decodeFile(customPath) : null;
            if (cb != null) iv.setImageBitmap(cb); else iv.setImageDrawable(r.loadIcon(pm));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(44), dp(44));
            il.rightMargin = dp(12);
            iv.setAlpha(isHidden ? 0.4f : 1f);
            iv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickIconFor(k); } });
            row.addView(iv, il);
            TextView name = tv(String.valueOf(r.loadLabel(pm)), 15, isHidden ? DIM : TXT);
            name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView chg = smallBtn("Cambiar ícono");
            chg.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickIconFor(k); } });
            row.addView(chg);
            if (customPath != null) {
                TextView rs = smallBtn("↺ Original");
                rs.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { prefs.edit().remove("icon." + k).apply(); touch(); rebuild(); }
                });
                row.addView(rs);
            }
            if (custom) {
                final int pos = i;
                TextView up = smallBtn("▲"), dn = smallBtn("▼");
                up.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { moveApp(keys, pos, -1); } });
                dn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { moveApp(keys, pos, 1); } });
                row.addView(up); row.addView(dn);
            }
            final TextView tgl = tv(isHidden ? "Oculta" : "Visible", 14, 0xFFFFFFFF);
            tgl.setGravity(Gravity.CENTER);
            tgl.setPadding(dp(16), dp(7), dp(16), dp(7));
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(14));
            g.setColor(isHidden ? SOFT : ACC);
            if (isHidden) g.setStroke(dp(1), DIM);
            tgl.setBackground(g);
            tgl.setTextColor(isHidden ? DIM : 0xFFFFFFFF);
            tgl.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Set<String> hs = new HashSet<String>(prefs.getStringSet("hidden", new HashSet<String>()));
                    if (!hs.remove(k)) hs.add(k);
                    prefs.edit().putStringSet("hidden", hs).apply();
                    touch();
                    rebuild();
                }
            });
            row.addView(tgl);
            c.addView(row);
            shown++;
        }
        return c;
    }

    private TextView smallBtn(String t) {
        TextView b = tv(t, 14, TXT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable g = new GradientDrawable(); g.setColor(SOFT); g.setCornerRadius(dp(12));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void moveApp(List<String> keys, int pos, int d) {
        int np = pos + d;
        if (np < 0 || np >= keys.size()) return;
        Collections.swap(keys, pos, np);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) { if (sb.length() > 0) sb.append(","); sb.append(k); }
        prefs.edit().putString("drawer.order", sb.toString()).apply();
        touch(); rebuild();
    }

    // ---------- reemplazar el ícono de una app por una imagen (galería o USB) ----------
    private String pickingKey;

    private void pickIconFor(String key) {
        pickingKey = key;
        try {
            Intent it = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(it, "Elegir ícono"), 77);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "No hay una app para elegir imágenes", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 77 || res != RESULT_OK || data == null || data.getData() == null || pickingKey == null) return;
        try {
            java.io.InputStream in = getContentResolver().openInputStream(data.getData());
            android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
            if (src == null) throw new Exception("imagen inválida");
            // recorte cuadrado al centro y 192 px: liviano para el launcher
            int side = Math.min(src.getWidth(), src.getHeight());
            android.graphics.Bitmap sq = android.graphics.Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            android.graphics.Bitmap out = android.graphics.Bitmap.createScaledBitmap(sq, 192, 192, true);
            java.io.File dir = new java.io.File(getFilesDir(), "icons");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, Integer.toHexString(pickingKey.hashCode()) + "_" + System.currentTimeMillis() + ".png");
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
            o.close();
            prefs.edit().putString("icon." + pickingKey, f.getAbsolutePath()).apply();
            touch();
            rebuild();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "No se pudo usar esa imagen: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private View pad(TextView t) { t.setPadding(dp(14), dp(12), dp(14), dp(12)); return t; }

    // ======================= permisos =======================
    private LinearLayout permsCard() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout c = card();
        int missing = 0;
        for (int i = 0; i < PERMS.length; i++) {
            final String[] perm = PERMS[i];
            boolean ok = permGranted(perm[0]);
            if (!ok) missing++;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(11), dp(14), dp(11));
            if (i > 0) { View div = new View(this); div.setBackgroundColor(LINE); c.addView(div, new LinearLayout.LayoutParams(-1, 1)); }
            TextView dot = tv(ok ? "✓" : "!", 14, 0xFFFFFFFF);
            dot.setGravity(Gravity.CENTER);
            GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(ok ? OK : WARN);
            dot.setBackground(dg);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(28), dp(28));
            dl.rightMargin = dp(12);
            row.addView(dot, dl);
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(tv(perm[1], 15, TXT));
            tx.addView(tv(perm[2], 12, DIM));
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
            if (ok) row.addView(tv("Concedido", 13, OK));
            else {
                TextView give = tv("Dar", 14, 0xFFFFFFFF);
                give.setGravity(Gravity.CENTER);
                give.setPadding(dp(16), dp(7), dp(16), dp(7));
                GradientDrawable gg = new GradientDrawable(); gg.setColor(ACC); gg.setCornerRadius(dp(14));
                give.setBackground(gg);
                give.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { permRequest(perm[0]); } });
                row.addView(give);
            }
            c.addView(row);
        }
        wrap.addView(c);
        TextView all = tv(missing == 0 ? "Todos los permisos concedidos ✓" : "Conceder los " + missing + " que faltan", 16, missing == 0 ? OK : 0xFFFFFFFF);
        all.setGravity(Gravity.CENTER);
        all.setPadding(0, dp(13), 0, dp(13));
        GradientDrawable ag = new GradientDrawable(); ag.setColor(missing == 0 ? 0x3330D158 : ACC); ag.setCornerRadius(dp(14));
        all.setBackground(ag);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(10);
        all.setLayoutParams(al);
        if (missing > 0) all.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startPermWizard(); } });
        wrap.addView(all);
        TextView note = tv("Android no deja dar todo de un toque: se abre la pantalla de cada permiso que falte; actívalo, vuelve, y sigue solo con el siguiente.", 12, DIM);
        note.setPadding(dp(4), dp(8), dp(4), 0);
        wrap.addView(note);
        return wrap;
    }

    // {id, nombre, para qué, permisos de Android (vacío = pantalla especial)}
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
    private final Set<String> permAsked = new HashSet<String>();

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
        } catch (Exception e) {
            try { startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:" + getPackageName()))); } catch (Exception x) { }
        }
    }

    void startPermWizard() { permAsked.clear(); permWizard = true; continueWizard(); }

    private void continueWizard() {
        if (!permWizard) return;
        h.postDelayed(new Runnable() {
            public void run() {
                if (!permWizard) return;
                int missing = 0;
                for (String[] p : PERMS) {
                    if (permGranted(p[0])) continue;
                    missing++;
                    if (permAsked.contains(p[0])) continue;
                    if (p[3].length() > 0) { for (String[] q : PERMS) if (q[3].length() > 0) permAsked.add(q[0]); }
                    else permAsked.add(p[0]);
                    android.widget.Toast.makeText(SettingsActivity.this, "Permiso: " + p[1] + " — " + p[2], android.widget.Toast.LENGTH_LONG).show();
                    permRequest(p[0]);
                    return;
                }
                permWizard = false;
                android.widget.Toast.makeText(SettingsActivity.this, missing == 0 ? "✅ Todos los permisos concedidos"
                        : "Quedaron " + missing + " permisos sin dar; puedes darlos uno por uno en Permisos.", android.widget.Toast.LENGTH_LONG).show();
                rebuild();
            }
        }, 700);
    }

    // ======================= utilidades =======================
    private int getInt(String key, int def) {
        try { return Integer.parseInt(prefs.getString(key, String.valueOf(def))); } catch (Exception e) { return def; }
    }

    private String hourTxt(int h) {
        if ("24".equals(prefs.getString("web.clock", "12"))) return (h < 10 ? "0" + h : h) + ":00";
        int hh = h % 12; if (hh == 0) hh = 12;
        return hh + (h < 12 ? " AM" : " PM");
    }

    private TextView tv(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
