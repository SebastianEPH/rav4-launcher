package com.orbitarn

import android.os.Bundle
import android.view.MotionEvent
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import pe.radio.bench.BenchProbe

class MainActivity : ReactActivity() {
  override fun getMainComponentName(): String = "OrbitaRN"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun onCreate(savedInstanceState: Bundle?) {
    BenchProbe.get().launchedAt(intent)
    super.onCreate(null)
    // pantalla completa, igual que el launcher
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != 0)
      requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
  }

  // hora real del toque (la del sistema), para medir la latencia completa: Android → JS → pantalla
  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    if (ev.actionMasked == MotionEvent.ACTION_DOWN) BenchProbe.get().touchDown(ev.eventTime)
    return super.dispatchTouchEvent(ev)
  }
}
