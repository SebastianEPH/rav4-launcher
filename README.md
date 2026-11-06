# RAV4 Launcher

Launcher Android personalizado para una autorradio **NWD K4811** (Unisoc UIS8581A, Android 10)
montada en un Toyota RAV4 2013. Pantalla 1280×800 horizontal.

## Compilar
```bash
./build.sh          # genera RAV4Launcher.apk (sin Gradle: aapt2 + javac + d8 + apksigner)
```
Requiere Java 17 y el Android command-line SDK (ver rutas en build.sh).

## Instalar
Copiar `RAV4Launcher.apk` a un USB, instalarlo en la radio y elegirlo como launcher por defecto.
No hay ADB a la radio (USB bloqueado por firmware), así que se prueba en la radio real.

## Documentación
- `CLAUDE.md` — contexto completo del proyecto y la radio.
- `DESIGN.md` — guía de diseño y reglas de UI.
- `FINDINGS.md` — hallazgos del análisis de las APK del sistema (volumen, batería, desinstalar…).

## Estructura
- `src/pe/radio/rav4/HomeActivity.java` — lógica y layouts de los 5 temas.
- `src/pe/radio/rav4/Theme.java` — paletas, íconos, fondos.
- `src/pe/radio/rav4/Views.java` — velocímetro, gráficos, gestos, fondos dibujados.
- `res/drawable-nodpi/rav4.png` — foto recortada del auto.
