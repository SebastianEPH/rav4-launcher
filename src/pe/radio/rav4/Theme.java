package pe.radio.rav4;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.content.res.Resources;

import java.util.Calendar;

/** Temas (terminal / glass) y modos (oscuro / claro / auto). */
public class Theme {

    public static final String TERMINAL = "terminal", GLASS = "glass", DUSK = "dusk", COCKPIT = "cockpit", HOME = "home";
    public static final String DARK = "dark", LIGHT = "light", AUTO = "auto";

    public static class Pal {
        public boolean dark, glass, dusk, cockpit, home;
        public int bg3; // tercer color del degradado (dusk)
        public int bg, bg2, panel, border, text, dim, accent, ok, warn, err, track, iconBg;
        public Typeface font, fontBold;
        public int radius; // dp
    }

    /** Paleta activa (la usan las vistas). */
    public static Pal P = build(TERMINAL, true);

    public static boolean isDarkNow(String mode) {
        if (DARK.equals(mode)) return true;
        if (LIGHT.equals(mode)) return false;
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hr < 6 || hr >= 18;
    }

    public static Pal fromPrefs(SharedPreferences p) {
        String mode = p.getString("mode", DARK);
        boolean dark;
        if (AUTO.equals(mode)) {
            int from = 18, to = 6;
            try { from = Integer.parseInt(p.getString("web.darkFrom", "18")); to = Integer.parseInt(p.getString("web.darkTo", "6")); } catch (Exception e) { }
            int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            dark = from > to ? (hr >= from || hr < to) : (hr >= from && hr < to);
        } else dark = DARK.equals(mode);
        String th = p.getString("theme", TERMINAL);
        if ("auto".equals(th)) th = COCKPIT;   // Auto · CAN solo existe en web
        return build(th, dark);
    }

    public static Pal build(String theme, boolean dark) {
        Pal p = new Pal();
        p.dark = dark;
        p.dusk = DUSK.equals(theme);
        p.cockpit = COCKPIT.equals(theme);
        p.home = HOME.equals(theme);
        p.glass = GLASS.equals(theme) || p.dusk || p.cockpit || p.home;
        if (p.cockpit || p.home) {
            p.font = Typeface.create("sans-serif", Typeface.NORMAL);
            p.fontBold = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            p.radius = 20;
            if (dark) {
                p.panel = 0xEB1C1C1E; p.border = 0x00000000; p.text = 0xFFFFFFFF; p.dim = 0x99FFFFFF;
                p.accent = 0xFF0A84FF; p.ok = 0xFF30D158; p.warn = 0xFFFF9F0A; p.err = 0xFFFF453A;
                p.track = 0x33FFFFFF; p.iconBg = 0xFF2C2C2E;
            } else {
                p.panel = 0xF2FFFFFF; p.border = 0x00000000; p.text = 0xFF000000; p.dim = 0x993C3C43;
                p.accent = 0xFF007AFF; p.ok = 0xFF34C759; p.warn = 0xFFFF9500; p.err = 0xFFFF3B30;
                p.track = 0x1F000000; p.iconBg = 0xFFFFFFFF;
            }
            p.bg = p.bg2 = p.bg3 = dark ? 0xFF000000 : 0xFFE5E5EA;
            return p;
        }
        if (p.dusk) {
            p.font = Typeface.create("sans-serif", Typeface.NORMAL);
            p.fontBold = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            p.radius = 22;
            if (dark) {
                // atardecer: rosa arriba -> violeta -> noche
                p.bg = 0xFFE58FA6; p.bg2 = 0xFF8E5C9E; p.bg3 = 0xFF231A3A;
                p.panel = 0xE6202024; p.border = 0x00000000;
                p.text = 0xFFFFFFFF; p.dim = 0xB3FFFFFF; p.accent = 0xFF8AB4F8;
                p.ok = 0xFF81C995; p.warn = 0xFFE8A33C; p.err = 0xFFF28B82; p.track = 0x33FFFFFF; p.iconBg = 0xFF3C3C42;
            } else {
                // amanecer: durazno -> celeste -> blanco
                p.bg = 0xFFFFC9A8; p.bg2 = 0xFFB9D4F5; p.bg3 = 0xFFF4F6FA;
                p.panel = 0xF2FFFFFF; p.border = 0x00000000;
                p.text = 0xFF1F1F23; p.dim = 0x991F1F23; p.accent = 0xFF1A73E8;
                p.ok = 0xFF188038; p.warn = 0xFFB06000; p.err = 0xFFD93025; p.track = 0x1F000000; p.iconBg = 0xFFFFFFFF;
            }
            return p;
        }
        if (!p.glass) {
            p.font = Typeface.MONOSPACE;
            p.fontBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
            p.radius = 14;
            if (dark) {
                // fósforo verde de consola
                p.bg = 0xFF050A06; p.bg2 = 0xFF071209; p.panel = 0xFF0A140C; p.border = 0xFF1A4D24;
                p.text = 0xFFB9F6C5; p.dim = 0xFF4E8A5A; p.accent = 0xFF39FF6A;
                p.ok = 0xFF39FF6A; p.warn = 0xFFFFA657; p.err = 0xFFFF7B72; p.track = 0xFF12301A; p.iconBg = 0xFF0D1A10;
            } else {
                p.bg = p.bg2 = 0xFFF1F8F2; p.panel = 0xFFFFFFFF; p.border = 0xFFA8D5B2;
                p.text = 0xFF0F2A16; p.dim = 0xFF4F7359; p.accent = 0xFF138A36;
                p.ok = 0xFF1A7F37; p.warn = 0xFFBC4C00; p.err = 0xFFCF222E; p.track = 0xFFE4E9EE; p.iconBg = 0xFFF0F3F6;
            }
        } else {
            p.font = Typeface.create("sans-serif", Typeface.NORMAL);
            p.fontBold = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            p.radius = 28;
            if (dark) {
                p.bg = 0xFF0B1426; p.bg2 = 0xFF2B1747; p.panel = 0x73141418; p.border = 0x1FFFFFFF;
                p.text = 0xFFFFFFFF; p.dim = 0xA6FFFFFF; p.accent = 0xFF0A84FF;
                p.ok = 0xFF30D158; p.warn = 0xFFFF9F0A; p.err = 0xFFFF453A; p.track = 0x33FFFFFF; p.iconBg = 0xFF2C2C2E;
            } else {
                p.bg = 0xFFCFE0F5; p.bg2 = 0xFFF2DCEB; p.panel = 0xA6FFFFFF; p.border = 0x99FFFFFF;
                p.text = 0xFF000000; p.dim = 0x993C3C43; p.accent = 0xFF007AFF;
                p.ok = 0xFF34C759; p.warn = 0xFFFF9500; p.err = 0xFFFF3B30; p.track = 0x1F000000; p.iconBg = 0xFFFFFFFF;
            }
        }
        return p;
    }

    /** Ícono circular con fondo (tema Dusk). */
    public static Drawable circle(Resources res, Drawable icon, int sizePx, int bg) {
        Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path clip = new Path();
        clip.addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW);
        c.clipPath(clip);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(bg);
        c.drawRect(0, 0, sizePx, sizePx, p);
        int inset = Math.round(sizePx * 0.18f);
        icon.setBounds(inset, inset, sizePx - inset, sizePx - inset);
        icon.draw(c);
        return new BitmapDrawable(res, b);
    }

    /** Ícono en forma de "squircle" estilo iOS a partir del ícono original de la app. */
    public static Drawable squircle(Resources res, Drawable icon, int sizePx, int bg) {
        Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path clip = new Path();
        float r = sizePx * 0.225f;
        clip.addRoundRect(new RectF(0, 0, sizePx, sizePx), r, r, Path.Direction.CW);
        c.clipPath(clip);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(bg);
        c.drawRect(0, 0, sizePx, sizePx, p);
        int inset = Math.round(sizePx * 0.12f);
        icon.setBounds(inset, inset, sizePx - inset, sizePx - inset);
        icon.draw(c);
        // brillo superior sutil
        p.setColor(0x14FFFFFF);
        c.drawRect(0, 0, sizePx, sizePx * 0.45f, p);
        return new BitmapDrawable(res, b);
    }
}
