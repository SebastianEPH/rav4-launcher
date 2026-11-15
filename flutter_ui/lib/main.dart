// Órbita Launcher — interfaz Flutter, tema Cockpit (igual al de la web, Compose y React Native):
// carretera neón cuya velocidad sigue a la del auto, velocímetro con arco degradado y aguja, brújula,
// tarjetas Viaje / Batería / GPS / Música, dock a la izquierda y cajón de apps de 6×3 por página.
// Los datos llegan cada 500 ms por el canal "launcher" (copias en caché de LauncherData).
// Gestos: ← abre el cajón; en el cajón ←/→ cambian de página y → en la primera lo cierra.
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';

const ch = MethodChannel('launcher');
const acc2 = Color(0xFFFF2D95);

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MaterialApp(debugShowCheckedModeBanner: false, home: Cockpit()));
}

class Pal {
  final bool dark;
  final Color acc;
  Pal(this.dark, this.acc);
  Color get text => dark ? const Color(0xFFF2F4F8) : const Color(0xFF111216);
  Color get dim => dark ? const Color(0x8CF2F4F8) : const Color(0x8C111216);
  Color get card => dark ? const Color(0xB80C101C) : const Color(0xC8FFFFFF);
  Color get line => dark ? const Color(0x14FFFFFF) : const Color(0x14000000);
  List<Color> get bg => dark ? const [Color(0xFF020306), Color(0xFF05060C), Color(0xFF0A0420)] : const [Color(0xFFF5F7FB), Color(0xFFE3E8F0), Color(0xFFD9DFEA)];
}

class AppInfo {
  final String key, label, icon;
  final bool hidden, pinned;
  AppInfo(Map m) : key = m['key'], label = m['label'], icon = m['icon'], hidden = m['hidden'] == true, pinned = m['pinned'] == true;
}

class Cockpit extends StatefulWidget {
  const Cockpit({super.key});
  @override
  State<Cockpit> createState() => _CockpitState();
}

class _CockpitState extends State<Cockpit> with SingleTickerProviderStateMixin {
  Pal p = Pal(true, const Color(0xFF00E5FF));
  bool h12 = true, drawer = false;
  Map st = {};
  List<AppInfo> apps = [];
  double kmh = 0, shown = 0, offset = 0, bearing = 0;
  late final Ticker ticker;
  Duration last = Duration.zero;
  final pager = PageController();
  int page = 0;

  @override
  void initState() {
    super.initState();
    ch.setMethodCallHandler((c) async {
      if (c.method == 'appsChanged') await loadApps();
      if (c.method == 'prefsChanged') { await loadPrefs(); await loadApps(); }
      if (c.method == 'back') setState(() => drawer = false);
    });
    loadPrefs(); loadApps(); poll();
    // carretera y aguja en cada cuadro
    ticker = createTicker((t) {
      final dt = last == Duration.zero ? 0.016 : ((t - last).inMicroseconds / 1e6).clamp(0.0, 0.05);
      last = t;
      setState(() {
        shown += (kmh - shown) * math.min(1, dt * 4);
        offset = (offset + (0.15 + shown / 90) * dt) % 1;
      });
    })..start();
  }

  Future<void> loadPrefs() async {
    final dark = await ch.invokeMethod<bool>('dark') ?? true;
    final a = await ch.invokeMethod<String>('pref', {'k': 'accent', 'd': ''}) ?? '';
    final c = await ch.invokeMethod<String>('pref', {'k': 'clock', 'd': '12'}) ?? '12';
    Color acc = const Color(0xFF00E5FF);
    if (a.startsWith('#') && a.length == 7) acc = Color(int.parse('FF${a.substring(1)}', radix: 16));
    setState(() { p = Pal(dark, acc); h12 = c != '24'; });
  }

  Future<void> loadApps() async {
    final s = await ch.invokeMethod<String>('apps') ?? '[]';
    setState(() => apps = (jsonDecode(s) as List).map((m) => AppInfo(m)).toList());
  }

  Future<void> poll() async {
    while (mounted) {
      try {
        final s = jsonDecode(await ch.invokeMethod<String>('state') ?? '{}') as Map;
        st = s;
        kmh = (s['kmh'] ?? 0).toDouble();
        final b = (s['bearing'] ?? -1).toDouble();
        if (b >= 0) bearing = b;
      } catch (_) {}
      await Future.delayed(const Duration(milliseconds: 500));
    }
  }

  @override
  void dispose() { ticker.dispose(); super.dispose(); }

  // ---------- piezas ----------
  Widget txt(String s, double size, Color c, {FontWeight w = FontWeight.normal, double ls = 0, int lines = 2, TextAlign al = TextAlign.start}) =>
      Text(s, maxLines: lines, overflow: TextOverflow.ellipsis, textAlign: al, style: TextStyle(color: c, fontSize: size, fontWeight: w, letterSpacing: ls));
  Widget card(Widget child) => Container(
      margin: const EdgeInsets.only(bottom: 14), padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(color: p.card, borderRadius: BorderRadius.circular(22), border: Border.all(color: p.line)), child: child);
  Widget h4(String s) => txt(s.toUpperCase(), 12, p.dim, ls: 2);
  Widget big(String n, String u) => Text.rich(TextSpan(text: n, style: TextStyle(color: p.text, fontSize: 42, fontWeight: FontWeight.bold),
      children: [TextSpan(text: ' $u', style: TextStyle(fontSize: 17, color: p.dim))]));
  Widget bar(double f, Color c) => Container(
      margin: const EdgeInsets.only(top: 10), height: 8,
      decoration: BoxDecoration(color: p.line, borderRadius: BorderRadius.circular(4)),
      child: FractionallySizedBox(alignment: Alignment.centerLeft, widthFactor: f.clamp(0.0, 1.0),
          child: Container(decoration: BoxDecoration(color: c, borderRadius: BorderRadius.circular(4)))));

  Widget appIcon(AppInfo a, double size, {bool label = false}) => GestureDetector(
        onTap: () => ch.invokeMethod('launch', a.key),
        onLongPress: () => ch.invokeMethod('appMenu', a.key),
        child: Padding(padding: const EdgeInsets.symmetric(vertical: 5), child: Column(mainAxisSize: MainAxisSize.min, children: [
          // el ícono real, sin placa ni contorno
          Image.file(File(a.icon), width: size, height: size, gaplessPlayback: true),
          if (label) SizedBox(width: size + 50, child: Padding(padding: const EdgeInsets.only(top: 6),
              child: Text(a.label, maxLines: 1, overflow: TextOverflow.ellipsis, textAlign: TextAlign.center,
                  style: TextStyle(color: p.text, fontSize: 14, shadows: const [Shadow(blurRadius: 4, color: Color(0x99000000))])))),
        ])),
      );

  Widget squareBtn(String t, Color bg, Color fg, VoidCallback onTap) => GestureDetector(onTap: onTap, child: Container(
      width: 58, height: 58, alignment: Alignment.center,
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(16), border: Border.all(color: p.line)),
      child: txt(t, 26, fg)));

  // ---------- pantalla ----------
  @override
  Widget build(BuildContext context) {
    final visible = apps.where((a) => !a.hidden).toList();
    final sorted = [...visible]..sort((x, y) => x.label.toLowerCase().compareTo(y.label.toLowerCase()));
    var dock = visible.where((a) => a.pinned).toList();
    if (dock.isEmpty) dock = sorted.take(5).toList();
    final now = DateTime.now();
    final hh = now.hour, mm = now.minute.toString().padLeft(2, '0');
    const days = ['LUNES', 'MARTES', 'MIÉRCOLES', 'JUEVES', 'VIERNES', 'SÁBADO', 'DOMINGO'];
    const months = ['ENERO', 'FEBRERO', 'MARZO', 'ABRIL', 'MAYO', 'JUNIO', 'JULIO', 'AGOSTO', 'SEPTIEMBRE', 'OCTUBRE', 'NOVIEMBRE', 'DICIEMBRE'];
    final v = (st['batteryV'] ?? -1).toDouble();
    final bc = v <= 0 ? p.dim : v < 12 ? const Color(0xFFFF453A) : v < 12.4 ? const Color(0xFFFF9F0A) : p.acc;
    final head = (st['heading'] ?? '').toString();
    final alt = ((st['alt'] ?? 0) as num).round();

    return Scaffold(
      backgroundColor: p.bg.first,
      body: GestureDetector(
        onHorizontalDragEnd: (d) { if (!drawer && (d.primaryVelocity ?? 0) < -300) setState(() => drawer = true); },
        child: Stack(children: [
          Positioned.fill(child: DecoratedBox(decoration: BoxDecoration(gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: p.bg)))),
          Positioned.fill(child: RepaintBoundary(child: CustomPaint(painter: RoadPainter(offset, p)))),
          Column(children: [
            SizedBox(height: 48, child: Padding(padding: const EdgeInsets.symmetric(horizontal: 20), child: Row(children: [
              txt(h12 ? '${(hh + 11) % 12 + 1}:$mm' : '${hh.toString().padLeft(2, '0')}:$mm', 22, p.text, w: FontWeight.bold),
              if (h12) txt(' ${hh < 12 ? 'AM' : 'PM'}', 12, p.dim),
              const SizedBox(width: 12),
              txt('${days[now.weekday - 1]} ${now.day} DE ${months[now.month - 1]}', 12, p.dim),
              Expanded(child: txt('${head.isNotEmpty ? 'Rumbo $head · ' : ''}$alt m s. n. m.', 13, p.dim, al: TextAlign.center)),
              txt('${st['wifi'] == true ? 'WiFi  ' : ''}${st['bt'] == true ? 'BT' : ''}', 12, p.dim),
            ]))),
            Expanded(child: Padding(padding: const EdgeInsets.fromLTRB(12, 0, 16, 16), child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              // dock (lado del conductor)
              SizedBox(width: 76, child: Column(children: [
                const Spacer(),
                ...dock.take(6).map((a) => appIcon(a, 58)),
                const Spacer(),
                squareBtn('⚙', p.card, p.text, () => ch.invokeMethod('openSettings')),
                const SizedBox(height: 10),
                squareBtn('▦', p.acc, const Color(0xFF001018), () => setState(() => drawer = true)),
              ])),
              const SizedBox(width: 14),
              // velocímetro
              Expanded(flex: 115, child: card(Stack(alignment: Alignment.center, children: [
                Center(child: AspectRatio(aspectRatio: 1, child: RepaintBoundary(child: CustomPaint(painter: GaugePainter(shown, p))))),
                Positioned(top: 0, bottom: 0, child: Padding(padding: const EdgeInsets.only(top: 190), child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                  txt('${shown.round()}', 80, p.text, w: FontWeight.bold), txt('KM/H', 14, p.dim, ls: 5)]))),
                Positioned(bottom: 0, child: txt('${(st['kmh'] ?? 0) > 2 ? 'En marcha' : 'Estacionado'}${head.isNotEmpty ? ' · $head' : ''}', 15, p.dim)),
              ]))),
              const SizedBox(width: 14),
              // tarjetas
              Expanded(flex: 100, child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Expanded(child: card(Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    h4('Viaje'), big(((st['distKm'] ?? 0) as num).toStringAsFixed(1), 'km'),
                    txt('${((st['movingMin'] ?? 0) as num).round()} min · prom. ${((st['avgKmh'] ?? 0) as num).round()} · máx. ${((st['maxKmh'] ?? 0) as num).round()} km/h', 14, p.dim),
                  ])),
                  SizedBox(width: 80, height: 80, child: TweenAnimationBuilder<double>(tween: Tween(end: bearing), duration: const Duration(milliseconds: 600),
                      builder: (_, b, __) => CustomPaint(painter: CompassPainter(b, p)))),
                ]))),
                Expanded(child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  Expanded(child: card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    h4('Batería'), big(v > 0 ? v.toStringAsFixed(1) : '—', 'V'), bar(v > 0 ? (v - 11.5) / 3 : 0, bc),
                    Padding(padding: const EdgeInsets.only(top: 6), child: txt(v <= 0 ? 'Sin dato' : v >= 13.2 ? 'Cargando' : v < 12 ? 'Baja' : 'Normal', 13, p.dim)),
                  ]))),
                  const SizedBox(width: 14),
                  Expanded(child: card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    h4('GPS'), big('${st['satsUsed'] ?? 0}', 'sats'),
                    txt('${st['satsUsed'] ?? 0}/${st['satsSeen'] ?? 0} · $alt m · ${head.isEmpty ? '—' : head}', 13, p.dim),
                  ]))),
                ])),
                Expanded(child: card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  h4('Música'),
                  Expanded(child: Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
                    IconButton(onPressed: () => ch.invokeMethod('media', 'prev'), icon: Icon(Icons.skip_previous, color: p.text, size: 34)),
                    GestureDetector(onTap: () => ch.invokeMethod('media', 'play'), child: Container(width: 64, height: 64,
                        decoration: BoxDecoration(color: p.acc, shape: BoxShape.circle), child: const Icon(Icons.play_arrow, color: Color(0xFF001018), size: 34))),
                    IconButton(onPressed: () => ch.invokeMethod('media', 'next'), icon: Icon(Icons.skip_next, color: p.text, size: 34)),
                  ])),
                ]))),
              ])),
            ]))),
          ]),
          if (drawer) drawerView(sorted),
        ]),
      ),
    );
  }

  // ---------- cajón de apps: páginas de 6×3 ----------
  Widget drawerView(List<AppInfo> list) {
    final pages = <List<AppInfo>>[];
    for (var i = 0; i < list.length; i += 18) { pages.add(list.sublist(i, math.min(i + 18, list.length))); }
    if (pages.isEmpty) pages.add([]);
    return Positioned.fill(child: NotificationListener<OverscrollNotification>(
      // → en la primera página (el PageView ya no puede ir atrás) cierra el cajón
      onNotification: (n) { if (page == 0 && n.overscroll < -8) setState(() => drawer = false); return false; },
      child: Container(
        color: p.dark ? const Color(0xF0050608) : const Color(0xF0F2F2F7),
        padding: const EdgeInsets.only(top: 24, bottom: 16),
        child: Column(children: [
          Padding(padding: const EdgeInsets.symmetric(horizontal: 28), child: Row(children: [
            Expanded(child: txt('Apps', 26, p.text, w: FontWeight.bold)),
            GestureDetector(onTap: () => setState(() => drawer = false), child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                decoration: BoxDecoration(color: p.card, borderRadius: BorderRadius.circular(14)), child: txt('Cerrar', 16, p.text))),
          ])),
          Expanded(child: PageView.builder(
            controller: pager, itemCount: pages.length,
            physics: const PageScrollPhysics(parent: ClampingScrollPhysics()),
            onPageChanged: (i) => setState(() => page = i),
            itemBuilder: (_, i) => Padding(padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12), child: Column(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: List.generate(3, (r) => Row(children: List.generate(6, (c) {
                final k = r * 6 + c;
                return Expanded(child: Center(child: k < pages[i].length ? appIcon(pages[i][k], 78, label: true) : const SizedBox()));
              }))),
            )),
          )),
          Row(mainAxisAlignment: MainAxisAlignment.center, children: List.generate(pages.length, (i) => Container(
              width: 9, height: 9, margin: const EdgeInsets.all(5),
              decoration: BoxDecoration(shape: BoxShape.circle, color: i == page ? p.acc : p.dim)))),
        ]),
      ),
    ));
  }
}

class RoadPainter extends CustomPainter {
  final double offset;
  final Pal p;
  RoadPainter(this.offset, this.p);
  @override
  void paint(Canvas c, Size s) {
    final w = s.width, h = s.height, hz = h * 0.52, paint = Paint()..strokeWidth = 2;
    c.drawRect(Offset.zero & s, Paint()..shader = RadialGradient(colors: [p.acc.withValues(alpha: 0.16), Colors.transparent], radius: 0.5)
        .createShader(Rect.fromCircle(center: Offset(w / 2, hz), radius: w * 0.5)));
    paint.color = p.acc.withValues(alpha: 0.2);
    for (var i = -7; i <= 7; i++) { c.drawLine(Offset(w / 2, hz), Offset(w / 2 + i * w / 5, h), paint); }
    for (var i = 0; i < 12; i++) {
      final t = (i + offset) / 12, y = hz + (h - hz) * t * t;
      paint.color = acc2.withValues(alpha: 0.4 * t);
      c.drawLine(Offset(0, y), Offset(w, y), paint);
    }
    c.drawLine(Offset(0, hz), Offset(w, hz), Paint()..color = acc2..strokeWidth = 3);
  }
  @override
  bool shouldRepaint(RoadPainter o) => o.offset != offset || o.p != p;
}

class GaugePainter extends CustomPainter {
  final double kmh;
  final Pal p;
  GaugePainter(this.kmh, this.p);
  static const max = 180.0;
  @override
  void paint(Canvas c, Size s) {
    final k = s.width / 400;
    c.scale(k);
    const ctr = Offset(200, 200);
    final rect = Rect.fromCircle(center: ctr, radius: 160), f = (kmh / max).clamp(0.0, 1.0);
    double rad(double a) => a * math.pi / 180;
    c.drawArc(rect, rad(135), rad(270), false, Paint()..style = PaintingStyle.stroke..strokeWidth = 18..color = p.dark ? const Color(0x10FFFFFF) : const Color(0x12000000));
    c.drawArc(rect, rad(135), rad(270 * f), false, Paint()
      ..style = PaintingStyle.stroke..strokeWidth = 18..strokeCap = StrokeCap.round
      ..shader = LinearGradient(begin: Alignment.bottomLeft, end: Alignment.topRight, colors: [p.acc, const Color(0xFF7C4DFF), acc2]).createShader(rect));
    Offset pt(double a, double r) => ctr + Offset(r * math.cos(rad(a)), r * math.sin(rad(a)));
    for (var v = 0; v <= max; v += 10) {
      final a = 135 + 270 * v / max, major = v % 20 == 0;
      c.drawLine(pt(a, 150), pt(a, major ? 132 : 140), Paint()..strokeWidth = major ? 3 : 2..color = p.text.withValues(alpha: major ? 0.85 : 0.35));
      if (major) {
        final tp = TextPainter(text: TextSpan(text: '$v', style: TextStyle(color: p.dim, fontSize: 15)), textDirection: TextDirection.ltr)..layout();
        tp.paint(c, pt(a, 112) - Offset(tp.width / 2, tp.height / 2));
      }
    }
    c.save();
    c.translate(200, 200);
    c.rotate(rad(270 * f - 135));
    c.drawPath(Path()..moveTo(-4, 5)..lineTo(0, -138)..lineTo(4, 5)..close(), Paint()..color = acc2);
    c.restore();
    c.drawCircle(ctr, 13, Paint()..color = p.dark ? const Color(0xFF0B0D14) : Colors.white);
    c.drawCircle(ctr, 13, Paint()..style = PaintingStyle.stroke..strokeWidth = 3..color = p.acc);
  }
  @override
  bool shouldRepaint(GaugePainter o) => o.kmh != kmh || o.p != p;
}

class CompassPainter extends CustomPainter {
  final double bearing;
  final Pal p;
  CompassPainter(this.bearing, this.p);
  @override
  void paint(Canvas c, Size s) {
    final ctr = s.center(Offset.zero), r = s.width / 2 - 2;
    c.drawCircle(ctr, r, Paint()..style = PaintingStyle.stroke..strokeWidth = 2..color = p.acc.withValues(alpha: 0.5));
    c.save();
    c.translate(ctr.dx, ctr.dy);
    c.rotate(-bearing * math.pi / 180);
    const dirs = ['N', 'E', 'S', 'O'];
    for (var i = 0; i < 4; i++) {
      final a = i * math.pi / 2 - math.pi / 2;
      final tp = TextPainter(text: TextSpan(text: dirs[i], style: TextStyle(color: i == 0 ? acc2 : p.text, fontSize: 13, fontWeight: FontWeight.bold)), textDirection: TextDirection.ltr)..layout();
      tp.paint(c, Offset((r - 13) * math.cos(a) - tp.width / 2, (r - 13) * math.sin(a) - tp.height / 2));
    }
    c.restore();
  }
  @override
  bool shouldRepaint(CompassPainter o) => o.bearing != bearing || o.p != p;
}
