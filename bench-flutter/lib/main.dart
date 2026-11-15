// Órbita · Flutter — prueba de rendimiento.
// La misma escena Cockpit que Nativo, Web y React Native: carretera neón, velocímetro con arco degradado y aguja,
// brújula y 4 tarjetas con números vivos. Escena común: aguja = 90·(1 − cos(2πt/10 s)) km/h, rumbo = 12°/s.
import 'dart:math' as math;
import 'dart:ui' show FrameTiming, FramePhase;
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';

const bench = MethodChannel('bench');
const acc = Color(0xFF00E5FF), acc2 = Color(0xFFFF2D95), dim = Color(0x8CFFFFFF);
double kmhAt(int ms) => 90 * (1 - math.cos(2 * math.pi * ms / 10000));

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  runApp(const MaterialApp(debugShowCheckedModeBanner: false, home: Cockpit()));
}

class Cockpit extends StatefulWidget {
  const Cockpit({super.key});
  @override
  State<Cockpit> createState() => _CockpitState();
}

class _CockpitState extends State<Cockpit> with SingleTickerProviderStateMixin {
  late final Ticker ticker;
  final t0 = DateTime.now().millisecondsSinceEpoch;
  int ms = 0, lastMs = 0, testEnd = 0;
  double dist = 0, offset = 0;
  bool hot = false, touchPending = false, running = false;
  String result = '';

  @override
  void initState() {
    super.initState();
    ticker = createTicker(_tick)..start();
    SchedulerBinding.instance.addTimingsCallback(_timings);
    SchedulerBinding.instance.addPostFrameCallback((_) => bench.invokeMethod('firstFrame'));
  }

  // cuadros realmente dibujados (fin del raster), en ms
  void _timings(List<FrameTiming> ts) {
    if (!running) return;
    bench.invokeMethod('frames', ts.map((t) => t.timestampInMicroseconds(FramePhase.rasterFinish) / 1000.0).toList());
  }

  void _tick(Duration _) {
    final now = DateTime.now().millisecondsSinceEpoch;
    setState(() {
      ms = now - t0;
      final v = kmhAt(ms), dt = lastMs == 0 ? 0.016 : (ms - lastMs) / 1000;
      dist += v / 3600 * dt;
      offset = (offset + (0.15 + v / 90) * dt) % 1;
      lastMs = ms;
    });
    if (touchPending) {
      touchPending = false;
      SchedulerBinding.instance.addPostFrameCallback((_) => bench.invokeMethod('touchShown'));
    }
    if (running && now >= testEnd) _finish();
  }

  Future<void> _start() async {
    await bench.invokeMethod('start');
    setState(() { result = ''; running = true; testEnd = DateTime.now().millisecondsSinceEpoch + 30000; });
  }

  Future<void> _finish() async {
    running = false;
    final r = await bench.invokeMethod<String>('finish');
    setState(() => result = r ?? '');
  }

  @override
  void dispose() { ticker.dispose(); super.dispose(); }

  Widget card(Widget child, {double? w}) => Container(
        width: w,
        margin: const EdgeInsets.only(bottom: 14),
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(color: const Color(0xB80C101C), borderRadius: BorderRadius.circular(22),
            border: Border.all(color: hot && w != null ? acc : const Color(0x14FFFFFF), width: hot && w != null ? 2 : 1)),
        child: child);
  Text h4(String s) => Text(s, style: const TextStyle(color: dim, fontSize: 13, letterSpacing: 2));
  Widget big(String n, String u) => Text.rich(TextSpan(text: n, style: const TextStyle(color: Colors.white, fontSize: 44, fontWeight: FontWeight.bold),
      children: [TextSpan(text: ' $u', style: const TextStyle(fontSize: 18, color: dim))]));
  Widget bar(double f, Color c) => Container(
      margin: const EdgeInsets.only(top: 10), height: 8,
      decoration: BoxDecoration(color: const Color(0x1AFFFFFF), borderRadius: BorderRadius.circular(4)),
      child: FractionallySizedBox(alignment: Alignment.centerLeft, widthFactor: f.clamp(0, 1),
          child: Container(decoration: BoxDecoration(color: c, borderRadius: BorderRadius.circular(4)))));

  @override
  Widget build(BuildContext context) {
    final v = kmhAt(ms), s = math.sin(ms / 1500), bearing = (ms * 0.012) % 360;
    final now = DateTime.now();
    final clock = '${(now.hour + 11) % 12 + 1}:${now.minute.toString().padLeft(2, '0')}:${now.second.toString().padLeft(2, '0')} ${now.hour < 12 ? 'AM' : 'PM'}';
    final left = running ? ((testEnd - now.millisecondsSinceEpoch) / 1000).ceil().clamp(0, 30) : 0;
    return Scaffold(
      backgroundColor: const Color(0xFF03040A),
      body: Stack(children: [
        Positioned.fill(child: CustomPaint(painter: RoadPainter(offset))),
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 70, 24, 24),
          child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            GestureDetector(
              onTapDown: (_) => setState(() { hot = !hot; touchPending = true; }),
              child: card(Center(child: SizedBox(width: 520, height: 520, child: Stack(children: [
                Positioned.fill(child: CustomPaint(painter: GaugePainter(v, hot))),
                Positioned(left: 0, right: 0, top: 330, child: Column(children: [
                  Text('${v.round()}', style: TextStyle(color: hot ? acc : Colors.white, fontSize: 86, fontWeight: FontWeight.bold)),
                  const Text('KM/H', style: TextStyle(color: dim, fontSize: 16, letterSpacing: 5)),
                ])),
              ]))), w: 560),
            ),
            const SizedBox(width: 18),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              card(Stack(children: [
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  h4('VIAJE'), big(dist.toStringAsFixed(2), 'km'),
                  Text('En marcha · prom. ${(v * 0.8).round()} · máx. 180', style: const TextStyle(color: dim, fontSize: 15)),
                ]),
                Positioned(right: 0, top: 0, child: SizedBox(width: 84, height: 84, child: CustomPaint(painter: CompassPainter(bearing)))),
              ])),
              Row(children: [
                Expanded(child: card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  h4('BATERÍA'), big((13.6 + 0.3 * s).toStringAsFixed(1), 'V'), bar(0.6 + 0.2 * s, acc)]))),
                const SizedBox(width: 14),
                Expanded(child: card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  h4('GPS'), big('${9 + (2 * math.sin(ms / 4000)).round()}', 'sats'),
                  Text('${(150 + 20 * math.sin(ms / 3000)).round()} m · ${bearing.round()}°', style: const TextStyle(color: dim, fontSize: 15))]))),
              ]),
              card(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                h4('MÚSICA · $clock'),
                const Text('Canción de prueba', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w600)),
                bar((ms / 300 % 100) / 100, acc2)])),
            ])),
          ]),
        ),
        Positioned(top: 14, left: 24, right: 24, child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          const Text('Flutter · Dart + CustomPainter', style: TextStyle(color: Color(0xB3FFFFFF), fontSize: 16)),
          running
              ? Text('Midiendo… $left s · toca el velocímetro varias veces', style: const TextStyle(color: acc, fontSize: 18, fontWeight: FontWeight.w600))
              : GestureDetector(onTap: _start, child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 10),
                  decoration: BoxDecoration(color: acc, borderRadius: BorderRadius.circular(14)),
                  child: Text(result.isEmpty ? 'Iniciar prueba (30 s)' : 'Repetir prueba (30 s)',
                      style: const TextStyle(color: Color(0xFF001018), fontSize: 17, fontWeight: FontWeight.bold)))),
        ])),
        if (result.isNotEmpty)
          Positioned(left: 300, right: 300, top: 160, child: GestureDetector(
            onTap: () => setState(() => result = ''),
            child: Container(padding: const EdgeInsets.all(28),
                decoration: BoxDecoration(color: const Color(0xF5080A12), borderRadius: BorderRadius.circular(22), border: Border.all(color: acc)),
                child: Text('$result\n\nToca para cerrar', style: const TextStyle(color: Colors.white, fontSize: 19, height: 1.4))))),
      ]),
    );
  }
}

class RoadPainter extends CustomPainter {
  final double offset;
  RoadPainter(this.offset);
  @override
  void paint(Canvas c, Size s) {
    final w = s.width, h = s.height, hz = h * 0.52, p = Paint()..strokeWidth = 2;
    p.color = const Color(0x3300E5FF);
    for (var i = -7; i <= 7; i++) { c.drawLine(Offset(w / 2, hz), Offset(w / 2 + i * w / 5, h), p); }
    for (var i = 0; i < 12; i++) {
      final t = (i + offset) / 12, y = hz + (h - hz) * t * t;
      p.color = acc2.withValues(alpha: 0.4 * t);
      c.drawLine(Offset(0, y), Offset(w, y), p);
    }
    c.drawLine(Offset(0, hz), Offset(w, hz), Paint()..color = acc2..strokeWidth = 3);
  }
  @override
  bool shouldRepaint(RoadPainter o) => o.offset != offset;
}

class GaugePainter extends CustomPainter {
  final double kmh;
  final bool hot;
  GaugePainter(this.kmh, this.hot);
  static const max = 180.0;
  @override
  void paint(Canvas c, Size s) {
    final k = s.width / 400;
    c.scale(k);
    const ctr = Offset(200, 200);
    final rect = Rect.fromCircle(center: ctr, radius: 160), f = (kmh / max).clamp(0.0, 1.0);
    double rad(double a) => a * math.pi / 180;
    c.drawArc(rect, rad(135), rad(270), false, Paint()..style = PaintingStyle.stroke..strokeWidth = 18..color = const Color(0x0FFFFFFF));
    c.drawArc(rect, rad(135), rad(270 * f), false, Paint()
      ..style = PaintingStyle.stroke..strokeWidth = 18..strokeCap = StrokeCap.round
      ..shader = const LinearGradient(begin: Alignment.bottomLeft, end: Alignment.topRight, colors: [acc, Color(0xFF7C4DFF), acc2]).createShader(rect));
    Offset pt(double a, double r) => ctr + Offset(r * math.cos(rad(a)), r * math.sin(rad(a)));
    for (var v = 0; v <= max; v += 10) {
      final a = 135 + 270 * v / max, major = v % 20 == 0;
      c.drawLine(pt(a, 150), pt(a, major ? 132 : 140), Paint()..strokeWidth = major ? 3 : 2..color = major ? const Color(0xD9FFFFFF) : const Color(0x59FFFFFF));
      if (major) {
        final tp = TextPainter(text: TextSpan(text: '$v', style: const TextStyle(color: Color(0xB3FFFFFF), fontSize: 15)), textDirection: TextDirection.ltr)..layout();
        tp.paint(c, pt(a, 112) - Offset(tp.width / 2, tp.height / 2));
      }
    }
    c.save();
    c.translate(200, 200);
    c.rotate(rad(270 * f - 135));
    c.drawPath(Path()..moveTo(-4, 5)..lineTo(0, -138)..lineTo(4, 5)..close(), Paint()..color = hot ? acc : acc2);
    c.restore();
    c.drawCircle(ctr, 13, Paint()..color = const Color(0xFF0B0D14));
    c.drawCircle(ctr, 13, Paint()..style = PaintingStyle.stroke..strokeWidth = 3..color = acc);
  }
  @override
  bool shouldRepaint(GaugePainter o) => o.kmh != kmh || o.hot != hot;
}

class CompassPainter extends CustomPainter {
  final double bearing;
  CompassPainter(this.bearing);
  @override
  void paint(Canvas c, Size s) {
    final ctr = s.center(Offset.zero), r = s.width / 2 - 2;
    c.drawCircle(ctr, r, Paint()..style = PaintingStyle.stroke..strokeWidth = 2..color = const Color(0x8000E5FF));
    c.save();
    c.translate(ctr.dx, ctr.dy);
    c.rotate(-bearing * math.pi / 180);
    const dirs = ['N', 'E', 'S', 'O'];
    for (var i = 0; i < 4; i++) {
      final a = i * math.pi / 2 - math.pi / 2;
      final tp = TextPainter(text: TextSpan(text: dirs[i], style: TextStyle(color: i == 0 ? acc2 : Colors.white, fontSize: 14, fontWeight: FontWeight.bold)), textDirection: TextDirection.ltr)..layout();
      tp.paint(c, Offset((r - 14) * math.cos(a) - tp.width / 2, (r - 14) * math.sin(a) - tp.height / 2));
    }
    c.restore();
  }
  @override
  bool shouldRepaint(CompassPainter o) => o.bearing != bearing;
}
