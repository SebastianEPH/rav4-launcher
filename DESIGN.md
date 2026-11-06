# Guía de diseño — RAV4 Launcher

Reglas para que los temas se vean pulidos y no "hechos por hacer". El usuario no es diseñador,
así que el launcher debe verse bien sin que él tenga que ajustar nada.

## Problemas conocidos a corregir (feedback del usuario)
- **Texto que se sale de los recuadros.** Causa raíz: tarjetas con alto fijo (`dp(290)`, pesos)
  y texto sin recortar. Solución: las tarjetas deben crecer con su contenido o recortar el texto
  (`setMaxLines` + `setEllipsize`), nunca desbordar.
- **Se ve básico / sin diseño.** Faltan: márgenes consistentes, jerarquía tipográfica, estados
  vacíos ("Buscando GPS…" en vez de campos en blanco), alineación.

## Sistema de medidas (usar siempre dp())
- Gutter exterior de pantalla: **24 dp**.
- Separación entre tarjetas: **12 dp**.
- Padding interno de tarjeta: **16 dp** (14 en tarjetas pequeñas).
- Radio de esquina: Terminal 14, Glass 28, Dusk 22, Cockpit/Inicio 20.
- Alturas tocables mínimas: **48 dp** (botones del conductor, 56–72 mejor).

## Tipografía
- Terminal: monoespaciada (`Typeface.MONOSPACE`).
- Glass/Dusk/Cockpit/Inicio: `sans-serif` y `sans-serif-medium` para títulos.
- Jerarquía: dato principal grande (40–64 sp), etiqueta pequeña atenuada (11–14 sp, color `dim`),
  cuerpo 15–17 sp.
- **Todo texto en una tarjeta:** una sola línea con ellipsis salvo que sea el cuerpo esperado.

## Color (definido en Theme.java por tema y modo)
Cada tema tiene paleta para modo oscuro y claro:
`bg/bg2/bg3` (fondo/degradado), `panel` (tarjeta), `border`, `text`, `dim` (texto secundario),
`accent`, `ok` (verde), `warn` (naranja), `err` (rojo), `track` (fondo de barras), `iconBg`.
- Las tarjetas van sobre `panel` (semitransparente en temas con fondo).
- El texto principal usa `text`; etiquetas y secundario usan `dim`.
- Nunca texto de color plano sobre fondo del mismo tono: contraste siempre.

## Layout de tarjetas (regla para no desbordar)
1. La tarjeta es un `LinearLayout` vertical con padding uniforme.
2. Título arriba: etiqueta corta en `dim`, `fontBold`.
3. Contenido debajo, que crece o se recorta — **nunca** alto fijo que corte el texto.
4. En filas de tarjetas, usar pesos (`layout_weight`) y que cada tarjeta tenga `rightMargin`
   menos la última.
5. Datos de viaje (dist/mov/prom/máx): 4 columnas iguales, cada una etiqueta + número, el número
   en una línea.

## Íconos
- Forma **squircle** (cuadrado muy redondeado, estilo iOS) con fondo `iconBg`, vía
  `Theme.squircle()`. **Nunca círculos** (rechazado por el usuario).
- Tamaños: dock 52–64 dp, cajón 84 dp, tema Inicio 110 dp.
- Nombre debajo, centrado, máximo 2 líneas con ellipsis.

## Estados vacíos
- GPS sin señal: "Buscando GPS…" / "Sin señal", nunca campo vacío.
- Batería sin dato: no mostrar la fila (la radio puede no tener CAN).

## Animaciones (sutiles)
- Al tocar un ícono: escala a 0.92 y vuelve (80 ms).
- Abrir/cerrar cajón: deslizar desde el borde (220/200 ms).
- Centro de control: deslizar desde arriba (240 ms).

## Checklist antes de dar un tema por bueno
- [ ] Ningún texto se sale de su tarjeta a 1280×800.
- [ ] Márgenes de 12/24 dp consistentes.
- [ ] Jerarquía tipográfica clara (dato grande, etiqueta chica atenuada).
- [ ] Funciona en modo claro y oscuro.
- [ ] Zonas tocables ≥ 48 dp.
- [ ] Estados vacíos con texto, no en blanco.
- [ ] Probado (o pendiente de probar) en la radio real.
