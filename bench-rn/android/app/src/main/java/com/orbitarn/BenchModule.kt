package com.orbitarn

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.*
import com.facebook.react.uimanager.ViewManager
import pe.radio.bench.BenchProbe

/** Puente JS → BenchProbe (el mismo medidor que usan Nativo, Web y Flutter). */
class BenchModule(ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
  override fun getName() = "Bench"
  @ReactMethod fun firstFrame() = BenchProbe.get().firstFrame()
  @ReactMethod fun touchShown() = BenchProbe.get().touchShown()
  // los cuadros que ve el usuario los dibuja el hilo de la interfaz de Android (Choreographer)
  @ReactMethod fun start() { UiThreadUtil.runOnUiThread { BenchProbe.get().start(true) } }
  @ReactMethod fun finish(jsFps: Double, jsSlow: Int, p: Promise) {
    val a = reactApplicationContext.currentActivity ?: return p.resolve("Sin actividad")
    p.resolve(BenchProbe.get().finish(a, "reactnative",
        "js_fps=%.1f\njs_cuadros_lentos=%d\nnota=fps = hilo de la interfaz (aguja y carretera con Reanimated); js_fps = hilo de JavaScript (números y tarjetas)".format(java.util.Locale.US, jsFps, jsSlow)))
  }
}

class BenchPackage : ReactPackage {
  override fun createNativeModules(ctx: ReactApplicationContext): List<NativeModule> = listOf(BenchModule(ctx))
  override fun createViewManagers(ctx: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
