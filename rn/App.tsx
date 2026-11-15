/**
 * Órbita Launcher — interfaz React Native, tema Cockpit (igual al de la web y Compose):
 * carretera neón cuya velocidad sigue a la del auto, velocímetro con arco degradado y aguja, brújula,
 * tarjetas Viaje / Batería / GPS / Música, dock a la izquierda y cajón de apps de 6×3 por página.
 * Aguja, arco, carretera y brújula se mueven en el hilo de la interfaz (Reanimated); los datos llegan cada 500 ms
 * desde LauncherData (copia en caché: leer nunca bloquea).
 * Gestos: ← abre el cajón; en el cajón ←/→ cambian de página y → en la primera lo cierra.
 */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { DeviceEventEmitter, FlatList, Image, NativeModules, PanResponder, Pressable, StatusBar, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import Animated, { useSharedValue, useFrameCallback, useAnimatedStyle, useAnimatedProps, withTiming } from 'react-native-reanimated';
import Svg, { Circle, Defs, Line, LinearGradient, Path, Stop, Text as SvgText } from 'react-native-svg';

const L = NativeModules.Launcher;
const MAX = 180, R = 160, ARC_LEN = (R * 270 * Math.PI) / 180;
const ACC2 = '#ff2d95';
const AnimatedPath = Animated.createAnimatedComponent(Path);

type App = { key: string; label: string; icon: string; hidden: boolean; pinned: boolean };
type Pal = { dark: boolean; acc: string; text: string; dim: string; card: string; line: string; bg: string };

function palette(): Pal {
  const dark = L.dark();
  const a = L.pref('accent', '');
  return {
    dark, acc: a && a[0] === '#' ? a : '#00e5ff',
    text: dark ? '#f2f4f8' : '#111216', dim: dark ? 'rgba(242,244,248,.55)' : 'rgba(17,18,22,.55)',
    card: dark ? 'rgba(12,16,28,.72)' : 'rgba(255,255,255,.78)', line: dark ? 'rgba(255,255,255,.08)' : 'rgba(0,0,0,.08)',
    bg: dark ? '#03040a' : '#e7ebf2',
  };
}
function readJson(s: string, def: any) { try { return JSON.parse(s); } catch (e) { return def; } }
function pt(a: number, r: number) { const t = (a * Math.PI) / 180; return [200 + r * Math.cos(t), 200 + r * Math.sin(t)]; }
const [ax, ay] = pt(135, R), [bx, by] = pt(405, R);
const ARC = `M ${ax} ${ay} A ${R} ${R} 0 1 1 ${bx} ${by}`;

// ---------- fondo: carretera ----------
function RoadLine({ i, offset, h, color }: { i: number; offset: Animated.SharedValue<number>; h: number; color: string }) {
  const hz = h * 0.52;
  const st = useAnimatedStyle(() => { const t = (i + offset.value) / 12; return { transform: [{ translateY: hz + (h - hz) * t * t }], opacity: 0.4 * t }; });
  return <Animated.View style={[styles.hline, { backgroundColor: color }, st]} />;
}
function Road({ kmh, p }: { kmh: Animated.SharedValue<number>; p: Pal }) {
  const { width: W, height: H } = useWindowDimensions();
  const offset = useSharedValue(0);
  useFrameCallback((f) => { offset.value = (offset.value + (0.15 + kmh.value / 90) * ((f.timeSincePreviousFrame || 16) / 1000)) % 1; });
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none">
      <Svg width={W} height={H}>
        {Array.from({ length: 15 }, (_, k) => k - 7).map((i) => <Line key={i} x1={W / 2} y1={H * 0.52} x2={W / 2 + (i * W) / 5} y2={H} stroke={p.acc} strokeOpacity={0.2} strokeWidth={2} />)}
        <Line x1={0} y1={H * 0.52} x2={W} y2={H * 0.52} stroke={ACC2} strokeWidth={3} />
      </Svg>
      {Array.from({ length: 12 }, (_, i) => <RoadLine key={i} i={i} offset={offset} h={H} color={ACC2} />)}
    </View>
  );
}

// ---------- velocímetro ----------
const TICKS = (p: Pal) => {
  const out: React.ReactElement[] = [];
  for (let v = 0; v <= MAX; v += 10) {
    const a = 135 + (270 * v) / MAX, major = v % 20 === 0;
    const [x1, y1] = pt(a, 150), [x2, y2] = pt(a, major ? 132 : 140);
    out.push(<Line key={'t' + v} x1={x1} y1={y1} x2={x2} y2={y2} stroke={p.text} strokeOpacity={major ? 0.85 : 0.35} strokeWidth={major ? 3 : 2} />);
    if (major) { const [tx, ty] = pt(a, 112); out.push(<SvgText key={'n' + v} x={tx} y={ty + 5} fill={p.dim} fontSize={15} textAnchor="middle">{v}</SvgText>); }
  }
  return out;
};
function Gauge({ kmh, shown, st, p }: { kmh: Animated.SharedValue<number>; shown: number; st: any; p: Pal }) {
  const ticks = useMemo(() => TICKS(p), [p]);
  const needle = useAnimatedStyle(() => ({ transform: [{ rotate: 270 * Math.min(1, kmh.value / MAX) - 135 + 'deg' }] }));
  const arcProps = useAnimatedProps(() => ({ strokeDashoffset: ARC_LEN * (1 - Math.min(1, kmh.value / MAX)) }));
  const S = 470;
  return (
    <View style={[styles.card, { backgroundColor: p.card, borderColor: p.line, flex: 1.15, alignItems: 'center', justifyContent: 'center' }]}>
      <View style={{ width: S, height: S }}>
        <Svg width={S} height={S} viewBox="0 0 400 400">
          <Defs><LinearGradient id="g" x1="0" y1="1" x2="1" y2="0"><Stop offset="0" stopColor={p.acc} /><Stop offset=".6" stopColor="#7c4dff" /><Stop offset="1" stopColor={ACC2} /></LinearGradient></Defs>
          <Path d={ARC} stroke={p.dark ? 'rgba(255,255,255,.06)' : 'rgba(0,0,0,.07)'} strokeWidth={18} fill="none" />
          <AnimatedPath d={ARC} stroke="url(#g)" strokeWidth={18} fill="none" strokeLinecap="round" strokeDasharray={ARC_LEN} animatedProps={arcProps} />
          {ticks}
        </Svg>
        <Animated.View style={[StyleSheet.absoluteFill, needle]}>
          <Svg width={S} height={S} viewBox="0 0 400 400"><Path d="M196 205 L200 62 L204 205 Z" fill={ACC2} /></Svg>
        </Animated.View>
        <Svg width={S} height={S} viewBox="0 0 400 400" style={StyleSheet.absoluteFill}><Circle cx={200} cy={200} r={13} fill={p.dark ? '#0b0d14' : '#fff'} stroke={p.acc} strokeWidth={3} /></Svg>
        <View style={styles.readout}>
          <Text style={[styles.kmh, { color: p.text }]}>{Math.round(shown)}</Text>
          <Text style={[styles.unit, { color: p.dim }]}>KM/H</Text>
        </View>
      </View>
      <Text style={[styles.sub, { color: p.dim, position: 'absolute', bottom: 16 }]}>{(st.kmh > 2 ? 'En marcha' : 'Estacionado') + (st.heading ? ' · ' + st.heading : '')}</Text>
    </View>
  );
}

// ---------- tarjetas ----------
function Card({ p, style, children }: any) { return <View style={[styles.card, { backgroundColor: p.card, borderColor: p.line }, style]}>{children}</View>; }
function H4({ t, p }: { t: string; p: Pal }) { return <Text style={[styles.h4, { color: p.dim }]}>{t.toUpperCase()}</Text>; }
function Big({ n, u, p }: { n: string; u: string; p: Pal }) { return <Text style={[styles.big, { color: p.text }]}>{n}<Text style={[styles.small, { color: p.dim }]}> {u}</Text></Text>; }
function Bar({ f, c, p }: { f: number; c: string; p: Pal }) {
  return <View style={[styles.bar, { backgroundColor: p.line }]}><View style={[styles.barIn, { width: Math.max(0, Math.min(1, f)) * 100 + '%', backgroundColor: c }]} /></View>;
}
function Compass({ bearing, p }: { bearing: number; p: Pal }) {
  const rot = useSharedValue(0);
  useEffect(() => { rot.value = withTiming(bearing < 0 ? 0 : bearing, { duration: 600 }); }, [bearing, rot]);
  const st = useAnimatedStyle(() => ({ transform: [{ rotate: -rot.value + 'deg' }] }));
  return (
    <View style={[styles.compass, { borderColor: p.acc + '80' }]}>
      <Animated.View style={[{ width: 76, height: 76 }, st]}>
        <Text style={[styles.dir, { top: 2, color: ACC2 }]}>N</Text>
        <Text style={[styles.dir, { top: 28, left: 52, right: 0, color: p.text }]}>E</Text>
        <Text style={[styles.dir, { bottom: 2, color: p.text }]}>S</Text>
        <Text style={[styles.dir, { top: 28, left: 0, right: 52, color: p.text }]}>O</Text>
      </Animated.View>
    </View>
  );
}

// ---------- dock y cajón ----------
function AppIcon({ a, size, label, p }: { a: App; size: number; label?: boolean; p: Pal }) {
  return (
    <Pressable onPress={() => L.launch(a.key)} onLongPress={() => L.appMenu(a.key)} style={{ alignItems: 'center', paddingVertical: 5 }}>
      {/* el ícono real, sin placa ni contorno */}
      <Image source={{ uri: 'file://' + a.icon }} style={{ width: size, height: size }} />
      {label && <Text numberOfLines={1} style={{ color: p.text, fontSize: 14, marginTop: 6, width: size + 50, textAlign: 'center', textShadowColor: 'rgba(0,0,0,.6)', textShadowRadius: 4 }}>{a.label}</Text>}
    </Pressable>
  );
}
function Drawer({ apps, p, onClose }: { apps: App[]; p: Pal; onClose: () => void }) {
  const { width: W } = useWindowDimensions();
  const pages = useMemo(() => { const out: App[][] = []; for (let i = 0; i < apps.length; i += 18) out.push(apps.slice(i, i + 18)); return out.length ? out : [[]]; }, [apps]);
  const [page, setPage] = useState(0);
  const pageRef = useRef(0); pageRef.current = page;
  // → en la primera página cierra (FlatList se encarga de las demás)
  const pan = useMemo(() => PanResponder.create({
    onMoveShouldSetPanResponderCapture: (_, g) => pageRef.current === 0 && g.dx > 40 && Math.abs(g.dx) > Math.abs(g.dy) * 2,
    onPanResponderRelease: (_, g) => { if (g.dx > 120) onClose(); },
  }), [onClose]);
  return (
    <View style={[StyleSheet.absoluteFill, { backgroundColor: p.dark ? 'rgba(5,6,8,.94)' : 'rgba(242,242,247,.94)', paddingTop: 24, paddingBottom: 16 }]} {...pan.panHandlers}>
      <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 28 }}>
        <Text style={{ color: p.text, fontSize: 26, fontWeight: '700', flex: 1 }}>Apps</Text>
        <Pressable onPress={onClose} style={{ backgroundColor: p.card, borderRadius: 14, paddingHorizontal: 20, paddingVertical: 10 }}><Text style={{ color: p.text, fontSize: 16 }}>Cerrar</Text></Pressable>
      </View>
      <FlatList data={pages} horizontal pagingEnabled showsHorizontalScrollIndicator={false} keyExtractor={(_, i) => 'p' + i}
        onMomentumScrollEnd={(e) => setPage(Math.round(e.nativeEvent.contentOffset.x / W))}
        renderItem={({ item }) => (
          <View style={{ width: W, paddingHorizontal: 24, paddingVertical: 12, justifyContent: 'space-evenly' }}>
            {[0, 1, 2].map((r) => (
              <View key={r} style={{ flexDirection: 'row' }}>
                {Array.from({ length: 6 }, (_, c) => item[r * 6 + c]).map((a, c) => (
                  <View key={c} style={{ flex: 1, alignItems: 'center' }}>{a && <AppIcon a={a} size={78} label p={p} />}</View>
                ))}
              </View>
            ))}
          </View>
        )} />
      <View style={{ flexDirection: 'row', justifyContent: 'center' }}>
        {pages.map((_, i) => <View key={i} style={{ width: 9, height: 9, borderRadius: 5, margin: 5, backgroundColor: i === page ? p.acc : p.dim }} />)}
      </View>
    </View>
  );
}

// ================= raíz =================
export default function App() {
  const [p, setP] = useState<Pal>(palette);
  const [st, setSt] = useState<any>(() => readJson(L.state(), {}));
  const [apps, setApps] = useState<App[]>(() => readJson(L.apps(), []));
  const [drawer, setDrawer] = useState(false);
  const [now, setNow] = useState(new Date());
  const [h12, setH12] = useState(() => L.pref('clock', '12') !== '24');
  const kmh = useSharedValue(0);
  const [shown, setShown] = useState(0);

  useEffect(() => {
    const t = setInterval(() => {
      const s = readJson(L.state(), {});
      setSt(s); setNow(new Date());
      kmh.value = withTiming(s.kmh || 0, { duration: 450 });
      setShown(s.kmh || 0);
    }, 500);
    const subs = [
      DeviceEventEmitter.addListener('appsChanged', () => setApps(readJson(L.apps(), []))),
      DeviceEventEmitter.addListener('prefsChanged', () => { setP(palette()); setH12(L.pref('clock', '12') !== '24'); }),
      DeviceEventEmitter.addListener('back', () => setDrawer(false)),
    ];
    return () => { clearInterval(t); subs.forEach((s) => s.remove()); };
  }, [kmh]);

  // ← abre el cajón
  const pan = useMemo(() => PanResponder.create({
    onMoveShouldSetPanResponder: (_, g) => Math.abs(g.dx) > 30 && Math.abs(g.dx) > Math.abs(g.dy) * 2,
    onPanResponderRelease: (_, g) => { if (g.dx < -120) setDrawer(true); },
  }), []);

  const visible = apps.filter((a) => !a.hidden);
  const dock = useMemo(() => {
    const pin = visible.filter((a) => a.pinned);
    return (pin.length ? pin : [...visible].sort((x, y) => x.label.localeCompare(y.label)).slice(0, 5)).slice(0, 6);
  }, [apps]); // eslint-disable-line react-hooks/exhaustive-deps
  const sorted = useMemo(() => [...visible].sort((x, y) => x.label.toLowerCase().localeCompare(y.label.toLowerCase())), [apps]); // eslint-disable-line react-hooks/exhaustive-deps

  const hh = now.getHours(), mm = String(now.getMinutes()).padStart(2, '0');
  const time = h12 ? ((hh + 11) % 12) + 1 + ':' + mm : String(hh).padStart(2, '0') + ':' + mm;
  const days = ['DOMINGO', 'LUNES', 'MARTES', 'MIÉRCOLES', 'JUEVES', 'VIERNES', 'SÁBADO'];
  const months = ['ENERO', 'FEBRERO', 'MARZO', 'ABRIL', 'MAYO', 'JUNIO', 'JULIO', 'AGOSTO', 'SEPTIEMBRE', 'OCTUBRE', 'NOVIEMBRE', 'DICIEMBRE'];
  const v = st.batteryV || -1;
  const bc = v <= 0 ? p.dim : v < 12 ? '#ff453a' : v < 12.4 ? '#ff9f0a' : p.acc;

  return (
    <View style={[styles.root, { backgroundColor: p.bg }]} {...pan.panHandlers}>
      <StatusBar hidden />
      <Road kmh={kmh} p={p} />
      <View style={styles.status}>
        <Text style={{ color: p.text, fontSize: 22, fontWeight: '700' }}>{time}</Text>
        {h12 && <Text style={{ color: p.dim, fontSize: 12 }}> {hh < 12 ? 'AM' : 'PM'}</Text>}
        <Text style={{ color: p.dim, fontSize: 12, marginLeft: 12 }}>{days[now.getDay()]} {now.getDate()} DE {months[now.getMonth()]}</Text>
        <Text style={{ color: p.dim, fontSize: 13, flex: 1, textAlign: 'center' }}>{(st.heading ? 'Rumbo ' + st.heading + ' · ' : '') + Math.round(st.alt || 0) + ' m s. n. m.'}</Text>
        <Text style={{ color: p.dim, fontSize: 12 }}>{(st.wifi ? 'WiFi  ' : '') + (st.bt ? 'BT' : '')}</Text>
      </View>
      <View style={styles.row}>
        <View style={styles.dock}>
          <View style={{ flex: 1 }} />
          {dock.map((a) => <AppIcon key={a.key} a={a} size={58} p={p} />)}
          <View style={{ flex: 1 }} />
          <Pressable onPress={() => L.openSettings()} style={[styles.dbtn, { backgroundColor: p.card, borderColor: p.line }]}><Text style={{ color: p.text, fontSize: 26 }}>⚙</Text></Pressable>
          <Pressable onPress={() => setDrawer(true)} style={[styles.dbtn, { backgroundColor: p.acc, borderColor: p.line, marginTop: 10 }]}><Text style={{ color: '#001018', fontSize: 26 }}>▦</Text></Pressable>
        </View>
        <Gauge kmh={kmh} shown={shown} st={st} p={p} />
        <View style={{ flex: 1, marginLeft: 14 }}>
          <Card p={p} style={{ flex: 1 }}>
            <View style={{ flexDirection: 'row' }}>
              <View style={{ flex: 1 }}>
                <H4 t="Viaje" p={p} />
                <Big n={(st.distKm || 0).toFixed(1)} u="km" p={p} />
                <Text style={[styles.sub, { color: p.dim }]}>{Math.round(st.movingMin || 0) + ' min · prom. ' + Math.round(st.avgKmh || 0) + ' · máx. ' + Math.round(st.maxKmh || 0) + ' km/h'}</Text>
              </View>
              <Compass bearing={st.bearing == null ? -1 : st.bearing} p={p} />
            </View>
          </Card>
          <View style={{ flex: 1, flexDirection: 'row' }}>
            <Card p={p} style={{ flex: 1, marginRight: 14 }}>
              <H4 t="Batería" p={p} />
              <Big n={v > 0 ? v.toFixed(1) : '—'} u="V" p={p} />
              <Bar f={v > 0 ? (v - 11.5) / 3 : 0} c={bc} p={p} />
              <Text style={[styles.sub, { color: p.dim }]}>{v <= 0 ? 'Sin dato' : v >= 13.2 ? 'Cargando' : v < 12 ? 'Baja' : 'Normal'}</Text>
            </Card>
            <Card p={p} style={{ flex: 1 }}>
              <H4 t="GPS" p={p} />
              <Big n={String(st.satsUsed || 0)} u="sats" p={p} />
              <Text style={[styles.sub, { color: p.dim }]}>{(st.satsUsed || 0) + '/' + (st.satsSeen || 0) + ' · ' + Math.round(st.alt || 0) + ' m · ' + (st.heading || '—')}</Text>
            </Card>
          </View>
          <Card p={p} style={{ flex: 1 }}>
            <H4 t="Música" p={p} />
            <View style={{ flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-evenly' }}>
              <Pressable onPress={() => L.media('prev')} style={styles.mbtn}><Text style={{ color: p.text, fontSize: 26 }}>⏮</Text></Pressable>
              <Pressable onPress={() => L.media('play')} style={[styles.mbtn, { width: 64, height: 64, backgroundColor: p.acc }]}><Text style={{ color: '#001018', fontSize: 26 }}>⏯</Text></Pressable>
              <Pressable onPress={() => L.media('next')} style={styles.mbtn}><Text style={{ color: p.text, fontSize: 26 }}>⏭</Text></Pressable>
            </View>
          </Card>
        </View>
      </View>
      {drawer && <Drawer apps={sorted} p={p} onClose={() => setDrawer(false)} />}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  hline: { position: 'absolute', left: 0, right: 0, top: 0, height: 2 },
  status: { height: 48, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 20 },
  row: { flex: 1, flexDirection: 'row', paddingLeft: 12, paddingRight: 16, paddingBottom: 16 },
  dock: { width: 76, alignItems: 'center', paddingVertical: 8, marginRight: 14 },
  dbtn: { width: 58, height: 58, borderRadius: 16, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
  card: { borderRadius: 22, borderWidth: 1, padding: 18, marginBottom: 14 },
  readout: { position: 'absolute', top: 270, left: 0, right: 0, alignItems: 'center' },
  kmh: { fontSize: 80, fontWeight: '700' },
  unit: { fontSize: 14, letterSpacing: 5 },
  compass: { width: 80, height: 80, borderRadius: 40, borderWidth: 2, alignItems: 'center', justifyContent: 'center' },
  dir: { position: 'absolute', left: 0, right: 0, textAlign: 'center', fontSize: 13, fontWeight: '700' },
  h4: { fontSize: 12, letterSpacing: 2, marginBottom: 4 },
  big: { fontSize: 42, fontWeight: '700' },
  small: { fontSize: 17 },
  sub: { fontSize: 14, marginTop: 4 },
  bar: { height: 8, borderRadius: 4, marginTop: 10, overflow: 'hidden' },
  barIn: { height: 8, borderRadius: 4 },
  mbtn: { width: 56, height: 56, borderRadius: 28, alignItems: 'center', justifyContent: 'center' },
});
