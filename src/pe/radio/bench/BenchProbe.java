package pe.radio.bench;

import android.app.Activity;
import android.os.Build;
import android.os.Debug;
import android.os.SystemClock;
import android.view.Choreographer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Medidor común de la prueba de rendimiento. El MISMO archivo se compila en las 4 apps (Nativo y Web dentro del
 * launcher; React Native y Flutter lo copian desde aquí), así todos miden exactamente igual.
 *
 * Mide durante 30 s: cuadros por segundo, cuadros lentos (> 33 ms), el peor cuadro, latencia de toque
 * (dedo en la pantalla → primer cuadro que ya muestra la reacción), uso de CPU y memoria. El arranque se mide
 * desde que Android crea el proceso hasta el primer cuadro dibujado.
 * Graba OrbitaLauncher/bench_<motor>_<fecha>.txt en el USB (y en /sdcard/OrbitaLauncher como respaldo).
 */
public class BenchProbe {
    public static final int DURATION_MS = 30000;
    private static final BenchProbe I = new BenchProbe();
    public static BenchProbe get() { return I; }

    private long startupMs = -1, launchT0 = 0;
    public static final String EXTRA_T0 = "bench_t0";

    /** Hora (uptime del sistema) en que Ajustes Launcher pidió abrir la prueba: mide el arranque completo, en frío. */
    public void launchedAt(android.content.Intent i) { if (i != null && launchT0 == 0) launchT0 = i.getLongExtra(EXTRA_T0, 0); }
    private volatile long lastDown = 0;
    private boolean running = false, choreo = false;
    private long t0, cpu0, lastFrameNs;
    private int frames, slow, worstMs;
    private final int[] hist = new int[8];            // cuadros por tramo: <17, <25, <34, <50, <100, <250, <500, más
    private final java.util.ArrayList<Integer> taps = new java.util.ArrayList<Integer>();

    /** Llamar en Activity.dispatchTouchEvent con ACTION_DOWN (hora del evento del sistema, no la de ahora). */
    public void touchDown(long eventUptimeMs) { lastDown = eventUptimeMs; }

    /** El motor llama esto en el primer cuadro que ya muestra la reacción al toque. */
    public synchronized void touchShown() {
        long d = lastDown;
        if (d == 0 || !running) return;
        lastDown = 0;
        taps.add((int) (SystemClock.uptimeMillis() - d));
    }

    /** Primer cuadro dibujado (solo cuenta la primera vez). */
    public synchronized void firstFrame() {
        if (startupMs >= 0) return;
        // sin la hora del toque (abierta desde la lista de apps) se usa el inicio del proceso, que se queda corto
        long st = launchT0 > 0 ? launchT0 : Build.VERSION.SDK_INT >= 24 ? android.os.Process.getStartUptimeMillis() : 0;
        startupMs = st > 0 ? SystemClock.uptimeMillis() - st : 0;
    }
    public long startupMs() { return startupMs; }

    /**
     * Empieza la prueba. useChoreographer=true cuenta los cuadros del hilo de la interfaz de Android (Nativo, React
     * Native). Web y Flutter dibujan en su propio hilo: pasan false y llaman frame() en cada cuadro suyo.
     */
    public synchronized void start(boolean useChoreographer) {
        running = true; choreo = useChoreographer;
        frames = 0; slow = 0; worstMs = 0; lastFrameNs = 0; taps.clear();
        for (int i = 0; i < hist.length; i++) hist[i] = 0;
        t0 = SystemClock.uptimeMillis(); cpu0 = cpuTicks();
        if (choreo) Choreographer.getInstance().postFrameCallback(cb);
    }
    public boolean running() { return running; }
    public int remainingMs() { return running ? (int) Math.max(0, DURATION_MS - (SystemClock.uptimeMillis() - t0)) : 0; }

    private final Choreographer.FrameCallback cb = new Choreographer.FrameCallback() {
        public void doFrame(long ns) { frameNs(ns); if (running && choreo) Choreographer.getInstance().postFrameCallback(this); }
    };

    /** Cuadro informado por el motor (milisegundos de su reloj; solo importan las diferencias). */
    public void frame(double ms) { frameNs((long) (ms * 1e6)); }

    private synchronized void frameNs(long ns) {
        if (!running) return;
        if (lastFrameNs != 0) {
            int d = (int) ((ns - lastFrameNs) / 1000000L);
            frames++;
            if (d > 34) slow++;
            if (d > worstMs) worstMs = d;
            int[] lim = {17, 25, 34, 50, 100, 250, 500};
            int k = 0; while (k < lim.length && d >= lim[k]) k++;
            hist[k]++;
        }
        lastFrameNs = ns;
    }

    /** Termina, guarda el archivo y devuelve el resumen para mostrarlo en pantalla. */
    public synchronized String finish(Activity a, String engine, String extra) {
        running = false;
        long wall = Math.max(1, SystemClock.uptimeMillis() - t0);
        long cpu = cpuTicks() - cpu0;
        int cores = Runtime.getRuntime().availableProcessors();
        double cpuCore = cpu * 10.0 * 100 / wall;       // ticks de 10 ms → % de un núcleo
        double fps = frames * 1000.0 / wall;
        Debug.MemoryInfo mi = new Debug.MemoryInfo();
        Debug.getMemoryInfo(mi);
        int ramMb = mi.getTotalPss() / 1024;
        int tapAvg = 0, tapMax = 0;
        for (int t : taps) { tapAvg += t; tapMax = Math.max(tapMax, t); }
        if (!taps.isEmpty()) tapAvg /= taps.size();

        StringBuilder s = new StringBuilder();
        s.append("# Prueba de rendimiento — Órbita Launcher\n");
        s.append("motor=").append(engine).append('\n');
        s.append("fecha=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        s.append("equipo=").append(Build.MODEL).append(" · ").append(Build.HARDWARE).append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        s.append("arranque_ms=").append(startupMs).append('\n');
        s.append("arranque_desde=").append(launchT0 > 0 ? "toque en Ajustes Launcher" : "inicio del proceso (aprox., abrir desde Ajustes → Prueba de rendimiento)").append('\n');
        s.append("duracion_ms=").append(wall).append('\n');
        s.append("fps=").append(String.format(Locale.US, "%.1f", fps)).append('\n');
        s.append("cuadros=").append(frames).append('\n');
        s.append("cuadros_lentos=").append(slow).append('\n');
        s.append("cuadros_lentos_pct=").append(frames == 0 ? 0 : slow * 100 / frames).append('\n');
        s.append("peor_cuadro_ms=").append(worstMs).append('\n');
        s.append("histograma_ms=<17:").append(hist[0]).append(" <25:").append(hist[1]).append(" <34:").append(hist[2]).append(" <50:").append(hist[3])
                .append(" <100:").append(hist[4]).append(" <250:").append(hist[5]).append(" <500:").append(hist[6]).append(" 500+:").append(hist[7]).append('\n');
        s.append("toques=").append(taps.size()).append('\n');
        s.append("toque_ms=").append(tapAvg).append('\n');
        s.append("toque_max_ms=").append(tapMax).append('\n');
        s.append("cpu_nucleo_pct=").append(Math.round(cpuCore)).append('\n');
        s.append("cpu_total_pct=").append(Math.round(cpuCore / cores)).append('\n');
        s.append("ram_mb=").append(ramMb).append('\n');
        if (extra != null && extra.length() > 0) s.append(extra.endsWith("\n") ? extra : extra + "\n");

        String name = "bench_" + engine + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        String where = save(name, s.toString());
        return String.format(Locale.US, "%s\n\nArranque: %d ms\nFluidez: %.1f fps · lentos %d%% · peor %d ms\nToque: %s\nCPU: %d%% de un núcleo · RAM: %d MB\n\n%s",
                engine, startupMs, fps, frames == 0 ? 0 : slow * 100 / frames, worstMs,
                taps.isEmpty() ? "no tocaste el velocímetro" : tapAvg + " ms promedio (" + taps.size() + " toques, máx. " + tapMax + ")",
                Math.round(cpuCore), ramMb, where);
    }

    private static long cpuTicks() {
        try {
            RandomAccessFile f = new RandomAccessFile("/proc/self/stat", "r");
            String l = f.readLine(); f.close();
            String[] p = l.substring(l.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(p[11]) + Long.parseLong(p[12]);   // utime + stime
        } catch (Exception e) { return 0; }
    }

    private static String save(String name, String txt) {
        String ok = null;
        File usb = usbDir();
        File[] dirs = {usb, new File(android.os.Environment.getExternalStorageDirectory(), "OrbitaLauncher")};
        for (File d : dirs) {
            if (d == null) continue;
            try {
                d.mkdirs();
                FileOutputStream o = new FileOutputStream(new File(d, name));
                o.write(txt.getBytes("UTF-8")); o.close();
                if (ok == null) ok = (d == usb ? "Guardado en el USB: " : "Guardado en la memoria interna: ") + d + "/" + name;
            } catch (Exception ignored) { }
        }
        return ok != null ? ok : "No se pudo guardar el archivo (¿falta el permiso de almacenamiento?)";
    }

    /** Carpeta OrbitaLauncher en el pendrive (mismas rutas que CanBridge.usbDir); null si no hay USB. */
    public static File usbDir() {
        java.util.List<File> c = new java.util.ArrayList<File>();
        for (String p : new String[]{"/mnt/media_rw/udisk", "/mnt/media_rw/udiskh", "/mnt/media_rw/udisk3",
                "/mnt/media_rw/udisk4", "/storage/udisk", "/storage/udiskh"}) c.add(new File(p));
        File[] st = new File("/storage").listFiles();
        if (st != null) for (File f : st) if (!f.getName().equals("emulated") && !f.getName().equals("self") && f.isDirectory()) c.add(f);
        for (File base : c) {
            if (!base.isDirectory()) continue;
            File d = new File(base, "OrbitaLauncher");
            d.mkdirs();
            if (d.isDirectory() && d.canWrite()) return d;
        }
        return null;
    }

    /** Lista los resultados guardados (USB y memoria interna), del más nuevo al más viejo. */
    public static java.util.List<File> results() {
        java.util.List<File> out = new java.util.ArrayList<File>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (File d : new File[]{usbDir(), new File(android.os.Environment.getExternalStorageDirectory(), "OrbitaLauncher")}) {
            File[] fs = d == null ? null : d.listFiles();
            if (fs != null) for (File f : fs) if (f.getName().startsWith("bench_") && f.getName().endsWith(".txt") && seen.add(f.getName())) out.add(f);
        }
        java.util.Collections.sort(out, new java.util.Comparator<File>() {
            public int compare(File x, File y) { return y.getName().substring(y.getName().lastIndexOf('_') - 8).compareTo(x.getName().substring(x.getName().lastIndexOf('_') - 8)); }
        });
        return out;
    }
}
