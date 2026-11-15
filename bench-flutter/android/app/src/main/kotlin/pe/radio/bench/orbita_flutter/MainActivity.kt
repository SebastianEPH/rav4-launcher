package pe.radio.bench.orbita_flutter

import android.os.Bundle
import android.view.MotionEvent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import pe.radio.bench.BenchProbe

class MainActivity : FlutterActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    BenchProbe.get().launchedAt(intent)
    super.onCreate(savedInstanceState)
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != 0)
      requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
  }

  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    if (ev.actionMasked == MotionEvent.ACTION_DOWN) BenchProbe.get().touchDown(ev.eventTime)
    return super.dispatchTouchEvent(ev)
  }

  override fun configureFlutterEngine(engine: FlutterEngine) {
    super.configureFlutterEngine(engine)
    MethodChannel(engine.dartExecutor.binaryMessenger, "bench").setMethodCallHandler { c, r ->
      val p = BenchProbe.get()
      when (c.method) {
        "firstFrame" -> { p.firstFrame(); r.success(null) }
        "touchShown" -> { p.touchShown(); r.success(null) }
        "start" -> { p.start(false); r.success(null) }      // cuadros: los informa Flutter (FrameTiming del hilo de dibujo)
        "frames" -> { (c.arguments as List<*>).forEach { p.frame((it as Number).toDouble()) }; r.success(null) }
        "finish" -> r.success(p.finish(this, "flutter", "nota=fps = FrameTiming de Flutter (cuadros realmente dibujados por su motor Impeller/Skia)"))
        else -> r.notImplemented()
      }
    }
  }
}
