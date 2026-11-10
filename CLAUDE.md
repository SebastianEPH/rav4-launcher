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

Código Java en el paquete `pe.radio.rav4`:
- `HomeActivity.java` — toda la lógica: layouts de los 5 temas, dock, cajón de apps, centro de
  control, ajustes, GPS/viaje, volumen NWD, batería, desinstalar, gestos.
- `Theme.java` — paletas de color por tema y modo (claro/oscuro/auto), íconos squircle, fondos.
- `Views.java` — vistas dibujadas a mano: velocímetro (Gauge), gráfico (Spark), detector de
  gestos (GestureFrame), fondos de pantalla (Wallpaper).
- `WebHome.java` — la interfaz web (ver abajo): WebView + puente JS `Radio`.
- `CanBridge.java` — lee el bus CAN por el servicio AIDL de `com.nwd.can.setting` (ver FINDINGS.md → Bus CAN)
  y graba todo en `RAV4Launcher/can_<fecha>.txt` en el USB para analizarlo en el Mac.
- `SettingsActivity.java` — ventana "Ajustes Launcher" (tema, apariencia, interfaz, permisos, actualización).
- `Updater.java` — "Buscar actualización" (⚙ Ajustes → Sistema): lee el último Release de GitHub,
  compara su tag con `versionName`, descarga el APK y lo instala con `PackageInstaller`. La primera vez
  Android pide permitir "instalar apps de esta fuente". El CI pone `versionCode=100+run_number` y
  `versionName` = tag; el build local queda en `1.3-local` (versionCode 100).

### Dos interfaces: Nativa y Web
⚙ Ajustes → **Interfaz: Nativa / Web (animada)** (pref `engine`). La nativa son los 5 temas en Java.
La **web** está en `assets/web/` (`index.html`, `app.css`, `app.js`) y se carga en un WebView:
- Los mismos 5 temas rehechos con efectos: aurora animada (Glass/Inicio), cielo que cambia con la
  hora con sol/luna/nubes/estrellas (Dusk), carretera neón cuya velocidad sigue a la del auto +
  velocímetro SVG con aguja (Cockpit), CRT + log en vivo + comando tecleándose (Terminal).
- **Sin panel lateral**: toda la configuración vive en la ventana aparte **"Ajustes Launcher"** (`SettingsActivity`,
  **100% nativa, sin WebView**), con ícono propio en la lista de apps y botón ⚙ en el dock. Al volver, el launcher
  compara `stamp` y solo se rearma si algo cambió. Gestos (nativa y web): **← abre las apps**; en Inicio ←/→ cambian
  de página. **No hay gesto hacia abajo ni centro de control** (se quitó del nativo para igualar al HTML).
- 6 temas: Terminal, Glass, Dusk, Cockpit, Inicio y **Auto · CAN** (vidrio líquido estilo iOS con íconos
  propios: puertas animadas, tacómetro, señales y registro en vivo del bus CAN para probar qué llega).
- **Todo configurable** desde el Panel: modo Oscuro/Claro/Automático con **horas configurables**
  (`darkFrom`/`darkTo`), color de acento, reloj 12 h (por defecto) / 24 h, efectos y **opciones propias de cada tema**
  (`THEMES.x.opts`, guardadas como `t.<tema>.<clave>` y expuestas en CSS como `data-o-<clave>`).
- Efectos Altos / Suaves / Ninguno; si la radio no llega a ~35 fps se bajan solos una vez.
- Puente `window.Radio` (WebHome.Bridge): `state()` JSON (GPS, viaje, voltaje, volumen, brillo,
  wifi/bt), `apps()`, `launch`, `appMenu` (diálogo nativo), `setVolume`, `setBrightness`, `media`,
  `open`, `toggle`, `pref/setPref` (SharedPreferences con prefijo `web.`), `checkUpdate`, `useNative`.
  El WebView solo carga `file:///android_asset/` para que ninguna web externa toque el puente.
- **Rendimiento (la radio es lenta, ya pasó que los toques tardaban 5–15 s)**: las llamadas JS→Android son
  síncronas y bloquean la página, así que el puente **solo devuelve copias en caché** (`WebHome` arma `state`/`can`
  en un hilo aparte cada 500 ms). Nada de leer sensores/archivos en cada consulta (CPU cada 15 s, perfil CAN cada 10 s).
  En CSS: sin `filter: blur` en fondos (la aurora usa degradados radiales) y `backdrop-filter` solo con Efectos "Altos";
  por defecto los efectos son "Suaves".
- **Arranque rápido**: el tema de la actividad (`@style/Rav4`) tiene como fondo de ventana `res/drawable/splash.xml`
  (fondo oscuro + foto del RAV4): Android lo pinta antes de que corra nuestro código. Luego `WebHome` muestra un
  reloj nativo y **recién en el cuadro siguiente crea el WebView** (crearlo bloquea varios segundos). Cada arranque
  se registra paso a paso en `RAV4Launcher/arranque.txt` (memoria interna y USB) para medir en la radio real.
- `WebHome` quita el reloj con fundido cuando
  la página llama `Radio.ready()` (primer cuadro). La lista de apps se arma en segundo plano (solo nombres) y se
  rehace solo al instalar/quitar apps (broadcast de paquetes) o al fijar/ocultar, no en cada vuelta a Inicio.
  Los íconos se sirven por `https://rav4.local/icon/<clave>?v=<lastUpdateTime>` (shouldInterceptRequest, caché en
  `cache/icons`), en paralelo. El Panel se arma recién al abrirlo (barra de carga) y los permisos al final.
  El servicio CAN se conecta solo cuando la página lo pide. El Panel muestra los tiempos de arranque medidos.
- **Arranque sin animaciones**: `app.js` fuerza `effFx()="off"` mientras `booting` es true (las animaciones son lo
  que pone lenta la radio al iniciar). Se reactiva el `fx` guardado a los 2.5 s o al primer toque (lo que ocurra
  antes), con `applyLook()` + `initParticles()`.
- **Congelar al tocar**: `pointerdown` añade `body.freeze` (pausa las animaciones de fondo vía CSS y salta el dibujo
  de partículas); `pointerup`/`pointercancel` lo quitan 650 ms después **solo si no hay una hoja abierta**. Mientras el cajón de
  apps (o cualquier `.sheet`) está abierto, las animaciones de fondo quedan detenidas; se reanudan al cerrarlo.
- **Permisos**: Android no permite "permiso total". Ajustes Launcher → Permisos
  pide **todos** los útiles (lista `SettingsActivity.PERMS` con su "para qué"): ubicación, almacenamiento, micrófono,
  cámara, teléfono/contactos, ajustes del sistema, instalar apps, notificaciones (`MediaListener`), mostrar encima,
  acceso de uso, sin optimización de batería, no molestar y launcher predeterminado. Sin accesibilidad ni admin
  del dispositivo (control total, sin uso aquí). "Conceder todos" pide cada uno una vez por recorrido,
  abre la pantalla de cada uno que falte y continúa solo al volver (`SettingsActivity.startPermWizard`).
- Íconos de apps **siempre solos**, el ícono real de cada app sin placa ni contorno, en web y en nativo
  (`HomeActivity.icon()` devuelve el drawable tal cual). Dock del Cockpit **centrado verticalmente**.
- **Compatibilidad**: el WebView de la radio puede ser Chrome ~74 → nada de `?.`, `??`, `:is()`,
  `inset`, `clamp()/min()`, `aspect-ratio`, ni `gap` en flex (en grid sí).
- Probar en el Mac: abrir `assets/web/index.html` en Chrome (usa un simulador). Parámetros:
  `?theme=cockpit&kmh=90&panel=1&drawer=1&fx=low&mode=light&door=0&shot=1` (`shot` congela animaciones para capturas).
  Capturas del README: `docs/img/` (Chrome headless, ver README).

### Temas (⚙ Ajustes del launcher → Tema, o centro de control → Tema)
1. **Terminal** — estilo consola: monoespaciado, telemetría, gráficos, datos técnicos.
2. **Glass** — estilo Mac/iPhone: barra de menú arriba (hora y estado), **Dock flotante tipo macOS** a la izquierda
   (`Views.MacDock`, lupa al pasar el dedo, separador + Launchpad ▦), fondo propio de manchas de color (`Views.IosWall`)
   y widgets de vidrio con ícono de color en el título.
3. **Dusk** — fondo degradado, hora grande, tarjetas abajo (estilo Android Auto).
4. **Cockpit** — rehecho para quedar igual al de la interfaz web: carretera neón animada de fondo (`Views.CockpitRoad`),
   velocímetro con aguja, estela de velocidad y arco con degradado (`Views.CockpitGauge`), brújula (`Views.Compass`),
   y tarjetas Viaje / Batería (`Views.Bar`) / GPS / Música. Reemplazó al Cockpit viejo de tarjetas.
   Los 4 espacios de tarjetas se eligen en **Ajustes Launcher → Tema actual** (`cockpit.slot1..4`, `HomeActivity.cockSlot`):
   Viaje, Batería, GPS, Música, Rumbo + calle (Geocoder), Altitud y pendiente (`Views.Line`), 0–100 y fuerza G
   (`accel.best`), Accesos (ZLINK/CarPlay/mapas: solo los abre, no se pueden incrustar en una tarjeta), Controles, Reloj grande, Conectividad (red WiFi + señal, Bluetooth + equipo conectado; tocar abre los ajustes).
   Las mismas tarjetas se eligen en todos los temas (`<tema>.slotN`, `HomeActivity.addSlot`).
   Animaciones: barrido de aguja también en Glass/Dusk, números que ruedan (`anim.roll`), barra de voltaje viva,
   entrada de tarjetas (`anim.cards`, tarjetas marcadas con tag `R_CARD`).
5. **Inicio** — cuadrícula de íconos grandes con páginas (estilo pantalla de inicio iOS/CarPlay).

6. **Sonido** (solo nativo; en web cae a Cockpit) — visualizador circular (`Views.Spectrum`) con el audio real vía
   `android.media.audiofx.Visualizer(0)` (necesita RECORD_AUDIO + MODIFY_AUDIO_SETTINGS); si la radio no entrega audio
   (la radio FM/MCU no pasa por Android) se anima según `isMusicActive()` y el volumen. Volumen NWD grande con deslizador,
   "Sonando ahora" por MediaSessionManager (requiere acceso a notificaciones, `MediaListener`), espectro de barras
   (espacio `sound.slot1`).
   **Espectro global** (Tema actual → Espectro): estilos `spec.style` (Barras, Espejo, Onda, LED, Círculo, Anillos, Sol),
   colores `spec.colors` (Neón, Fuego, Océano, Matrix, Atardecer, Hielo, Oro, Arcoíris), sensibilidad `spec.gain`,
   **fondo** detrás de cualquier tema `spec.bg` + `spec.bgStyle` + `spec.bgAlpha`, y tarjeta "Espectro de audio" en
   todos los temas. Todos los `Views.Spectrum` salen de `HomeActivity.newSpec()` y reciben el mismo audio (`specs`).
- **Volumen sin la barra del sistema**: `nwdSetVolume` usa `ACTION_HIGHTEMP_TRIGGER` (silencioso, 1–40) y si no cambia
  en 700 ms repite con la vía normal. Opción en Ajustes → General → Sonido (`volume.silent`). Ver FINDINGS.md.

Fondo configurable (solo Cockpit/Inicio): Ondas, Noche, Atardecer, Mi RAV4 (foto del auto).

### Gestos (interfaz nativa)
- Deslizar ← : abre el cajón de apps (en tema Inicio ←/→ cambian de página).
- **No hay centro de control** (gesto hacia abajo eliminado, igual que en el HTML). Config en "Ajustes Launcher".

### Reglas de UX acordadas con el usuario
- Brillo: **nunca por debajo del 18 %** (`MIN_BRIGHTNESS=46/255`). Por debajo la pantalla se apaga.
  El launcher lo vigila cada segundo y lo corrige lo haya bajado quien lo haya bajado.
- **Brillo automático por tramos** (Ajustes Launcher → Brillo automático): pref `autobright` on/off y
  `autobright.bands` = "hora:porcentaje,.." (ej. "7:90,19:55,22:25"). El tramo vigente es el de mayor hora ≤ ahora
  (envuelve medianoche). Forma `autobright.shape`: **Arco suave** (`curve`, interpolación coseno entre puntos, `Views.brightAtF`) o Escalones (`steps`). `HomeActivity.applyAutoBrightness` lo aplica al cambiar de tramo (un ajuste manual dentro
  del mismo tramo se respeta); nunca baja del 18 %. Editor **táctil grande** (`SettingsActivity.BandGraph`, 440 dp):
  arrastrar un punto (↕ brillo, ↔ hora), tocar un espacio vacío agrega un tramo, mantener presionado lo borra.
- **Ajustes Launcher en pestañas**: General · Brillo · Apps · Permisos · Sistema (tocar o deslizar ←/→). Corre en su propia
  tarea (`taskAffinity` aparte): sin eso, abrirlo desde el cajón solo traía el launcher al frente y "no abría".
- Brillo: deslizador de brillo actual, perfiles rápidos (Día brillante / Equilibrado / Nocturno), "Aplicar ahora" y
  transición suave entre tramos (`autobright.fade`, ~30 s). Cajón: opacidad del fondo 1–100 % (`drawer.alpha`).
  Cambiar ícono: botón "Cambiar ícono" en cada app (pref `icon.<clave>` = PNG 192 px en `files/icons`).
- **Modo claro en todo lo nativo**: el Cockpit toma sus colores del modo (`Views.fg()`, `HomeActivity.cTxt/cDim/cCard`)
  y Ajustes Launcher arma su paleta con `Theme.fromPrefs` (`SettingsActivity.palette()`); si cambia el modo se recrea.
  Nada de colores oscuros fijos.
- **Configuración global**: tema, modo y acento usan las mismas claves (`theme`, `mode`, `accent`) en la interfaz
  nativa y en la web (`WebHome.globalKey`); las horas del modo automático (`web.darkFrom/To`) valen para ambas.
  Auto · CAN en nativo se muestra como Cockpit. Nada de configuraciones separadas por interfaz.
- El cajón de apps: íconos **grandes y reales (sin placa)**, **páginas horizontales de 6×3** con puntos (nada de scroll
  vertical). Con el cajón abierto: ← página siguiente, → anterior; en la primera página → lo cierra.
- Ocultar/mostrar apps desde **Ajustes Launcher → Apps** (lista con botón Visible/Oculta; escribe el set `hidden`).
- Nada de círculos en los íconos (fue un rechazo explícito).
- **REGLA: los íconos de apps NUNCA llevan fondo, placa, círculo, squircle ni contorno**, en ningún tema (nativo ni web),
  tampoco en temas nuevos. Siempre el ícono real tal cual (`HomeActivity.appIcon(r)` / `<img>` sin fondo en `.app .ic`).
  Nada de `Theme.squircle`/`Theme.circle` ni `iconBg` para íconos de apps. Las etiquetas tampoco llevan fondo (usar sombra).
- Controles y apps empiezan por la **izquierda**, lado del conductor.
- Hora en **12 h con AM/PM por defecto** (pref compartida `web.clock` = "12"/"24"; nativa y web). Los registros técnicos (CAN, log) usan 24 h.
- No copiar íconos de marcas (Apple, Google, Volkswagen): se usan los íconos reales de cada app.

## Cómo compilar e instalar
```bash
./build.sh                      # genera RAV4Launcher.apk
# copiar el APK a un USB, instalarlo en la radio, elegirlo como launcher por defecto
```
### Releases automáticos (CI)
Cada push a `main` (salvo cambios solo en `.md`, `site/` o `docs/`) dispara `.github/workflows/release.yml`: compila,
firma con la keystore guardada en el secreto `KEYSTORE_B64` (la misma del Mac, para que la radio
instale encima sin desinstalar) y publica un Release `v1.3.<n>` con el APK. No hace falta crear tags.
Las notas del Release las arma `.github/release-notes.sh` desde git: commits desde la versión anterior,
archivos agregados/eliminados, "Qué trae el launcher" (lista fija en el script: **actualizarla al agregar
funciones**) y "Todavía no incluye" (sale de la sección Pendientes de este archivo).
El repo es **público**: nunca subir claves, tokens ni la `debug.keystore`.

## Reglas de git
- **Commitear y hacer push por cada cambio**, con mensaje en español.
- **No agregar a Claude como coautor**: nada de `Co-Authored-By` ni firmas de Claude en commits o PRs.

## Verificar la UI nativa en emulador (antes de enviar)
Hay emulador local (Android 29, misma API de la radio). Para **ver** un cambio nativo sin la radio:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17; SDK=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT=$SDK; export PATH=$JAVA_HOME/bin:$SDK/platform-tools:$PATH
# AVD "rav4" ya creado a 1280x160dpi horizontal. Arrancar sin ventana:
"$SDK/emulator/emulator" -avd rav4 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device; adb root
# fijar tema/interfaz escribiendo las prefs y abrir:
adb push rav4.xml /data/data/pe.radio.rav4/shared_prefs/rav4.xml   # <string name="theme">cockpit</string>, engine=native
adb install -r -g RAV4Launcher.apk; adb shell am start -n pe.radio.rav4/.HomeActivity
adb shell am broadcast -a com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE --ei extra_voltage_value 138   # simular 13.8 V
adb exec-out screencap -p > /tmp/x.png
```
**Revisar la captura antes de dar algo por bueno.** El emulador no reemplaza la prueba final en la radio
(firmware NWD, CAN real), pero sí evita enviar layouts chuecos.

No hay ADB a la radio en producción, así que **la versión final se prueba instalándola en la radio real**.
Nunca afirmar que algo "funciona" en la radio sin que el usuario lo haya confirmado.

## App hermana: Radio Diagnóstico
En `../radio-diagnostico/`. App para exportar al USB el informe de la radio + las APK del sistema
(o todas). Carpeta de salida: `RadioDiagnostico/` (informe .txt + carpeta `apps/`). Borra el
diagnóstico anterior antes de guardar. Se usa para seguir analizando apps y encontrar más funciones.

## Pendientes / ideas
- Confirmar en la radio qué datos CAN llegan de verdad (tema Auto · CAN) y usar RPM/puertas en los demás temas.
- Más controles de audio (navegación, BT, GPS tienen volúmenes separados).
- Abrir CarPlay/Android Auto directo (ZLINK expone CarPlayActivity y HiCarActivity como LAUNCHER).
- Información de lugares (nombre de la calle/distrito) a partir del GPS.
- Dashboard completo del auto: consumo, temperatura, estado de sistemas (basado en CAN).
