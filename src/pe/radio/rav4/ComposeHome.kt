package pe.radio.rav4

import android.graphics.BitmapFactory
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Interfaz Compose (Kotlin, Jetpack Compose) — tema Cockpit, igual al de la web:
 * carretera neón cuya velocidad sigue a la del auto, velocímetro con arco degradado y aguja, brújula,
 * tarjetas Viaje / Batería / GPS / Música, dock a la izquierda (lado del conductor) y cajón de apps de 6×3 por página.
 * Gestos: ← abre el cajón; con el cajón abierto ← / → cambian de página y → en la primera lo cierra.
 */
class ComposeHome(private val a: HomeActivity, private val data: LauncherData) : ModernHome,
    androidx.lifecycle.LifecycleOwner, androidx.savedstate.SavedStateRegistryOwner {
    // HomeActivity es una Activity simple (sin AndroidX): Compose necesita un dueño de ciclo de vida propio
    private val lifecycleReg = androidx.lifecycle.LifecycleRegistry(this)
    private val savedState = androidx.savedstate.SavedStateRegistryController.create(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleReg
    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry get() = savedState.savedStateRegistry
    private val view = ComposeView(a)
    private val prefsVersion = mutableIntStateOf(0)
    private val appsVersion = mutableIntStateOf(0)
    private val drawerOpen = mutableStateOf(false)
    private val resumed = mutableStateOf(true)

    init {
        savedState.performRestore(null)
        lifecycleReg.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this))
        data.setListener { appsVersion.intValue++ }
        view.setContent { Root() }
    }

    override fun view(): View = view
    override fun reload() { prefsVersion.intValue++ }
    override fun back() { drawerOpen.value = false }
    override fun resume() { resumed.value = true; lifecycleReg.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
    override fun pause() { resumed.value = false; lifecycleReg.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
    override fun destroy() { data.setListener(null); lifecycleReg.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED }

    // ---------- colores (mismos tokens que la web: modo y acento globales) ----------
    private class Pal(val dark: Boolean, val acc: Color) {
        val acc2 = Color(0xFFFF2D95)
        val text = if (dark) Color(0xFFF2F4F8) else Color(0xFF111216)
        val dim = if (dark) Color(0x8CF2F4F8) else Color(0x8C111216)
        val card = if (dark) Color(0xB80C101C) else Color(0xC8FFFFFF)
        val line = if (dark) Color(0x14FFFFFF) else Color(0x14000000)
        val bg = if (dark) listOf(Color(0xFF020306), Color(0xFF05060C), Color(0xFF0A0420))
                 else listOf(Color(0xFFF5F7FB), Color(0xFFE3E8F0), Color(0xFFD9DFEA))
    }

    private fun pal(): Pal {
        val p = Theme.fromPrefs(a.webPrefs())
        val accHex = data.pref("accent", "")
        val acc = try { if (accHex.startsWith("#")) Color(android.graphics.Color.parseColor(accHex)) else Color(0xFF00E5FF) } catch (e: Exception) { Color(0xFF00E5FF) }
        return Pal(p.dark, acc)
    }

    private data class App(val key: String, val label: String, val icon: String, val hidden: Boolean, val pinned: Boolean)

    private fun apps(): List<App> = try {
        val arr = JSONArray(data.apps())
        (0 until arr.length()).map { val o = arr.getJSONObject(it); App(o.getString("key"), o.getString("label"), o.getString("icon"), o.optBoolean("hidden"), o.optBoolean("pinned")) }
    } catch (e: Exception) { emptyList() }

    private val iconCache = HashMap<String, ImageBitmap?>()
    private fun icon(path: String): ImageBitmap? = iconCache.getOrPut(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }

    // ================= raíz =================
    @Composable
    private fun Root() {
        prefsVersion.intValue
        val p = remember(prefsVersion.intValue) { pal() }
        val clock12 = remember(prefsVersion.intValue) { data.pref("clock", "12") != "24" }
        var st by remember { mutableStateOf(JSONObject()) }
        LaunchedEffect(Unit) { while (true) { try { st = JSONObject(data.state()) } catch (_: Exception) { }; delay(500) } }
        val all = remember(appsVersion.intValue) { apps() }
        val kmh by animateFloatAsState(st.optDouble("kmh", 0.0).toFloat(), tween(450), label = "kmh")

        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(p.bg))
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx < -120 && !drawerOpen.value) drawerOpen.value = true }) { _, d -> dx += d }
            }) {
            Road(kmh, p)
            Column(Modifier.fillMaxSize()) {
                StatusBar(st, p, clock12)
                Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 16.dp, bottom = 16.dp)) {
                    Dock(all.filter { it.pinned && !it.hidden }.ifEmpty { all.filter { !it.hidden }.sortedBy { it.label.lowercase() }.take(5) }, p)   // igual que el dock de la web
                    Spacer(Modifier.width(14.dp))
                    GaugeCard(kmh, st, p, Modifier.weight(1.15f).fillMaxHeight())
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TripCard(st, p, Modifier.weight(1f).fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.weight(1f)) {
                            BatteryCard(st, p, Modifier.weight(1f).fillMaxHeight())
                            Spacer(Modifier.width(14.dp))
                            GpsCard(st, p, Modifier.weight(1f).fillMaxHeight())
                        }
                        Spacer(Modifier.height(14.dp))
                        MusicCard(p, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
            AnimatedVisibility(drawerOpen.value, enter = fadeIn() + slideInHorizontally { it / 6 }, exit = fadeOut() + slideOutHorizontally { it / 6 }) {
                Drawer(all.filter { !it.hidden }.sortedBy { it.label.lowercase() }, p)
            }
        }
    }

    // ---------- fondo: carretera neón ----------
    @Composable
    private fun Road(kmh: Float, p: Pal) {
        var offset by remember { mutableFloatStateOf(0f) }
        val speed by rememberUpdatedState(kmh)
        LaunchedEffect(resumed.value) {
            var last = 0L
            while (resumed.value) {
                withFrameNanos { t ->
                    val dt = if (last == 0L) 0.016f else ((t - last) / 1e9f).coerceAtMost(0.05f)
                    last = t
                    offset = (offset + (0.15f + speed / 90f) * dt) % 1f
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height; val hz = h * 0.52f
            drawRect(Brush.radialGradient(listOf(p.acc.copy(alpha = 0.16f), Color.Transparent), Offset(w / 2, hz), w * 0.5f))
            for (i in -7..7) drawLine(p.acc.copy(alpha = if (p.dark) 0.2f else 0.25f), Offset(w / 2, hz), Offset(w / 2 + i * w / 5, h), 2f)
            for (i in 0 until 12) {
                val t = (i + offset) / 12f; val y = hz + (h - hz) * t * t
                drawLine(p.acc2.copy(alpha = 0.4f * t), Offset(0f, y), Offset(w, y), 2f)
            }
            drawLine(p.acc2, Offset(0f, hz), Offset(w, hz), 3f)
        }
    }

    // ---------- barra superior ----------
    @Composable
    private fun StatusBar(st: JSONObject, p: Pal, h12: Boolean) {
        var now by remember { mutableStateOf(Date()) }
        LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
        val time = SimpleDateFormat(if (h12) "h:mm" else "HH:mm", Locale("es")).format(now)
        val ampm = if (h12) SimpleDateFormat("a", Locale.US).format(now) else ""
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text2(time, 22, p.text, FontWeight.Bold)
            if (ampm.isNotEmpty()) Text2(" $ampm", 12, p.dim)
            Spacer(Modifier.width(12.dp))
            Text2(SimpleDateFormat("EEEE d 'de' MMMM", Locale("es")).format(now).uppercase(), 12, p.dim)
            Spacer(Modifier.weight(1f))
            val head = st.optString("heading", "")
            Text2((if (head.isNotEmpty()) "Rumbo $head · " else "") + st.optDouble("alt", 0.0).roundToInt() + " m s. n. m.", 13, p.dim)
            Spacer(Modifier.weight(1f))
            Text2((if (st.optBoolean("wifi")) "WiFi  " else "") + (if (st.optBoolean("bt")) "BT" else ""), 12, p.dim)
        }
    }

    // ---------- dock (izquierda) ----------
    @Composable
    private fun Dock(pinned: List<App>, p: Pal) {
        Column(Modifier.width(76.dp).fillMaxHeight().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            pinned.take(6).forEach { AppIcon(it, 58) }
            Spacer(Modifier.weight(1f))
            RoundBtn("⚙", p) { data.openSettings() }
            Spacer(Modifier.height(10.dp))
            RoundBtn("▦", p, p.acc) { drawerOpen.value = true }
        }
    }

    @Composable
    private fun RoundBtn(t: String, p: Pal, fill: Color? = null, onClick: () -> Unit) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(fill ?: p.card).border(1.dp, p.line, RoundedCornerShape(16.dp)).clickable { onClick() },
            contentAlignment = Alignment.Center) { Text2(t, 26, if (fill != null) Color(0xFF001018) else p.text) }
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun AppIcon(app: App, sizeDp: Int, label: Boolean = false, p: Pal? = null) {
        Column(Modifier.padding(vertical = 5.dp).combinedClickable(onClick = { data.launch(app.key) }, onLongClick = { data.appMenu(app.key) }),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // el ícono real, sin placa ni contorno
            val bmp = icon(app.icon)
            if (bmp != null) Image(bmp, app.label, Modifier.size(sizeDp.dp)) else Spacer(Modifier.size(sizeDp.dp))
            if (label && p != null) Text2(app.label, 14, p.text, maxLines = 1, modifier = Modifier.width((sizeDp + 50).dp).padding(top = 6.dp), center = true)
        }
    }

    // ---------- tarjetas ----------
    @Composable
    private fun Card(p: Pal, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
        Column(modifier.clip(RoundedCornerShape(22.dp)).background(p.card).border(1.dp, p.line, RoundedCornerShape(22.dp)).padding(18.dp), content = content)
    }

    @Composable
    private fun H4(t: String, p: Pal) = Text2(t.uppercase(), 12, p.dim, letter = 2f)

    @Composable
    private fun Big(n: String, unit: String, p: Pal) {
        Row(verticalAlignment = Alignment.Bottom) { Text2(n, 42, p.text, FontWeight.Bold); Text2(" $unit", 17, p.dim, modifier = Modifier.padding(bottom = 7.dp)) }
    }

    @Composable
    private fun GaugeCard(kmh: Float, st: JSONObject, p: Pal, modifier: Modifier) {
        val tm = rememberTextMeasurer()
        val max = 180f
        Card(p, modifier) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxHeight(0.92f).aspectRatio(1f)) {
                    val r = size.minDimension / 2 * 0.8f; val c = center
                    val f = (kmh / max).coerceIn(0f, 1f)
                    val tl = Offset(c.x - r, c.y - r); val sz = Size(r * 2, r * 2)
                    drawArc(if (p.dark) Color(0x10FFFFFF) else Color(0x12000000), 135f, 270f, false, tl, sz, style = Stroke(r * 0.11f))
                    drawArc(Brush.sweepGradient(listOf(p.acc, Color(0xFF7C4DFF), p.acc2, p.acc), c), 135f, 270f * f, false, tl, sz, style = Stroke(r * 0.11f, cap = StrokeCap.Round))
                    for (v in 0..max.toInt() step 10) {
                        val ang = Math.toRadians(135.0 + 270.0 * v / max); val major = v % 20 == 0
                        val o = Offset(c.x + r * 0.94f * cos(ang).toFloat(), c.y + r * 0.94f * sin(ang).toFloat())
                        val i = Offset(c.x + r * (if (major) 0.82f else 0.87f) * cos(ang).toFloat(), c.y + r * (if (major) 0.82f else 0.87f) * sin(ang).toFloat())
                        drawLine(p.text.copy(alpha = if (major) 0.85f else 0.35f), i, o, if (major) 3f else 2f)
                        if (major) {
                            val lay = tm.measure("$v", TextStyle(color = p.dim, fontSize = 14.sp))
                            drawText(lay, topLeft = Offset(c.x + r * 0.68f * cos(ang).toFloat() - lay.size.width / 2, c.y + r * 0.68f * sin(ang).toFloat() - lay.size.height / 2))
                        }
                    }
                    rotate(270f * f - 135f, c) {
                        val needle = Path().apply { moveTo(c.x - 4.dp.toPx(), c.y + 5.dp.toPx()); lineTo(c.x, c.y - r * 0.88f); lineTo(c.x + 4.dp.toPx(), c.y + 5.dp.toPx()); close() }
                        drawPath(needle, p.acc2)
                    }
                    drawCircle(if (p.dark) Color(0xFF0B0D14) else Color.White, 13.dp.toPx(), c)
                    drawCircle(p.acc, 13.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                }
                Column(Modifier.align(Alignment.Center).padding(top = 190.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text2("${kmh.roundToInt()}", 80, p.text, FontWeight.Bold)
                    Text2("KM/H", 14, p.dim, letter = 5f)
                }
                val moving = st.optDouble("kmh", 0.0) > 2
                Text2((if (moving) "En marcha" else "Estacionado") + (st.optString("heading", "").let { if (it.isNotEmpty()) " · $it" else "" }), 15, p.dim,
                    modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    @Composable
    private fun TripCard(st: JSONObject, p: Pal, modifier: Modifier) {
        Card(p, modifier) {
            Row {
                Column(Modifier.weight(1f)) {
                    H4("Viaje", p)
                    Big(String.format(Locale.US, "%.1f", st.optDouble("distKm", 0.0)), "km", p)
                    Text2("${st.optInt("movingMin", 0)} min · prom. ${st.optDouble("avgKmh", 0.0).roundToInt()} · máx. ${st.optDouble("maxKmh", 0.0).roundToInt()} km/h", 14, p.dim)
                }
                Compass(st.optDouble("bearing", -1.0).toFloat(), p)
            }
        }
    }

    @Composable
    private fun Compass(bearing: Float, p: Pal) {
        val b by animateFloatAsState(if (bearing < 0) 0f else bearing, tween(600), label = "rumbo")
        Box(Modifier.size(80.dp).border(2.dp, p.acc.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().rotate(-b)) {
                Text2("N", 14, p.acc2, FontWeight.Bold, modifier = Modifier.align(Alignment.TopCenter).padding(top = 3.dp))
                Text2("E", 13, p.text, FontWeight.Bold, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp))
                Text2("S", 13, p.text, FontWeight.Bold, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
                Text2("O", 13, p.text, FontWeight.Bold, modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
            }
        }
    }

    @Composable
    private fun BatteryCard(st: JSONObject, p: Pal, modifier: Modifier) {
        val v = st.optDouble("batteryV", -1.0)
        Card(p, modifier) {
            H4("Batería", p)
            Big(if (v > 0) String.format(Locale.US, "%.1f", v) else "—", "V", p)
            val f = if (v > 0) ((v - 11.5) / 3.0).toFloat().coerceIn(0f, 1f) else 0f
            val col = when { v <= 0 -> p.dim; v < 12.0 -> Color(0xFFFF453A); v < 12.4 -> Color(0xFFFF9F0A); else -> p.acc }
            Bar(f, col, p)
            Text2(when { v <= 0 -> "Sin dato"; v >= 13.2 -> "Cargando"; v < 12.0 -> "Baja"; else -> "Normal" }, 13, p.dim, modifier = Modifier.padding(top = 6.dp))
        }
    }

    @Composable
    private fun GpsCard(st: JSONObject, p: Pal, modifier: Modifier) {
        Card(p, modifier) {
            H4("GPS", p)
            Big("${st.optInt("satsUsed", 0)}", "sats", p)
            Text2("${st.optInt("satsUsed", 0)}/${st.optInt("satsSeen", 0)} · ${st.optDouble("alt", 0.0).roundToInt()} m · ${st.optString("heading", "—")}", 13, p.dim)
        }
    }

    @Composable
    private fun MusicCard(p: Pal, modifier: Modifier) {
        Card(p, modifier) {
            H4("Música", p)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                MediaBtn("⏮", p) { data.media("prev") }
                Box(Modifier.size(64.dp).clip(CircleShape).background(p.acc).clickable { data.media("play") }, contentAlignment = Alignment.Center) { Text2("⏯", 26, Color(0xFF001018)) }
                MediaBtn("⏭", p) { data.media("next") }
            }
        }
    }

    @Composable
    private fun MediaBtn(t: String, p: Pal, onClick: () -> Unit) {
        Box(Modifier.size(56.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) { Text2(t, 26, p.text) }
    }

    @Composable
    private fun Bar(f: Float, c: Color, p: Pal) {
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(p.line)) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(c))
        }
    }

    // ---------- cajón de apps: páginas de 6×3 ----------
    @Composable
    private fun Drawer(list: List<App>, p: Pal) {
        val pages = list.chunked(18).ifEmpty { listOf(emptyList()) }
        val pager = rememberPagerState { pages.size }
        Box(Modifier.fillMaxSize().background(if (p.dark) Color(0xF0050608) else Color(0xF0F2F2F7))
            .pointerInput(pager.currentPage) {
                // → en la primera página cierra el cajón (el pager mueve las demás)
                var dx = 0f
                detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx > 120 && pager.currentPage == 0) drawerOpen.value = false }) { _, d -> dx += d }
            }) {
            Column(Modifier.fillMaxSize().padding(top = 24.dp, bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text2("Apps", 26, p.text, FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(p.card).clickable { drawerOpen.value = false }.padding(horizontal = 20.dp, vertical = 10.dp)) { Text2("Cerrar", 16, p.text) }
                }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                        pages[page].chunked(6).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                row.forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { AppIcon(it, 78, true, p) } }
                                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    repeat(pages.size) { i -> Box(Modifier.padding(5.dp).size(9.dp).clip(CircleShape).background(if (i == pager.currentPage) p.acc else p.dim)) }
                }
            }
        }
    }

    @Composable
    private fun Text2(t: String, size: Int, color: Color, weight: FontWeight = FontWeight.Normal, letter: Float = 0f, maxLines: Int = 2,
                      modifier: Modifier = Modifier, center: Boolean = false) {
        androidx.compose.foundation.text.BasicText(t, modifier, TextStyle(color = color, fontSize = size.sp, fontWeight = weight, letterSpacing = letter.sp,
            textAlign = if (center) TextAlign.Center else TextAlign.Start), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}
