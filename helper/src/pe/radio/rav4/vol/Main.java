package pe.radio.rav4.vol;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Pantalla mínima: hay que abrirla una vez después de instalar (Android no activa apps nunca abiertas). */
public class Main extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean ok = Quiet.put(this, 0);
        TextView t = new TextView(this);
        t.setTextSize(20);
        t.setPadding(40, 40, 40, 40);
        t.setText(ok ? "Listo ✓\n\nEl RAV4 Launcher ya puede cambiar el volumen sin que aparezca la ventana de la radio.\nPuedes cerrar esto."
                : "No se pudo activar.\nAndroid no dejó escribir el ajuste en esta radio.");
        setContentView(t);
    }
}
