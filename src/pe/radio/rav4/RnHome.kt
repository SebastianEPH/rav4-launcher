package pe.radio.rav4

import android.os.Bundle
import android.view.View
import com.facebook.react.PackageList
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.interfaces.fabric.ReactSurface
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.uimanager.ViewManager

/**
 * Interfaz React Native (Hermes + Reanimated + SVG), JS en rn/App.tsx — tema Cockpit.
 * El motor de React Native se crea una sola vez por proceso y se reutiliza al volver a esta interfaz.
 */
class RnHome(private val a: HomeActivity, private val data: LauncherData) : ModernHome {
    companion object {
        private var host: ReactHost? = null
        @JvmStatic var current: RnHome? = null
    }

    private val surface: ReactSurface

    init {
        current = this
        if (host == null) {
            loadReactNative(a.applicationContext)
            host = getDefaultReactHost(a.applicationContext,
                PackageList(a.application).packages.apply { add(LauncherPackage()) }, useDevSupport = false)
        }
        surface = host!!.createSurface(a, "OrbitaRN", Bundle())
        surface.start()
        host!!.onHostResume(a)
        data.setListener { emit("appsChanged") }
    }

    fun emit(event: String) {
        try {
            host?.currentReactContext?.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)?.emit(event, null)
        } catch (_: Exception) { }
    }

    fun data() = data
    fun activity() = a

    override fun view(): View = surface.view!!
    override fun reload() = emit("prefsChanged")
    override fun back() = emit("back")
    override fun resume() { host?.onHostResume(a) }
    override fun pause() { host?.onHostPause(a) }
    override fun destroy() {
        data.setListener(null)
        surface.stop()
        if (current === this) current = null
    }
}

/** Puente JS → launcher. Lecturas síncronas pero instantáneas: devuelven la copia en caché de LauncherData. */
class LauncherModule(ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
    override fun getName() = "Launcher"
    private fun d() = RnHome.current?.data()

    @ReactMethod(isBlockingSynchronousMethod = true) fun state(): String = d()?.state() ?: "{}"
    @ReactMethod(isBlockingSynchronousMethod = true) fun apps(): String = d()?.apps() ?: "[]"
    @ReactMethod(isBlockingSynchronousMethod = true) fun pref(key: String, def: String): String = d()?.pref(key, def) ?: def
    @ReactMethod(isBlockingSynchronousMethod = true) fun dark(): Boolean = RnHome.current?.let { Theme.fromPrefs(it.activity().webPrefs()).dark } ?: true
    @ReactMethod fun launch(key: String) { d()?.launch(key) }
    @ReactMethod fun appMenu(key: String) { d()?.appMenu(key) }
    @ReactMethod fun openSettings() { d()?.openSettings() }
    @ReactMethod fun media(what: String) { d()?.media(what) }
    @ReactMethod fun addListener(@Suppress("UNUSED_PARAMETER") e: String) { }
    @ReactMethod fun removeListeners(@Suppress("UNUSED_PARAMETER") n: Double) { }
}

class LauncherPackage : ReactPackage {
    override fun createNativeModules(ctx: ReactApplicationContext): List<NativeModule> = listOf(LauncherModule(ctx))
    override fun createViewManagers(ctx: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
