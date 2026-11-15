package pe.radio.rav4

import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Provisorio: la interfaz Flutter se arma en el siguiente paso. */
class FlutterHome(a: HomeActivity, @Suppress("UNUSED_PARAMETER") data: LauncherData) : ModernHome {
    private val v = TextView(a).apply { text = "Interfaz Flutter: en construcción"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(-1); setBackgroundColor(0xFF05060C.toInt()) }
    override fun view(): View = v
    override fun reload() {}
    override fun back() {}
    override fun resume() {}
    override fun pause() {}
    override fun destroy() {}
}
