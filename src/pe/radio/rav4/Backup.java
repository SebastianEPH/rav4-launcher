package pe.radio.rav4;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Respaldo y restauración de TODA la configuración (Ajustes Launcher → Sistema).
 * Un respaldo es un .json con: todas las preferencias "rav4" (con su tipo) + los íconos personalizados
 * (files/icons) + la imagen de fondo (files/bg.jpg), en base64. Se guarda en la memoria interna
 * (files/respaldos) y, si hay pendrive, también en RAV4Launcher/respaldos del USB.
 */
public class Backup {
    static final String[] SKIP = {"stamp"};   // claves que no tiene sentido restaurar

    public static File localDir(Context c) { File d = new File(c.getFilesDir(), "respaldos"); d.mkdirs(); return d; }

    public static File usbDir() {
        File u = CanBridge.usbDir();
        if (u == null) return null;
        File d = new File(u, "respaldos"); d.mkdirs();
        return d.isDirectory() && d.canWrite() ? d : null;
    }

    /** Guarda un respaldo. Devuelve un texto para mostrar (dónde quedó) o "ERROR: …". */
    public static String save(Context c, String label) {
        try {
            JSONObject root = new JSONObject();
            root.put("app", "RAV4 Launcher");
            root.put("version", Updater.currentVersion(c));
            root.put("fecha", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
            root.put("nombre", label);
            JSONObject prefs = new JSONObject();
            SharedPreferences sp = c.getSharedPreferences("rav4", 0);
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                if (Arrays.asList(SKIP).contains(e.getKey())) continue;
                Object v = e.getValue();
                JSONObject t = new JSONObject();
                if (v instanceof String) { t.put("t", "s"); t.put("v", v); }
                else if (v instanceof Integer) { t.put("t", "i"); t.put("v", v); }
                else if (v instanceof Long) { t.put("t", "l"); t.put("v", v); }
                else if (v instanceof Float) { t.put("t", "f"); t.put("v", (double) (Float) v); }
                else if (v instanceof Boolean) { t.put("t", "b"); t.put("v", v); }
                else if (v instanceof Set) { t.put("t", "set"); JSONArray a = new JSONArray(); for (Object o : (Set<?>) v) a.put(String.valueOf(o)); t.put("v", a); }
                else continue;
                prefs.put(e.getKey(), t);
            }
            root.put("prefs", prefs);
            // archivos: íconos personalizados y fondo propio
            JSONObject files = new JSONObject();
            File icons = new File(c.getFilesDir(), "icons");
            File[] ic = icons.listFiles();
            if (ic != null) for (File f : ic) files.put("icons/" + f.getName(), b64(f));
            File bg = new File(c.getFilesDir(), "bg.jpg");
            if (bg.exists()) files.put("bg.jpg", b64(bg));
            root.put("files", files);

            String name = "rav4_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".json";
            byte[] data = root.toString(1).getBytes("UTF-8");
            write(new File(localDir(c), name), data);
            String where = "memoria de la radio";
            File u = usbDir();
            if (u != null) { try { write(new File(u, name), data); where += " y USB"; } catch (Exception ignored) { } }
            return name + " (" + where + ")";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** Restaura un respaldo. Antes guarda automáticamente la configuración actual (para poder deshacer). */
    public static String restore(Context c, File f) {
        try {
            JSONObject root = new JSONObject(new String(read(f), "UTF-8"));
            save(c, "Automático · antes de restaurar");
            JSONObject prefs = root.getJSONObject("prefs");
            SharedPreferences.Editor ed = c.getSharedPreferences("rav4", 0).edit();
            ed.clear();
            Iterator<String> it = prefs.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject t = prefs.getJSONObject(k);
                String ty = t.getString("t");
                if ("s".equals(ty)) ed.putString(k, t.getString("v"));
                else if ("i".equals(ty)) ed.putInt(k, t.getInt("v"));
                else if ("l".equals(ty)) ed.putLong(k, t.getLong("v"));
                else if ("f".equals(ty)) ed.putFloat(k, (float) t.getDouble("v"));
                else if ("b".equals(ty)) ed.putBoolean(k, t.getBoolean("v"));
                else if ("set".equals(ty)) {
                    JSONArray a = t.getJSONArray("v"); Set<String> set = new HashSet<String>();
                    for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                    ed.putStringSet(k, set);
                }
            }
            JSONObject files = root.optJSONObject("files");
            if (files != null) {
                Iterator<String> fk = files.keys();
                while (fk.hasNext()) {
                    String rel = fk.next();
                    if (rel.contains("..")) continue;   // solo dentro de files/
                    File out = new File(c.getFilesDir(), rel);
                    out.getParentFile().mkdirs();
                    write(out, Base64.decode(files.getString(rel), Base64.DEFAULT));
                }
                // los íconos guardan la ruta absoluta: se rehace por si cambió
                Iterator<String> pk = prefs.keys();
                while (pk.hasNext()) {
                    String k = pk.next();
                    if (!k.startsWith("icon.")) continue;
                    String path = prefs.getJSONObject(k).optString("v", "");
                    File nf = new File(new File(c.getFilesDir(), "icons"), new File(path).getName());
                    if (nf.exists()) ed.putString(k, nf.getAbsolutePath());
                }
            }
            ed.putLong("stamp", System.currentTimeMillis());
            ed.apply();
            return "Restaurado: " + root.optString("fecha", f.getName());
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** Respaldos disponibles (memoria interna + USB), del más nuevo al más viejo. */
    public static List<File> list(Context c) {
        List<File> out = new ArrayList<File>();
        for (File d : new File[]{localDir(c), usbDir()}) {
            if (d == null) continue;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) if (f.getName().endsWith(".json")) out.add(f);
        }
        java.util.Collections.sort(out, new Comparator<File>() {
            public int compare(File a, File b) { return b.getName().compareTo(a.getName()); }
        });
        return out;
    }

    /** "Nombre · fecha" leído del archivo (sin cargar los archivos adjuntos en pantalla). */
    public static String describe(File f) {
        try {
            JSONObject o = new JSONObject(new String(read(f), "UTF-8"));
            String n = o.optString("nombre", "");
            return o.optString("fecha", f.getName()) + (n.length() > 0 ? " · " + n : "") + " · v" + o.optString("version", "?");
        } catch (Exception e) { return f.getName(); }
    }

    private static String b64(File f) throws Exception { return Base64.encodeToString(read(f), Base64.NO_WRAP); }

    static byte[] read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }

    private static void write(File f, byte[] d) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(d); o.getFD().sync(); o.close();
    }
}
