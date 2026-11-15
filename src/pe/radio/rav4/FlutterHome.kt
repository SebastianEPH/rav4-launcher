package pe.radio.rav4

import android.view.View
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

/**
 * Interfaz Flutter (Dart, flutter_ui/lib/main.dart) — tema Cockpit.
 * El motor de Flutter se crea una vez por proceso y se reutiliza al volver a esta interfaz.
 * Canal "launcher": la página lee el estado/las apps (copias en caché de LauncherData) y pide acciones.
 */
class FlutterHome(private val a: HomeActivity, private val data: LauncherData) : ModernHome {
    companion object { private var engine: FlutterEngine? = null }

    private val fv = FlutterView(a)
    private val channel: MethodChannel

    init {
        if (engine == null) {
            engine = FlutterEngine(a.applicationContext).also { it.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault()) }
        }
        val e = engine!!
        channel = MethodChannel(e.dartExecutor.binaryMessenger, "launcher")
        channel.setMethodCallHandler { c, r ->
            when (c.method) {
                "state" -> r.success(data.state())
                "apps" -> r.success(data.apps())
                "pref" -> r.success(data.pref(c.argument<String>("k") ?: "", c.argument<String>("d") ?: ""))
                "dark" -> r.success(Theme.fromPrefs(a.webPrefs()).dark)
                "launch" -> { data.launch(c.arguments as String); r.success(null) }
                "appMenu" -> { data.appMenu(c.arguments as String); r.success(null) }
                "openSettings" -> { data.openSettings(); r.success(null) }
                "media" -> { data.media(c.arguments as String); r.success(null) }
                else -> r.notImplemented()
            }
        }
        fv.attachToFlutterEngine(e)
        e.lifecycleChannel.appIsResumed()
        data.setListener { channel.invokeMethod("appsChanged", null) }
        channel.invokeMethod("prefsChanged", null)   // al volver a esta interfaz, releer todo
    }

    override fun view(): View = fv
    override fun reload() = channel.invokeMethod("prefsChanged", null)
    override fun back() = channel.invokeMethod("back", null)
    override fun resume() { engine?.lifecycleChannel?.appIsResumed() }
    override fun pause() { engine?.lifecycleChannel?.appIsInactive() }
    override fun destroy() {
        data.setListener(null)
        channel.setMethodCallHandler(null)
        fv.detachFromFlutterEngine()
        engine?.lifecycleChannel?.appIsPaused()
    }
}
