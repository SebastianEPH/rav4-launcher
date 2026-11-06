# RAV4 Launcher — contexto del proyecto

Launcher (pantalla de inicio) personalizado para una **autorradio Android china**, montada en un
Toyota RAV4 2013 (4ª gen, XA40), color gris. El dueño es desarrollador backend / DevSecOps,
sin experiencia en UX/UI, por lo que la interfaz debe quedar clara y pulida.

## Idioma
- Toda comunicación con el usuario y los textos de la app van en **español**.
- Código y nombres de variables en inglés o español indistinto; comentarios en español.

## La radio (muy importante — no es la que dice ser)

| Dato | Lo que muestra | Lo real |
|------|----------------|---------|
| Android | 14 | **Android 10 (API 29)** — la versión 14 es un maquillaje del fabricante |
| RAM/ROM | 8 GB / 128 GB | Probablemente exagerado (valor redondo falseado) |
| SoC | — | **Unisoc UIS8581A**, 8 núcleos, placa `uis8581a2h10` |
| Fabricante | — | **NWD**, plataforma **K4811** (`ro.nwd.platform.name`) |
| Pantalla | — | **1280×800**, horizontal, densidad 160 dpi (~14") |
| Marca build | — | `SPRD` / `NWD`, `ro.product.nwd.customer=K4811_NWD_S212851` |

Trae apps de benchmark (CPU-Z, AnTuTu, AIDA64…) preparadas para mostrar datos falsos.

### Hechos clave de hardware/software
- **GPS**: existe y está activo (`gps` provider enabled). La velocidad se saca por GPS.
- **WiFi y Bluetooth**: sí tiene, aunque el dueño creía que no.
- **USB en modo dispositivo (ADB por cable)**: **bloqueado por firmware** (`ro.boot.enable_usb1=0`,
  `adbd` detenido, `ro.secure=1`). El puerto USB queda fijo en modo host. Por eso no se puede
  conectar la radio a una PC por ADB. Cambiar "USB mode" en Opciones de desarrollador NO lo activa.
- **Launcher por defecto**: `com.android.launcher`, fijado por `persist.nwd.launcher.default`.
  Puede volver al original al reiniciar; el usuario ya lo dejó por defecto manualmente.
- **CAN bus**: la radio recibe datos del auto. App `com.nwd.can.setting` ("Datos del automóvil").
- **Proyección de teléfono**: ZLINK (`com.zjinnova.zlink`) con CarPlay + Android Auto + mirroring.
- El usuario **tiene el ZIP del firmware y sabe restaurarlo**, así que acepta operaciones de riesgo.

## Hallazgos del análisis de las APK del sistema (ver FINDINGS.md)

Las apps del sistema se exportan con la app "Radio Diagnóstico" (ver `../radio-diagnostico/`)
y se descompilan con `jadx`. Hallazgos que ya se usan en el launcher:

1. **Volumen real (resuelto):** la radio NO usa el volumen estándar de Android. El volumen vive en
   `Settings.System` bajo `mcu_system_volume` (escala **0–40**, máx en `mcu_max_volume`). Se cambia
   con el broadcast `com.nwd.action.ACTION_REQUEST_SET_SYSTEM_VOLUME` + extra int `extra_volume`.
   Descubierto en `com.nwd.setting.service` (SettingManager.java).
2. **Voltaje de batería (en vivo):** se pide con broadcast `com.nwd.action.ACTION_QUERY_CAN_BATTERY_VOLTAGE`
   y la radio responde con `com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE` + extra int `extra_voltage_value`
   (voltaje × 10, ej. 126 = 12.6 V). Visto en systemui BatteryVoltageController + SettingManager.
3. **Desinstalar apps como root:** `com.nwd.kernel` (KernelService) escucha
   `com.nwd.action.ACTION_SILENT_UNINSTALL_APK` + extra string `pack_name`. Escribe el comando en
   `/cache/nwdcommand.cmd` y un demonio del firmware lo ejecuta COMO ROOT (`pm uninstall <pkg>`).
   Solo registrado en Android < 11 → funciona en nuestra radio (Android 10). Responde con
   `...ACTION_SILENT_UNINSTALL_APK_RESULT` + extra `result` ("1"=ok, "2"=fallo).
   El launcher lo usa en el menú de cada app, con lista de apps protegidas.

## Arquitectura del launcher

Proyecto Android nativo **sin Gradle** — se compila con `./build.sh` (aapt2 + javac + d8 + apksigner).
SDK en `/opt/homebrew/share/android-commandlinetools`, Java 17 de Homebrew. minSdk 21, targetSdk 28.

Solo 3 archivos de código, paquete `pe.radio.rav4`:
- `HomeActivity.java` — toda la lógica: layouts de los 5 temas, dock, cajón de apps, centro de
  control, ajustes, GPS/viaje, volumen NWD, batería, desinstalar, gestos.
- `Theme.java` — paletas de color por tema y modo (claro/oscuro/auto), íconos squircle, fondos.
- `Views.java` — vistas dibujadas a mano: velocímetro (Gauge), gráfico (Spark), detector de
  gestos (GestureFrame), fondos de pantalla (Wallpaper).

### Temas (⚙ Ajustes del launcher → Tema, o centro de control → Tema)
1. **Terminal** — estilo consola: monoespaciado, telemetría, gráficos, datos técnicos.
2. **Glass** — tarjetas translúcidas y barra lateral (estilo iOS/CarPlay).
3. **Dusk** — fondo degradado, hora grande, tarjetas abajo (estilo Android Auto).
4. **Cockpit** — panel de tarjetas: velocidad, viaje, auto, música (estilo CarPlay dashboard).
5. **Inicio** — cuadrícula de íconos grandes con páginas (estilo pantalla de inicio iOS/CarPlay).

Fondo configurable (solo Cockpit/Inicio): Ondas, Noche, Atardecer, Mi RAV4 (foto del auto).

### Gestos
- Deslizar → / ← : abre/cierra el cajón de apps (en tema Inicio cambia de página).
- Deslizar ↓ : abre el centro de control.
- Deslizar ↑ : lo cierra.

### Reglas de UX acordadas con el usuario
- Brillo: **nunca por debajo del 18 %** (`MIN_BRIGHTNESS=46/255`). Por debajo la pantalla se apaga.
  El launcher lo vigila cada segundo y lo corrige lo haya bajado quien lo haya bajado.
- El cajón de apps debe verse pulido (íconos grandes squircle, nombres, sin cajas grises).
- Nada de círculos en los íconos (fue un rechazo explícito).
- Controles y apps empiezan por la **izquierda**, lado del conductor.
- No copiar íconos de marcas (Apple, Google, Volkswagen): se usan los íconos reales de cada app.

## Cómo compilar e instalar
```bash
./build.sh                      # genera RAV4Launcher.apk
# copiar el APK a un USB, instalarlo en la radio, elegirlo como launcher por defecto
```
No hay emulador ni ADB a la radio, así que **cada versión se prueba instalándola en la radio real**.
Nunca afirmar que algo "funciona" sin que el usuario lo haya confirmado en la radio.

## App hermana: Radio Diagnóstico
En `../radio-diagnostico/`. App para exportar al USB el informe de la radio + las APK del sistema
(o todas). Carpeta de salida: `RadioDiagnostico/` (informe .txt + carpeta `apps/`). Borra el
diagnóstico anterior antes de guardar. Se usa para seguir analizando apps y encontrar más funciones.

## Pendientes / ideas
- Velocidad del auto por CAN (buscar en `com.nwd.can.setting`) para no depender solo del GPS.
- Más controles de audio (navegación, BT, GPS tienen volúmenes separados).
- Estado de puertas por CAN (hay acciones relacionadas).
- Abrir CarPlay/Android Auto directo (ZLINK expone CarPlayActivity y HiCarActivity como LAUNCHER).
