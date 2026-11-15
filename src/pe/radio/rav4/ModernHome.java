package pe.radio.rav4;

import android.view.View;

/**
 * Interfaces "compiladas" alternativas a la Nativa y la Web: Compose (Kotlin), React Native y Flutter.
 * Todas leen los mismos datos (LauncherData) y usan la misma configuración (tema, modo, acento, reloj).
 * Por ahora traen el tema Cockpit; los demás temas caen a Cockpit.
 */
public interface ModernHome {
    View view();
    /** Ajustes Launcher cambió algo: releer preferencias. */
    void reload();
    /** Botón Inicio / atrás: cerrar el cajón de apps si está abierto. */
    void back();
    void resume();
    void pause();
    void destroy();

    static ModernHome create(HomeActivity a, String engine, LauncherData data) {
        if ("rn".equals(engine)) return new RnHome(a, data);
        if ("flutter".equals(engine)) return new FlutterHome(a, data);
        return new ComposeHome(a, data);
    }

    static boolean isModern(String engine) { return "compose".equals(engine) || "rn".equals(engine) || "flutter".equals(engine); }
}
