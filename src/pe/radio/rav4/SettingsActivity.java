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
 * "Ajustes RAV4" — ventana nativa (sin WebView) para configurar el launcher: interfaz, tema, apariencia,
 * brillo automático, permisos y sistema. Al volver, el launcher compara "stamp" y solo se rearma si algo cambió.
 */
public class SettingsActivity extends Activity {

    private static final int BG = 0xFF0B0C10, CARD = 0xFF17181E, LINE = 0x14FFFFFF,
            TXT = 0xFFF2F4F8, DIM = 0x8CF2F4F8, ACC = 0xFF0A84FF, OK = 0xFF30D158, WARN = 0xFFFF9F0A;

    private SharedPreferences prefs;
    private LinearLayout content;
    private final Handler h = new Handler();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("rav4", MODE_PRIVATE);

        LinearLayout rootc = new LinearLayout(this);
        rootc.setOrientation(LinearLayout.VERTICAL);
        rootc.setBackgroundColor(BG);

        // encabezado
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(16), dp(20), dp(12));
        TextView title = tv("Ajustes RAV4", 24, TXT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = tv("Listo", 17, 0xFFFFFFFF);
        done.setPadding(dp(22), dp(10), dp(22), dp(10));
        GradientDrawable db = new GradientDrawable(); db.setColor(ACC); db.setCornerRadius(dp(22));
        done.setBackground(db);
        done.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        head.addView(done);
        rootc.addView(head, new LinearLayout.LayoutParams(-1, -2));

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
    private void rebuild() {
        content.removeAllViews();

        section("Interfaz");
        LinearLayout ic = card();
        ic.addView(segRow("Tipo de interfaz", "La nativa es la más liviana", "engine",
                new String[]{"Nativa", "Web (animada)"}, new String[]{"native", "web"}, "native"));
        content.addView(ic);

        section("Tema");
        content.addView(themeGrid());

        section("Apariencia");
        LinearLayout ap = card();
        String modeKey = web() ? "web.mode" : "mode";
        ap.addView(segRow("Modo", null, modeKey,
                new String[]{"Oscuro", "Claro", "Automático"}, new String[]{"dark", "light", "auto"}, "dark"));
        if ("auto".equals(prefs.getString(modeKey, "dark"))) ap.addView(hoursRow());
        ap.addView(segRow("Reloj", null, "web.clock", new String[]{"12 h (AM/PM)", "24 h"}, new String[]{"12", "24"}, "12"));
        ap.addView(segRow("Fondo (Cockpit / Inicio)", null, "wall",
                new String[]{"Ondas", "Noche", "Atardecer", "Mi RAV4"}, new String[]{"ondas", "noche", "atardecer", "rav4"}, "ondas"));
        if (web()) {
            ap.addView(segRow("Color de acento", null, "accent",
                    new String[]{"Tema", "Azul", "Verde", "Naranja", "Rosa"}, new String[]{"theme", "blue", "green", "orange", "pink"}, "theme"));
            ap.addView(segRow("Efectos", "\"Altos\" agrega desenfoque; más lento", "web.fx",
                    new String[]{"Altos", "Suaves", "No"}, new String[]{"high", "low", "off"}, "low"));
        }
        content.addView(ap);

        section("Velocímetro y animaciones");
        LinearLayout va = card();
        va.addView(segRow("Tamaño del velocímetro (Cockpit)", null, "cockpit.size",
                new String[]{"Pequeño", "Mediano", "Grande"}, new String[]{"s", "m", "l"}, "m"));
        va.addView(segRow("Animaciones", "Carretera, estela de la aguja, barrido al encender", "native.anim",
                new String[]{"Sí", "No"}, new String[]{"on", "off"}, "on"));
        content.addView(va);

        section("Brillo automático");
        LinearLayout bc = card();
        bc.addView(segRow("Ajustar el brillo según la hora", "El launcher sube o baja el brillo solo", "autobright",
                new String[]{"Sí", "No"}, new String[]{"on", "off"}, "off"));
        content.addView(bc);
        if ("on".equals(prefs.getString("autobright", "off"))) content.addView(bandsEditor());

        section("Cajón de apps");
        LinearLayout dc = card();
        dc.addView(segRow("Tamaño de los íconos", null, "drawer.size",
                new String[]{"Pequeño", "Mediano", "Grande"}, new String[]{"s", "m", "l"}, "m"));
        dc.addView(segRow("Opacidad del fondo", null, "drawer.alpha",
                new String[]{"70 %", "85 %", "100 %"}, new String[]{"70", "85", "100"}, "100"));
        dc.addView(segRow("Difuminar el fondo", "Se calcula una vez al abrir (no da lag)", "drawer.blur",
                new String[]{"Sí", "No"}, new String[]{"on", "off"}, "off"));
        dc.addView(segRow("Orden de las apps", "Personalizado: usa ▲▼ en la lista de abajo", "drawer.sort",
                new String[]{"Por nombre", "Personalizado"}, new String[]{"name", "custom"}, "name"));
        content.addView(dc);

        section("Apps (toca el ícono para cambiarlo)");
        content.addView(appsCard());

        section("Permisos");
        content.addView(permsCard());

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
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0x14FFFFFF); bg.setCornerRadius(dp(12));
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
        String key = w ? "web.theme" : "theme";
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
            TextView nm = tv((on ? "✓ " : "") + themes[i][1], 14, on ? 0xFFFFFFFF : TXT);
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
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(0x14FFFFFF);
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
        graphCard.addView(new BandGraph(this), new LinearLayout.LayoutParams(-1, dp(110)));
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
                public void onStartTrackingTouch(SeekBar s) { }
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
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(0x14FFFFFF);
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

    private int brightAt(List<int[]> l, int hour) {
        int best = -1, bestH = -1, lastP = -1, lastH = -1;
        for (int[] b : l) {
            if (b[0] > lastH) { lastH = b[0]; lastP = b[1]; }
            if (b[0] <= hour && b[0] > bestH) { bestH = b[0]; best = b[1]; }
        }
        return best >= 0 ? best : lastP;
    }

    /** Gráfico escalonado del brillo a lo largo del día (igual al de la web). */
    private class BandGraph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        BandGraph(android.content.Context c) { super(c); }
        protected void onDraw(Canvas c) {
            List<int[]> l = bandList();
            float w = getWidth(), hh = getHeight(), pad = dp(4);
            float y0 = pad, y100 = hh - pad - dp(14);
            // relleno
            android.graphics.Path area = new android.graphics.Path();
            area.moveTo(0, yFor(brightAt(l, 0), y0, y100));
            for (int hr = 1; hr <= 24; hr++) {
                float x = w * hr / 24f;
                area.lineTo(x, yFor(brightAt(l, hr - 1), y0, y100));
                if (hr < 24) area.lineTo(x, yFor(brightAt(l, hr), y0, y100));
            }
            android.graphics.Path fill = new android.graphics.Path(area);
            fill.lineTo(w, hh); fill.lineTo(0, hh); fill.close();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, y0, 0, hh, 0x550A84FF, 0x100A84FF, Shader.TileMode.CLAMP));
            c.drawPath(fill, p);
            p.setShader(null);
            // línea de piso 18 %
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(WARN);
            float yFloor = yFor(18, y0, y100);
            android.graphics.DashPathEffect dash = new android.graphics.DashPathEffect(new float[]{dp(4), dp(3)}, 0);
            p.setPathEffect(dash);
            c.drawLine(0, yFloor, w, yFloor, p);
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(WARN); p.setTextSize(dp(9));
            c.drawText("18% mín.", dp(2), yFloor - dp(3), p);
            // curva
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f));
            p.setColor(ACC);
            c.drawPath(area, p);
            // puntos
            p.setStyle(Paint.Style.FILL);
            for (int[] b : l) { p.setColor(0xFFFFFFFF); c.drawCircle(w * b[0] / 24f, yFor(b[1], y0, y100), dp(3.5f), p); p.setColor(ACC); c.drawCircle(w * b[0] / 24f, yFor(b[1], y0, y100), dp(2f), p); }
            // marca hora actual
            java.util.Calendar cal = java.util.Calendar.getInstance();
            float nx = w * (cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f) / 24f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(0x66FFFFFF);
            c.drawLine(nx, 0, nx, hh, p);
            // etiquetas
            p.setStyle(Paint.Style.FILL); p.setColor(DIM); p.setTextSize(dp(9));
            String[] lab = {"12 AM", "6 AM", "12 PM", "6 PM", "12 AM"};
            p.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < 5; i++) {
                float x = w * i / 4f;
                c.drawText(lab[i], Math.max(dp(14), Math.min(w - dp(14), x)), hh - dp(2), p);
            }
            p.setTextAlign(Paint.Align.LEFT);
        }
        private float yFor(int pct, float y0, float y100) { return y100 - (pct / 100f) * (y100 - y0); }
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
            g.setColor(isHidden ? 0x1AFFFFFF : ACC);
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
        GradientDrawable g = new GradientDrawable(); g.setColor(0x14FFFFFF); g.setCornerRadius(dp(12));
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
