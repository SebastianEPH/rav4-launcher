/**
 * Órbita · React Native — prueba de rendimiento.
 * La misma escena Cockpit que el launcher (Nativo y Web) y la versión Flutter:
 * carretera neón, velocímetro con arco degradado y aguja, brújula y 4 tarjetas con números vivos.
 * Escena común: aguja = 90·(1 − cos(2πt/10 s)) km/h (0→180→0 cada 10 s), rumbo = 12°/s.
 *
 * Para que la prueba sea justa con RN se usa su forma "rápida": aguja, arco, carretera y brújula se mueven en el
 * hilo de la interfaz con Reanimated (sin pasar por JS). Los números y las tarjetas sí pasan por JS (setState en cada
 * cuadro), como lo haría un launcher real.
 */
import React, { useEffect, useRef, useState } from 'react';
import { NativeModules, StatusBar, StyleSheet, Text, View, Pressable } from 'react-native';
import Animated, { useSharedValue, useFrameCallback, useAnimatedStyle, useAnimatedProps } from 'react-native-reanimated';
import Svg, { Circle, Defs, G, Line, LinearGradient, Path, Stop, Text as SvgText } from 'react-native-svg';

const Bench = NativeModules.Bench;
const MAX = 180, R = 160, ARC_LEN = (R * 270 * Math.PI) / 180;
const ACC = '#00e5ff', ACC2 = '#ff2d95';
const AnimatedPath = Animated.createAnimatedComponent(Path);

function kmhAt(ms: number) { 'worklet'; return 90 * (1 - Math.cos((2 * Math.PI * ms) / 10000)); }
function pt(a: number, r: number) { const t = (a * Math.PI) / 180; return [200 + r * Math.cos(t), 200 + r * Math.sin(t)]; }

// partes fijas del velocímetro (se arman una sola vez)
const TICKS: React.ReactElement[] = [];
for (let v = 0; v <= MAX; v += 10) {
  const a = 135 + (270 * v) / MAX, major = v % 20 === 0;
  const [x1, y1] = pt(a, 150), [x2, y2] = pt(a, major ? 132 : 140);
  TICKS.push(<Line key={'t' + v} x1={x1} y1={y1} x2={x2} y2={y2} stroke={major ? 'rgba(255,255,255,.85)' : 'rgba(255,255,255,.35)'} strokeWidth={major ? 3 : 2} />);
  if (major) { const [tx, ty] = pt(a, 112); TICKS.push(<SvgText key={'n' + v} x={tx} y={ty + 5} fill="rgba(255,255,255,.7)" fontSize={15} textAnchor="middle">{v}</SvgText>); }
}
const [ax, ay] = pt(135, R), [bx, by] = pt(405, R);
const ARC = `M ${ax} ${ay} A ${R} ${R} 0 1 1 ${bx} ${by}`;

function RoadLine({ i, offset, h }: { i: number; offset: Animated.SharedValue<number>; h: number }) {
  const horizon = h * 0.52;
  const st = useAnimatedStyle(() => {
    const t = (i + offset.value) / 12;
    return { transform: [{ translateY: horizon + (h - horizon) * t * t }], opacity: 0.4 * t };
  });
  return <Animated.View style={[styles.hline, st]} />;
}

export default function App() {
  const t0 = useRef(Date.now()).current;
  const kmh = useSharedValue(0), offset = useSharedValue(0), bearing = useSharedValue(0);
  const [, setTick] = useState(0);
  const [hot, setHot] = useState(false);
  const [test, setTest] = useState<'idle' | 'run' | 'done'>('idle');
  const [result, setResult] = useState('');
  const dist = useRef(0), touchPending = useRef(false);
  const js = useRef({ on: false, frames: 0, slow: 0, last: 0, t0: 0 });
  const H = 800;

  // hilo de la interfaz: aguja, arco, carretera, brújula
  useFrameCallback((f) => {
    const ms = Date.now() - t0;
    kmh.value = kmhAt(ms);
    offset.value = (offset.value + (0.15 + kmh.value / 90) * ((f.timeSincePreviousFrame || 16) / 1000)) % 1;
    bearing.value = (ms * 0.012) % 360;
  });
  const needle = useAnimatedStyle(() => ({ transform: [{ rotate: (270 * Math.min(1, kmh.value / MAX) - 135) + 'deg' }] }));
  const arcProps = useAnimatedProps(() => ({ strokeDashoffset: ARC_LEN * (1 - Math.min(1, kmh.value / MAX)) }));
  const rose = useAnimatedStyle(() => ({ transform: [{ rotate: -bearing.value + 'deg' }] }));

  // hilo de JS: números y tarjetas en cada cuadro (como un launcher real)
  useEffect(() => {
    let raf = 0, last = 0;
    const loop = (now: number) => {
      if (last) dist.current += (kmhAt(Date.now() - t0) / 3600) * ((now - last) / 1000);
      const j = js.current;
      if (j.on) { if (j.last) { j.frames++; if (now - j.last > 34) j.slow++; } j.last = now; }
      last = now;
      setTick((x) => x + 1);
      if (touchPending.current) { touchPending.current = false; requestAnimationFrame(() => Bench.touchShown()); }
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    requestAnimationFrame(() => Bench.firstFrame());
    return () => cancelAnimationFrame(raf);
  }, [t0]);

  const startTest = () => {
    setTest('run'); setResult('');
    js.current = { on: true, frames: 0, slow: 0, last: 0, t0: Date.now() };
    Bench.start();
    setTimeout(async () => {
      const j = js.current; j.on = false;
      const fps = (j.frames * 1000) / Math.max(1, Date.now() - j.t0);
      setResult(await Bench.finish(fps, j.slow)); setTest('done');
    }, 30000);
  };

  const ms = Date.now() - t0, v = kmhAt(ms);
  const left = test === 'run' ? Math.max(0, 30 - Math.floor((Date.now() - js.current.t0) / 1000)) : 0;
  const now = new Date();
  const clock = ((now.getHours() + 11) % 12) + 1 + ':' + String(now.getMinutes()).padStart(2, '0') + ':' + String(now.getSeconds()).padStart(2, '0') + (now.getHours() < 12 ? ' AM' : ' PM');

  return (
    <View style={styles.root}>
      <StatusBar hidden />
      {/* carretera */}
      <View style={StyleSheet.absoluteFill}>
        <Svg width="100%" height="100%">
          {Array.from({ length: 15 }, (_, k) => k - 7).map((i) => <Line key={i} x1={640} y1={H * 0.52} x2={640 + i * 256} y2={H} stroke="rgba(0,229,255,.2)" strokeWidth={2} />)}
          <Line x1={0} y1={H * 0.52} x2={1280} y2={H * 0.52} stroke={ACC2} strokeWidth={3} />
        </Svg>
        {Array.from({ length: 12 }, (_, i) => <RoadLine key={i} i={i} offset={offset} h={H} />)}
      </View>

      <View style={styles.row}>
        {/* velocímetro */}
        <View style={[styles.card, styles.gauge]} onTouchStart={() => { setHot((x) => !x); touchPending.current = true; }}>
          <Svg width={520} height={520} viewBox="0 0 400 400">
            <Defs><LinearGradient id="g" x1="0" y1="1" x2="1" y2="0"><Stop offset="0" stopColor={ACC} /><Stop offset=".6" stopColor="#7c4dff" /><Stop offset="1" stopColor={ACC2} /></LinearGradient></Defs>
            <Path d={ARC} stroke="rgba(255,255,255,.06)" strokeWidth={18} fill="none" />
            <AnimatedPath d={ARC} stroke="url(#g)" strokeWidth={18} fill="none" strokeLinecap="round" strokeDasharray={ARC_LEN} animatedProps={arcProps} />
            {TICKS}
          </Svg>
          <Animated.View style={[styles.needleBox, needle]} pointerEvents="none">
            <Svg width={520} height={520} viewBox="0 0 400 400"><G><Path d="M196 205 L200 62 L204 205 Z" fill={hot ? ACC : ACC2} /></G><Circle cx={200} cy={200} r={13} fill="#0b0d14" stroke={ACC} strokeWidth={3} /></Svg>
          </Animated.View>
          <View style={styles.readout} pointerEvents="none">
            <Text style={[styles.kmh, hot && { color: ACC }]}>{Math.round(v)}</Text>
            <Text style={styles.unit}>KM/H</Text>
          </View>
        </View>

        {/* tarjetas */}
        <View style={styles.side}>
          <View style={styles.card}>
            <View style={styles.compass}>
              <Animated.View style={[styles.rose, rose]}>
                <Text style={[styles.dir, { top: 2, color: ACC2 }]}>N</Text><Text style={[styles.dir, { left: 56, top: 30 }]}>E</Text>
                <Text style={[styles.dir, { bottom: 2 }]}>S</Text><Text style={[styles.dir, { right: 56, top: 30 }]}>O</Text>
              </Animated.View>
            </View>
            <Text style={styles.h4}>VIAJE</Text>
            <Text style={styles.big}>{dist.current.toFixed(2)}<Text style={styles.small}> km</Text></Text>
            <Text style={styles.sub}>En marcha · prom. {Math.round(v * 0.8)} · máx. 180</Text>
          </View>
          <View style={styles.two}>
            <View style={[styles.card, { flex: 1, marginRight: 14 }]}>
              <Text style={styles.h4}>BATERÍA</Text>
              <Text style={styles.big}>{(13.6 + 0.3 * Math.sin(ms / 1500)).toFixed(1)}<Text style={styles.small}> V</Text></Text>
              <View style={styles.bar}><View style={[styles.barIn, { width: (60 + 20 * Math.sin(ms / 1500)) + '%' }]} /></View>
            </View>
            <View style={[styles.card, { flex: 1 }]}>
              <Text style={styles.h4}>GPS</Text>
              <Text style={styles.big}>{9 + Math.round(2 * Math.sin(ms / 4000))}<Text style={styles.small}> sats</Text></Text>
              <Text style={styles.sub}>{Math.round(150 + 20 * Math.sin(ms / 3000))} m · {Math.round((ms * 0.012) % 360)}°</Text>
            </View>
          </View>
          <View style={styles.card}>
            <Text style={styles.h4}>MÚSICA · {clock}</Text>
            <Text style={styles.song}>Canción de prueba</Text>
            <View style={styles.bar}><View style={[styles.barIn, { width: ((ms / 300) % 100) + '%', backgroundColor: ACC2 }]} /></View>
          </View>
        </View>
      </View>

      {/* control de la prueba */}
      <View style={styles.top}>
        <Text style={styles.title}>React Native · Hermes · Reanimated</Text>
        {test === 'run'
          ? <Text style={styles.count}>Midiendo… {left} s · toca el velocímetro varias veces</Text>
          : <Pressable style={styles.btn} onPress={startTest}><Text style={styles.btnT}>{test === 'done' ? 'Repetir prueba (30 s)' : 'Iniciar prueba (30 s)'}</Text></Pressable>}
      </View>
      {test === 'done' && (
        <Pressable style={styles.result} onPress={() => setTest('idle')}>
          <Text style={styles.resultT}>{result}</Text>
          <Text style={styles.sub}>Toca para cerrar</Text>
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#03040a' },
  hline: { position: 'absolute', left: 0, right: 0, top: 0, height: 2, backgroundColor: ACC2 },
  row: { flex: 1, flexDirection: 'row', padding: 24, paddingTop: 70 },
  card: { backgroundColor: 'rgba(12,16,28,.72)', borderRadius: 22, borderWidth: 1, borderColor: 'rgba(255,255,255,.08)', padding: 18, marginBottom: 14 },
  gauge: { width: 560, alignItems: 'center', justifyContent: 'center', marginRight: 18 },
  needleBox: { position: 'absolute', width: 520, height: 520 },
  readout: { position: 'absolute', top: 330, left: 0, right: 0, alignItems: 'center' },
  kmh: { color: '#fff', fontSize: 86, fontWeight: '700' },
  unit: { color: 'rgba(255,255,255,.55)', fontSize: 16, letterSpacing: 5 },
  side: { flex: 1 },
  two: { flexDirection: 'row' },
  compass: { position: 'absolute', right: 18, top: 18, width: 84, height: 84, borderRadius: 42, borderWidth: 2, borderColor: 'rgba(0,229,255,.5)' },
  rose: { width: 80, height: 80 },
  dir: { position: 'absolute', left: 0, right: 0, textAlign: 'center', color: '#fff', fontSize: 14, fontWeight: '700' },
  h4: { color: 'rgba(255,255,255,.55)', fontSize: 13, letterSpacing: 2, marginBottom: 6 },
  big: { color: '#fff', fontSize: 44, fontWeight: '700' },
  small: { fontSize: 18, color: 'rgba(255,255,255,.55)' },
  sub: { color: 'rgba(255,255,255,.55)', fontSize: 15, marginTop: 4 },
  song: { color: '#fff', fontSize: 22, fontWeight: '600', marginBottom: 10 },
  bar: { height: 8, borderRadius: 4, backgroundColor: 'rgba(255,255,255,.1)', marginTop: 10, overflow: 'hidden' },
  barIn: { height: 8, borderRadius: 4, backgroundColor: ACC },
  top: { position: 'absolute', top: 14, left: 24, right: 24, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  title: { color: 'rgba(255,255,255,.7)', fontSize: 16 },
  count: { color: ACC, fontSize: 18, fontWeight: '600' },
  btn: { backgroundColor: ACC, borderRadius: 14, paddingHorizontal: 22, paddingVertical: 10 },
  btnT: { color: '#001018', fontSize: 17, fontWeight: '700' },
  result: { position: 'absolute', left: 300, right: 300, top: 160, backgroundColor: 'rgba(8,10,18,.96)', borderRadius: 22, padding: 28, borderWidth: 1, borderColor: ACC },
  resultT: { color: '#fff', fontSize: 19, lineHeight: 28 },
});
