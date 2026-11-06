# Hallazgos del análisis de la radio NWD K4811

Todo esto sale de descompilar con `jadx` las APK del sistema exportadas con la app
"Radio Diagnóstico". Las APK están en `../radio-diagnostico/RadioDiagnostico/apps/`.

## Resumen de identidad de la radio
- Fabricante **NWD**, plataforma **K4811**, SoC **Unisoc UIS8581A**, placa `uis8581a2h10`.
- **Android 10 (API 29)** real, disfrazado de Android 14.
- `sharedUserId` del kernel: `com.nwd.kernel.setting` (apps de sistema con privilegios).

## 1. Volumen (IMPLEMENTADO en el launcher)
- Dónde: `com.nwd.setting.service` → `SettingManager.java`.
- El volumen vive en `Settings.System` clave **`mcu_system_volume`**, rango **0–`mcu_max_volume`**
  (normalmente 40).
- Cambiarlo: broadcast **`com.nwd.action.ACTION_REQUEST_SET_SYSTEM_VOLUME`**, extra int
  **`extra_volume`** con el valor 0–40.
- El AudioManager estándar de Android NO mueve el volumen real: por eso antes no funcionaba.
- Volúmenes separados que existen (futuros controles): `mcu_bt_volume`, `mcu_gps_volume`,
  `nwd_navi_vol`, `nwd_media_vol`, `nwd_media_mix_navi_vol`, y `ACTION_CHANGE_GAIN_VOL`.

## 2. Voltaje de batería del auto (IMPLEMENTADO)
- Dónde: `com.android.systemui` → `BatteryVoltageController.java` y `SettingManager.java`.
- Pedir: broadcast **`com.nwd.action.ACTION_QUERY_CAN_BATTERY_VOLTAGE`**.
- Respuesta: broadcast **`com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE`**, extra int
  **`extra_voltage_value`** = voltaje × 10 (126 → 12.6 V).
- Llega por CAN, así que solo funciona si la radio tiene la caja CAN conectada.

## 3. Desinstalar apps como root (IMPLEMENTADO con protecciones)
- Dónde: `com.nwd.kernel` → `MCUKeyReceiver.java` + `KernelService.java` + `KernelUtils.java`.
- Enviar: broadcast **`com.nwd.action.ACTION_SILENT_UNINSTALL_APK`**, extra string
  **`pack_name`** = paquete.
- Qué hace: `KernelUtils.setNwdScriptCmd()` escribe `pm uninstall <pkg>` en `/cache/nwdcommand.cmd`
  y setea la propiedad `sys.nwd.cmd.start=true`. Un **demonio del firmware (root)** lee el archivo
  y ejecuta el comando. => desinstalación con privilegios de root.
- Resultado: broadcast **`...ACTION_SILENT_UNINSTALL_APK_RESULT`**, extra `result` ("1"=ok, "2"=fallo).
- **Solo registrado en Android < 11** (`Build.VERSION.SDK_INT < 30`). Nuestra radio es API 29 → OK.
- Hermano para instalar: `ACTION_SILENT_INSTALL_APK` (extra `file_path`).
- `ACTION_STOP_APP` (force-stop, extra `extra_package_name`) solo se registra en API ≥ 30 → NO
  disponible en nuestra radio.

### Apps PROTEGIDAS (el launcher no deja quitarlas)
Quitarlas puede dejar la radio sin arrancar / sin sonido / sin cámara de reversa:
`com.nwd.kernel`, `com.nwd.setting.service`, `com.nwd.can.setting`, `com.nwd.can.help`,
`com.android.systemui`, `com.nwd.audioset`, `com.nwd.media.scanner`, `com.nwd.install.ex.service`,
`com.spreadtrum.sgps`, `com.unisoc.reversecamera`, `com.nwd.mycar`, `android`.

## 4. CarPlay / Android Auto (ZLINK)
- Paquete `com.zjinnova.zlink`.
- Expone como LAUNCHER: `...features.launcher.CarPlayActivity` y `...features.launcher.HiCarActivity`
  → el launcher puede abrir CarPlay o HiCar directo.
- NO tiene mapas/música dentro: el teléfono manda todo como video. Imposible "sacar" Maps/Spotify
  de ZLINK.

## 5. Otras acciones NWD vistas (sin usar aún)
- `com.nwd.ACTION_SET_USB_MODE` / `ACTION_QUERY_USB_MODE` — cambiar modo USB (host/device) desde
  software. A investigar: podría ser la vía para activar ADB sin tocar Opciones de desarrollador.
- `ACTION_SET_AIRPLANE_MODE`, `ACTION_MEMORY_CLEAN`, `ACTION_FORMAT_STORAGE`.
- `ACTION_INJECT_KEY` (NwdManager.injectKeyEvent) — inyectar teclas del MCU.
- `persist.nwd.voltage.offset` — offset del voltaje mostrado.
- `nwd_statusbar_voltage_display` — flag para mostrar el voltaje en la barra original.

## Pendiente de analizar
- `com.nwd.can.setting` — velocidad del auto, RPM, combustible, puertas por CAN.
- `com.nwd.factory.setting` — ajustes de fábrica (contraseñas, config USB, hardware).
- `com.nwd.toolallinone.app` — herramientas varias.
