#!/bin/bash
# Genera las notas de un Release a partir de git (no hay pull requests: se sube directo a main).
# Uso: .github/release-notes.sh <versión> [commit]     → imprime Markdown
set -e
VERSION="$1"
REF="${2:-HEAD}"
REPO="${GITHUB_REPOSITORY:-SebastianEPH/rav4-launcher}"
PREV=$(git describe --tags --abbrev=0 --match 'v1.3.*' --exclude "$VERSION" "$REF" 2>/dev/null || true)
RANGE="${PREV:+$PREV..}$REF"

area() {
  case "$1" in
    src/*) echo "Launcher nativo (Java)" ;;
    assets/web/*) echo "Interfaz web" ;;
    res/*) echo "Recursos" ;;
    AndroidManifest.xml) echo "Permisos y versión" ;;
    .github/*) echo "Compilación y publicación (CI)" ;;
    site/*) echo "Demo en GitHub Pages" ;;
    docs/*) echo "Capturas" ;;
    *.md) echo "Documentación" ;;
    *) echo "Otros" ;;
  esac
}

echo "Instala desde el launcher: **Ajustes Launcher → Buscar actualización**."
echo "O descarga el \`.apk\` de abajo, cópialo a un USB e instálalo en la radio: está firmado con la misma clave y se instala encima."
echo
echo "## Novedades desde ${PREV:-el inicio}"
git log --no-merges --pretty='- %s' "$RANGE"
echo

if [ -n "$PREV" ]; then
  ADDED=$(git diff --name-status "$PREV" "$REF" | awk '$1=="A"{print $2}')
  REMOVED=$(git diff --name-status "$PREV" "$REF" | awk '$1=="D"{print $2}')
  CHANGED=$(git diff --name-status "$PREV" "$REF" | awk '$1 ~ /^[MR]/{print $NF}')
  if [ -n "$ADDED" ]; then
    echo "### ➕ Agregado"
    for f in $ADDED; do echo "- \`$f\` — $(area "$f")"; done
    echo
  fi
  if [ -n "$REMOVED" ]; then
    echo "### ➖ Eliminado"
    for f in $REMOVED; do echo "- \`$f\` — $(area "$f")"; done
    echo
  fi
  if [ -n "$CHANGED" ]; then
    echo "### ✏️ Modificado"
    for f in $CHANGED; do area "$f"; done | sort | uniq -c | sort -rn | awk '{n=$1; $1=""; sub(/^ /,""); print "- " $0 " (" n (n==1?" archivo":" archivos") ")"}'
    echo
  fi
fi

echo "## Qué trae el launcher"
cat <<'EOF'
- Dos interfaces: **Nativa** (Java, liviana) y **Web animada** con 6 temas: Terminal, Glass, Dusk, Cockpit, Inicio y Auto · CAN.
- Cockpit con tarjetas a elección (rumbo y calle, altitud y pendiente, 0–100 y fuerza G, accesos a CarPlay/mapas, controles, reloj) en Ajustes Launcher → Tema actual.
- Tema Sonido: visualizador del audio que suena, volumen grande con deslizador y lo que se está reproduciendo.
- Espectro de audio con 7 estilos y 8 paletas, como tarjeta en cualquier tema o de fondo; volumen sin la barra del sistema.
- Velocidad y viaje por GPS, voltaje de la batería del auto, volumen real de la radio (NWD) y brillo con mínimo seguro.
- **Bus CAN en vivo** (tema Auto · CAN): puertas, RPM, luces, freno de mano, cinturón… y grabación en `.txt` al USB.
- Temas configurables: modo claro/oscuro/automático por horas, color de acento, reloj 12/24 h y opciones de cada tema.
- **Ajustes Launcher** en ventana aparte (launcher liviano), desinstalar apps del sistema y actualización desde el propio launcher.
EOF
echo

# Lo que todavía no está: sale de la sección "Pendientes / ideas" de CLAUDE.md
PENDING=$(git show "$REF:CLAUDE.md" 2>/dev/null | awk '/^## Pendientes/{f=1;next} /^## /{f=0} f && /^- /')
if [ -n "$PENDING" ]; then
  echo "## Todavía no incluye"
  echo "$PENDING"
  echo
fi

[ -n "$PREV" ] && echo "**Comparar con la versión anterior:** https://github.com/$REPO/compare/$PREV...$VERSION"
exit 0
