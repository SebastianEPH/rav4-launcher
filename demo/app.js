/* RAV4 Launcher — interfaz web.
   En la radio, window.Radio es el puente nativo (WebHome.java). En un navegador se usa un simulador.
   Sintaxis conservadora (ES2015, sin ?. ni ??) para el WebView de Android 10. */
(function () {
  'use strict';

  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var body = document.body;
  var params = {};
  location.search.replace(/[?&]([^=]+)=([^&]*)/g, function (_, k, v) { params[k] = decodeURIComponent(v); });
  var R = window.Radio || makeSim();
  var SIM = !window.Radio;

  // ---------- estado ----------
  var S = {};                    // último estado de la radio
  var shownKmh = 0;              // velocidad mostrada (suavizada)
  var hist = [];                 // historial de velocidad (1 muestra/seg)
  var apps = [];
  var showHiddenApps = false;
  var prefs = {
    theme: params.theme || R.pref('theme', 'cockpit'),
    mode: params.mode || R.pref('mode', 'dark'),
    fx: params.fx || (R.pref('fxv2', '') ? R.pref('fx', 'low') : 'low'),
    icons: 'plain',
    accent: params.accent || R.pref('accent', 'theme'),
    clock: R.pref('clock', '12'),
    darkFrom: R.pref('darkFrom', '18'),
    darkTo: R.pref('darkTo', '6')
  };
  var ACCENTS = { blue: '#0a84ff', cyan: '#5ac8fa', green: '#30d158', orange: '#ff9f0a', pink: '#ff375f', purple: '#bf5af2', red: '#ff453a' };
  // valor de una opción del tema activo (guardada como "t.<tema>.<clave>")
  function opt(k) {
    var key = 't.' + prefs.theme + '.' + k;
    if (!(key in prefs)) {
      var d = '';
      (THEMES[prefs.theme].opts || []).forEach(function (o) { if (o.k === k) d = o.def; });
      prefs[key] = params[k] || R.pref(key, d);
    }
    return prefs[key];
  }
  function hexA(hex, a) { var n = parseInt(hex.slice(1), 16); return 'rgba(' + (n >> 16) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')'; }
  function hhmm(d) { return prefs.clock === '24' ? pad(d.getHours()) + ':' + pad(d.getMinutes()) : (d.getHours() % 12 || 12) + ':' + pad(d.getMinutes()); }
  function ampm(d) { return prefs.clock === '24' ? '' : (d.getHours() < 12 ? 'AM' : 'PM'); }
  var current = null;            // tema montado
  // Al arrancar, las animaciones van apagadas (son lo que pone lenta la radio); se reactivan al asentarse.
  var booting = true;
  function effFx() { return booting ? 'off' : prefs.fx; }
  // Congela animaciones mientras tocas (el cajón abre fluido); se reanudan al soltar sin actividad.
  var freezeT;
  function freeze() { body.classList.add('freeze'); clearTimeout(freezeT); }
  function unfreezeSoon() { clearTimeout(freezeT); freezeT = setTimeout(function () { if (!$('.sheet.open')) body.classList.remove('freeze'); }, 650); }
  function enableFx() {
    if (!booting) return;
    booting = false;
    applyLook();
    initParticles();
  }
  if (params.shot) document.documentElement.classList.add('shot');

  // ---------- utilidades ----------
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function setK(k, v) {
    var els = document.querySelectorAll('[data-k="' + k + '"]');
    for (var i = 0; i < els.length; i++) if (els[i].textContent !== String(v)) els[i].textContent = v;
  }
  function num(v, d) { return v == null || v < 0 || isNaN(v) ? '—' : Number(v).toFixed(d || 0); }
  function fmtMin(m) { m = Math.round(m || 0); return m >= 60 ? Math.floor(m / 60) + 'h ' + pad(m % 60) + 'm' : m + ' min'; }
  function hourNow() { var d = new Date(); return d.getHours() + d.getMinutes() / 60; }
  var DAYS = ['domingo', 'lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado'];
  var MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];
  function dateLong(d) { return DAYS[d.getDay()] + ', ' + d.getDate() + ' de ' + MONTHS[d.getMonth()]; }
  function greet() { var h = new Date().getHours(); return h < 6 ? 'Buenas noches' : h < 12 ? 'Buenos días' : h < 19 ? 'Buenas tardes' : 'Buenas noches'; }
  function battClass() { var v = S.batteryV; return v == null || v < 0 ? '' : v >= 13.3 ? 'chg' : v < 12.0 ? 'low' : ''; }
  function battText() { var v = S.batteryV; if (v == null || v < 0) return 'Sin dato'; return v >= 13.3 ? 'Cargando' : v >= 12.4 ? 'Bien' : v >= 12.0 ? 'Baja' : 'Muy baja'; }
  function battPct() { var v = S.batteryV; return v == null || v < 0 ? 0 : Math.max(0, Math.min(100, (v - 11.6) / (14.4 - 11.6) * 100)); }

  var toastT;
  function toast(msg) {
    var t = $('#toast');
    t.textContent = msg; t.classList.add('on');
    clearTimeout(toastT); toastT = setTimeout(function () { t.classList.remove('on'); }, 2600);
  }

  function scale() { document.documentElement.style.fontSize = (16 * Math.min(innerWidth / 1280, innerHeight / 800)) + 'px'; }

  function resolvedMode() {
    if (prefs.mode !== 'auto') return prefs.mode;
    var h = new Date().getHours(), from = +prefs.darkFrom, to = +prefs.darkTo;
    var dark = from > to ? (h >= from || h < to) : (h >= from && h < to);
    return dark ? 'dark' : 'light';
  }

  // íconos SVG reutilizables
  var IC = {
    prev: '<svg viewBox="0 0 24 24"><path d="M6 6h2v12H6zm3.5 6 8.5 6V6z"/></svg>',
    play: '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',
    next: '<svg viewBox="0 0 24 24"><path d="M16 6h2v12h-2zM6 18l8.5-6L6 6z"/></svg>'
  };
  function mediaHtml() {
    return '<div class="media"><button data-media="prev">' + IC.prev + '</button><button class="pp" data-media="play">' + IC.play +
      '</button><button data-media="next">' + IC.next + '</button></div>';
  }

  // ================= TEMAS =================
  var THEMES = {};

  // ---------- Terminal ----------
  var CMDS = ['gps --watch', 'can --battery', 'trip --stats', 'uptime', 'top -n 1', 'nwd volume --get'];
  var termLog = [], termCmd = { i: 0, c: 0, t: 0 }, lastEv = {};
  function logEv(cls, txt) {
    var d = new Date();
    termLog.push({ t: pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()), c: cls, x: txt });
    if (termLog.length > 40) termLog.shift();
    var el = $('#tlog');
    if (el) {
      var e = termLog[termLog.length - 1], div = document.createElement('div');
      div.innerHTML = '<span class="t">[' + e.t + ']</span> <span class="' + e.c + '">' + esc(e.x) + '</span>';
      el.appendChild(div);
      while (el.children.length > 22) el.removeChild(el.firstChild);
    }
  }
  function watchEvents() {
    if (lastEv.fix !== S.fix) { if (lastEv.fix !== undefined || S.fix) logEv(S.fix ? 'ok' : 'wr', S.fix ? 'gnss: fix ok (' + S.satsUsed + ' sats)' : 'gnss: señal perdida'); lastEv.fix = S.fix; }
    if (lastEv.wifi !== S.wifi) { logEv(S.wifi ? 'ok' : 'wr', 'wlan0: ' + (S.wifi ? 'conectado' : 'sin conexión')); lastEv.wifi = S.wifi; }
    if (lastEv.bt !== S.bt) { logEv('in', 'bt: ' + (S.bt ? 'encendido' : 'apagado')); lastEv.bt = S.bt; }
    if (S.batteryV > 0 && Math.abs((lastEv.batt || 0) - S.batteryV) >= 0.2) { logEv(S.batteryV < 12 ? 'wr' : 'in', 'can: batería ' + S.batteryV.toFixed(1) + ' V'); lastEv.batt = S.batteryV; }
    if (lastEv.vol !== undefined && lastEv.vol !== S.vol) logEv('in', 'nwd: volumen ' + S.vol + '/' + S.volMax);
    lastEv.vol = S.vol;
    var fast = S.kmh >= 100;
    if (lastEv.fast !== fast) { if (fast) logEv('wr', 'trip: velocidad alta ' + Math.round(S.kmh) + ' km/h'); lastEv.fast = fast; }
  }
  THEMES.terminal = {
    name: 'Terminal', pv: ['#07090d', '#3cff8f', '#58a6ff'],
    html: function () {
      return '<div class="screen term stagger">' +
        '<div class="prompt glow">rav4@k4811:~$ <span class="cmd" id="tcmd"></span><span class="cursor"></span></div>' +
        '<div class="card speed"><h4>speed.gps</h4><div><span class="big glow" data-k="kmhInt">0</span><span class="unit">km/h</span></div>' +
        '<div class="sub">max <b data-k="maxKmh">0</b> · prom <b data-k="avgKmh">0</b> km/h</div><canvas id="spark"></canvas></div>' +
        '<div class="card"><h4>trip + gnss</h4>' +
        kv('distancia', 'dist') + kv('en movimiento', 'moving') + kv('satélites', 'sats') + kv('rumbo', 'heading') +
        kv('altitud', 'alt') + kv('precisión', 'acc') + kv('lat', 'lat') + kv('lon', 'lon') +
        '<h4 style="margin-top:1rem">car.can</h4>' + kv('batería', 'battV') + kv('estado', 'battState') + kv('cpu', 'cpuTemp') + kv('volumen', 'volTxt') +
        '</div>' +
        '<div class="card"><h4>journalctl -f</h4><div class="log" id="tlog"></div></div>' +
        '</div>';
      function kv(l, k) { return '<div class="kv"><span>' + l + '</span><b data-k="' + k + '">—</b></div>'; }
    },
    mount: function () {
      var el = $('#tlog');
      termLog.slice(-22).forEach(function (e) {
        var div = document.createElement('div');
        div.innerHTML = '<span class="t">[' + e.t + ']</span> <span class="' + e.c + '">' + esc(e.x) + '</span>';
        el.appendChild(div);
      });
      if (!termLog.length) { logEv('ok', 'boot: rav4-launcher web ui'); logEv('in', 'radio: NWD K4811 · ' + R.version()); }
      termCmd = { i: 0, c: 0, t: 0 };
    },
    frame: function (dt) {
      termCmd.t += dt;
      var cmd = CMDS[termCmd.i % CMDS.length];
      if (termCmd.c < cmd.length && termCmd.t > 0.07) { termCmd.c++; termCmd.t = 0; $('#tcmd').textContent = cmd.slice(0, termCmd.c); }
      else if (termCmd.c >= cmd.length && termCmd.t > 6) { termCmd.i++; termCmd.c = 0; termCmd.t = 0; }
      drawSpark($('#spark'), getComputedStyle(body).getPropertyValue('--acc').trim());
    }
  };

  // ---------- Glass ----------
  var RING_R = 150, RING_C = 2 * Math.PI * RING_R;
  THEMES.glass = {
    name: 'Glass', pv: ['#2a5bff', '#b44bff', '#00c2b8'],
    html: function () {
      return '<div class="screen glass stagger">' +
        '<div class="card clock shine"><div><h4>Hoy</h4><div class="hh"><span data-k="time">--:--</span><small class="ampm" data-k="ampm"></small></div><div class="dd" data-k="dateLong"></div></div>' +
        '<div><div class="sub">Batería del auto</div><div class="big"><span data-k="battV">—</span><span class="unit">V</span></div><div class="bar"><i data-w="batt"></i></div>' +
        '<div class="sub" style="margin-top:1rem">GPS · <span data-k="satsTxt">—</span></div></div></div>' +
        '<div class="card ringcard shine"><div class="ring"><svg viewBox="0 0 340 340"><defs><linearGradient id="ringGrad" x1="0" y1="0" x2="1" y2="1">' +
        '<stop offset="0" stop-color="#64d2ff"/><stop offset="1" stop-color="#bf5af2"/></linearGradient></defs>' +
        '<circle class="trk" cx="170" cy="170" r="' + RING_R + '" stroke-dasharray="' + (RING_C * 0.75) + ' ' + RING_C + '"/>' +
        '<circle class="val" id="ringVal" cx="170" cy="170" r="' + RING_R + '" stroke-dasharray="0 ' + RING_C + '"/></svg>' +
        '<div class="center"><b data-k="kmhInt">0</b><span>km/h</span></div></div><div class="sub" data-k="gpsLine"></div></div>' +
        '<div class="card tap shine"><h4>Viaje</h4><div class="big"><span data-k="dist">0.0</span><span class="unit">km</span></div>' +
        '<div class="sub"><span data-k="moving">0 min</span> en movimiento</div><div class="sub">Prom. <b data-k="avgKmh">0</b> · Máx. <b data-k="maxKmh">0</b> km/h</div></div>' +
        '<div class="card shine"><h4>Música</h4><div class="sub">Controla la app que esté sonando</div>' + mediaHtml() + '</div>' +
        '</div>';
    },
    frame: function () {
      var f = Math.min(1, shownKmh / 180);
      var el = $('#ringVal');
      if (el) el.setAttribute('stroke-dasharray', (RING_C * 0.75 * f).toFixed(1) + ' ' + RING_C);
    }
  };

  // ---------- Dusk ----------
  var SKIES = [
    { h: 0, c: ['#0b1026', '#1b1f4a', '#2a1c48'], stars: 1 },
    { h: 5, c: ['#2c2a5a', '#7b4f8e', '#f2a07b'], stars: .5 },
    { h: 7, c: ['#ffc3a0', '#ffafbd', '#b9d4f5'], stars: 0 },
    { h: 10, c: ['#3f8fe8', '#78b6f0', '#cfe6ff'], stars: 0 },
    { h: 16.5, c: ['#4f86d9', '#f6b38e', '#f08a8a'], stars: 0 },
    { h: 18.3, c: ['#e58fa6', '#8e5c9e', '#231a3a'], stars: .3 },
    { h: 20, c: ['#151a3d', '#2a2050', '#3a2050'], stars: 1 },
    { h: 24, c: ['#0b1026', '#1b1f4a', '#2a1c48'], stars: 1 }
  ];
  function paintSky() {
    var so = prefs.theme === 'dusk' ? opt('sky') : 'hour';
    var h = so === 'day' ? 12 : so === 'sunset' ? 18.4 : so === 'night' ? 23 : hourNow(), a = SKIES[0], b = SKIES[1];
    for (var i = 0; i < SKIES.length - 1; i++) if (h >= SKIES[i].h && h < SKIES[i + 1].h) { a = SKIES[i]; b = SKIES[i + 1]; }
    var f = (h - a.h) / (b.h - a.h), st = body.style;
    for (var j = 0; j < 3; j++) st.setProperty('--sky' + (j + 1), mix(a.c[j], b.c[j], f));
    st.setProperty('--starsOp', String(a.stars + (b.stars - a.stars) * f));
    var orb = $('#orb'), day = h >= 6 && h < 18.5;
    var t = day ? (h - 6) / 12.5 : ((h >= 18.5 ? h - 18.5 : h + 5.5) / 11.5);
    orb.classList.toggle('moon', !day);
    orb.style.left = (8 + 78 * t) + '%';
    orb.style.top = (48 - 36 * Math.sin(Math.PI * t)) + '%';
  }
  function mix(c1, c2, f) {
    var a = parseInt(c1.slice(1), 16), b = parseInt(c2.slice(1), 16), out = '#';
    for (var s = 16; s >= 0; s -= 8) { var v = Math.round(((a >> s) & 255) + ((((b >> s) & 255) - ((a >> s) & 255)) * f)); out += (v < 16 ? '0' : '') + v.toString(16); }
    return out;
  }
  THEMES.dusk = {
    name: 'Dusk', pv: ['#e58fa6', '#8e5c9e', '#231a3a'],
    html: function () {
      return '<div class="screen dusk"><div class="hero"><div class="hh"><span data-k="time">--:--</span><small class="ampm" data-k="ampm"></small></div><div class="dd" data-k="dateLong"></div><div class="greet" data-k="greet"></div></div>' +
        '<div class="row stagger">' +
        '<div class="card shine"><h4>Velocidad</h4><div class="big"><span data-k="kmhInt">0</span><span class="unit">km/h</span></div><div class="sub" data-k="gpsLine"></div></div>' +
        '<div class="card shine"><h4>Viaje</h4><div class="big"><span data-k="dist">0.0</span><span class="unit">km</span></div><div class="sub">Máx. <b data-k="maxKmh">0</b> km/h · <span data-k="moving"></span></div></div>' +
        '<div class="card shine battery-v"><h4>Batería</h4><div class="big"><span data-k="battV">—</span><span class="unit">V</span></div><div class="sub" data-k="battState"></div></div>' +
        '<div class="card shine"><h4>Música</h4>' + mediaHtml() + '</div>' +
        '</div></div>';
    },
    mount: paintSky
  };

  // ---------- Cockpit ----------
  var G = { cx: 200, cy: 200, r: 170, max: 180 };
  function pt(a, r) { var rad = a * Math.PI / 180; return (G.cx + r * Math.cos(rad)).toFixed(1) + ' ' + (G.cy + r * Math.sin(rad)).toFixed(1); }
  var ARC_LEN = 0;
  THEMES.cockpit = {
    name: 'Cockpit', pv: ['#030406', '#00e5ff', '#ff2d95'],
    html: function () {
      G.max = Math.min(220, +opt('scale') || 180);   // en Perú los tableros llegan a 220
      ARC_LEN = G.r * 270 * Math.PI / 180;
      var ticks = '';
      for (var v = 0; v <= G.max; v += 10) {
        var a = 135 + 270 * v / G.max, major = v % 20 === 0;
        ticks += '<line class="tick' + (major ? ' major' : '') + '" x1="' + pt(a, 150).split(' ')[0] + '" y1="' + pt(a, 150).split(' ')[1] +
          '" x2="' + pt(a, major ? 132 : 140).split(' ')[0] + '" y2="' + pt(a, major ? 132 : 140).split(' ')[1] + '"/>';
        if (major) { var p = pt(a, 112).split(' '); ticks += '<text class="num" x="' + p[0] + '" y="' + p[1] + '">' + v + '</text>'; }
      }
      var arc = 'M ' + pt(135, G.r) + ' A ' + G.r + ' ' + G.r + ' 0 1 1 ' + pt(405, G.r);
      return '<div class="screen cockpit">' +
        '<div class="card gauge shine"><svg viewBox="0 0 400 400"><defs><linearGradient id="gGrad" x1="0" y1="1" x2="1" y2="0">' +
        '<stop offset="0" stop-color="#00e5ff"/><stop offset=".6" stop-color="#7c4dff"/><stop offset="1" stop-color="#ff2d95"/></linearGradient></defs>' +
        '<path class="arc-bg" d="' + arc + '"/><path class="arc" id="gArc" d="' + arc + '" stroke-dasharray="' + ARC_LEN.toFixed(1) + '" stroke-dashoffset="' + ARC_LEN.toFixed(1) + '"/>' +
        ticks + '<g class="needle" id="gNeedle" transform="rotate(-135 200 200)"><path d="M196 205 L200 62 L204 205 Z"/></g><circle class="hub" cx="200" cy="200" r="13"/></svg>' +
        '<div class="readout"><b data-k="kmhInt">0</b><span>KM/H</span></div><div class="gpsstate" data-k="gpsLine"></div></div>' +
        '<div class="side stagger">' +
        '<div class="card"><div class="compass"><div class="rose" id="rose"><b class="n">N</b><b class="e">E</b><b class="s">S</b><b class="w">O</b></div><i class="pointer"></i></div>' +
        '<h4>Viaje</h4><div class="big"><span data-k="dist">0.0</span><span class="unit">km</span></div><div class="sub"><span data-k="moving"></span> · prom. <b data-k="avgKmh">0</b> · máx. <b data-k="maxKmh">0</b></div></div>' +
        '<div class="two"><div class="card battery-v" id="battCard"><h4>Batería</h4><div class="big"><span data-k="battV">—</span><span class="unit">V</span></div><div class="bar"><i data-w="batt"></i></div></div>' +
        '<div class="card"><h4>GPS</h4><div class="big"><span data-k="satsUsedTxt">—</span><span class="unit">sats</span></div><div class="sub"><span data-k="alt">—</span> · <span data-k="heading">—</span></div></div></div>' +
        '<div class="card"><h4>Música</h4>' + mediaHtml() + '</div>' +
        '</div></div>';
    },
    frame: function () {
      var f = Math.min(1, shownKmh / G.max);
      var arc = $('#gArc'), nd = $('#gNeedle');
      if (arc) arc.setAttribute('stroke-dashoffset', (ARC_LEN * (1 - f)).toFixed(1));
      if (nd) nd.setAttribute('transform', 'rotate(' + (270 * f - 135).toFixed(2) + ' 200 200)');
    },
    tick: function () {
      var rose = $('#rose');
      if (rose && S.bearing >= 0) rose.style.transform = 'rotate(' + (-S.bearing) + 'deg)';
      $('#battCard').className = 'card battery-v ' + battClass();
      // la cuadrícula del piso corre más rápido cuanto más rápido vas
      var dur = S.kmh > 1 ? Math.max(0.35, 6 * 25 / (S.kmh + 25)) : 8;
      var bucket = Math.round(dur * 4) / 4;
      if (body.style.getPropertyValue('--roadDur') !== bucket + 's') body.style.setProperty('--roadDur', bucket + 's');
    }
  };

  // ---------- Inicio ----------
  var homePage = 0;
  THEMES.home = {
    name: 'Inicio', pv: ['#120c2a', '#ff375f', '#0a84ff'],
    html: function () {
      return '<div class="screen home"><div class="widgets stagger">' +
        '<div class="card"><h4>Velocidad</h4><div class="big"><span data-k="kmhInt">0</span><span class="unit">km/h</span></div></div>' +
        '<div class="card"><h4>Viaje</h4><div class="big"><span data-k="dist">0.0</span><span class="unit">km</span></div></div>' +
        '<div class="card battery-v"><h4>Batería</h4><div class="big"><span data-k="battV">—</span><span class="unit">V</span></div></div>' +
        '</div><div class="pages" id="pages"><div class="track" id="track"></div></div><div class="dots" id="dots"></div></div>';
    },
    mount: function () { renderHomePages(); },
    apps: function () { renderHomePages(); }
  };
  function visibleApps() { return apps.filter(function (a) { return !a.hidden; }); }
  function appHtml(a) {
    return '<div class="app' + (a.hidden ? ' hid' : '') + '" data-app="' + esc(a.key) + '"><div class="ic"><img src="' + a.icon + '" alt=""></div><label>' + esc(a.label) + '</label></div>';
  }
  function renderHomePages() {
    var track = $('#track'), pages = $('#pages');
    if (!track) return;
    var cols = +opt('cols') || 6, list = visibleApps(), per = cols * 3, n = Math.max(1, Math.ceil(list.length / per)), w = pages.clientWidth, html = '';
    for (var p = 0; p < n; p++) html += '<div class="page" style="width:' + w + 'px;grid-template-columns:repeat(' + cols + ',1fr)">' + list.slice(p * per, p * per + per).map(appHtml).join('') + '</div>';
    track.innerHTML = html;
    homePage = Math.min(homePage, n - 1);
    $('#dots').innerHTML = n > 1 ? new Array(n + 1).join('<i></i>') : '';
    showHomePage(homePage);
  }
  function showHomePage(p) {
    var track = $('#track');
    if (!track) return;
    var n = track.children.length;
    homePage = Math.max(0, Math.min(n - 1, p));
    track.style.transform = 'translateX(' + (-homePage * $('#pages').clientWidth) + 'px)';
    $$('#dots i').forEach(function (d, i) { d.classList.toggle('on', i === homePage); });
  }

  // ---------- opciones configurables de cada tema ----------
  var ONOFF = [['on', 'Sí'], ['off', 'No']];
  THEMES.terminal.opts = [{ k: 'phosphor', label: 'Color del fósforo', def: 'green', ch: [['green', 'Verde'], ['amber', 'Ámbar'], ['blue', 'Azul']] },
    { k: 'crt', label: 'Líneas de monitor (CRT)', def: 'on', ch: ONOFF }];
  THEMES.glass.opts = [{ k: 'aurora', label: 'Colores de la aurora', def: 'ocean', ch: [['ocean', 'Océano'], ['sunset', 'Atardecer'], ['forest', 'Bosque']] }];
  THEMES.dusk.opts = [{ k: 'sky', label: 'Cielo', def: 'hour', ch: [['hour', 'Según la hora'], ['day', 'Día'], ['sunset', 'Atardecer'], ['night', 'Noche']] },
    { k: 'greet', label: 'Saludo', def: 'on', ch: ONOFF }];
  THEMES.cockpit.opts = [{ k: 'scale', label: 'Escala del velocímetro (km/h)', def: '180', ch: [['140', '140'], ['180', '180'], ['220', '220']] },
    { k: 'road', label: 'Carretera animada', def: 'on', ch: ONOFF }];
  THEMES.home.opts = [{ k: 'cols', label: 'Columnas de íconos', def: '6', ch: [['5', '5'], ['6', '6'], ['7', '7']] },
    { k: 'widgets', label: 'Widgets de velocidad, viaje y batería', def: 'on', ch: ONOFF }];

  // ---------- Auto · CAN (estilo vidrio líquido) ----------
  // Íconos propios de trazo redondeado (inspirados en el estilo iOS, no copiados).
  var CI = {
    door: '<path d="M6 21V10l5-6h7v17z"/><path d="M9 12h7M14 16h2"/>',
    trunk: '<path d="M4 18h16v-5l-2-4H6l-2 4z"/><path d="M6 9l2-5h8l2 5M7 21v-3M17 21v-3"/>',
    hood: '<path d="M4 16h16v4H4z"/><path d="M5 16l2-5h10l2 5M7 11 5 4h14l-2 7"/>',
    brake: '<circle cx="12" cy="12" r="6.5"/><path d="M10.5 15.5v-7h2.2a2 2 0 0 1 0 4h-2.2M3.5 7.5a10 10 0 0 0 0 9M20.5 7.5a10 10 0 0 1 0 9"/>',
    belt: '<circle cx="12" cy="4.5" r="2"/><path d="M7.5 21v-7a4.5 4.5 0 0 1 9 0v7M8.5 9.5l8 10"/>',
    low: '<path d="M11 5c-4.5 0-7 3.2-7 7s2.5 7 7 7z"/><path d="M14 8.5l6 2M14 12.5l6 2M14 16.5l6 2"/>',
    high: '<path d="M11 5c-4.5 0-7 3.2-7 7s2.5 7 7 7z"/><path d="M14 8h7M14 12h7M14 16h7"/>',
    fog: '<path d="M11 5c-4.5 0-7 3.2-7 7s2.5 7 7 7z"/><path d="M14 8h6M14 12h6M14 16h6M17.5 5.5c-1 2 1 4 0 6.5s1 4 0 6.5"/>',
    hazard: '<path d="M12 3.5 21 19.5H3z"/><path d="M12 9l4 7H8z"/>',
    left: '<path d="M10 6l-6 6 6 6M4 12h16"/>',
    right: '<path d="M14 6l6 6-6 6M20 12H4"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2.5"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    fuel: '<path d="M5 21V5a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v16M3 21h14M7 8h6"/><path d="M15 9l3 2v7a1.5 1.5 0 0 0 3 0V9l-3-3"/>',
    gear: '<circle cx="6" cy="5" r="1.8"/><circle cx="12" cy="5" r="1.8"/><circle cx="18" cy="5" r="1.8"/><circle cx="6" cy="19" r="1.8"/><circle cx="12" cy="19" r="1.8"/><path d="M6 7v10M12 7v10M18 7v5H6"/>',
    lamp: '<circle cx="12" cy="12" r="3.5"/><path d="M12 3v2.5M12 18.5V21M3 12h2.5M18.5 12H21M5.6 5.6l1.8 1.8M16.6 16.6l1.8 1.8M5.6 18.4l1.8-1.8M16.6 7.4l1.8-1.8"/>',
    bus: '<rect x="4" y="5" width="16" height="14" rx="3"/><path d="M8 9h8M8 12h8M8 15h5"/>'
  };
  function ci(k) { return '<svg class="ci" viewBox="0 0 24 24">' + CI[k] + '</svg>'; }
  var C = {}, shownRpm = 0, RR = 120, RC = 2 * Math.PI * RR;
  var DOORS = ['fl', 'fr', 'rl', 'rr', 'trunk', 'hood'];
  var DOOR_NAMES = ['puerta del conductor', 'puerta del copiloto', 'puerta trasera izq.', 'puerta trasera der.', 'maletero', 'capó'];
  var GEARS = { 0: 'P', 1: 'P', 2: 'R', 3: 'N', 4: 'D', 5: 'S', 6: 'M' };
  var INDICATORS = [
    ['brake', 'Freno de mano', function () { return C.handbrake; }],
    ['belt', 'Cinturón', function () { return C.beltDriver; }],
    ['low', 'Luces bajas', function () { return li(1); }],
    ['high', 'Luces altas', function () { return li(0); }],
    ['fog', 'Neblineros', function () { var a = li(2), b = li(3); return a < 0 && b < 0 ? -1 : (a > 0 || b > 0 ? 1 : 0); }],
    ['lamp', 'Luz de posición', function () { return li(4); }],
    ['hazard', 'Emergencia', function () { return li(5); }],
    ['lock', 'Seguros', function () { var l = C.locks || []; if (!l.length || l[0] < 0) return -1; return l.some(function (v) { return v > 0; }) ? 1 : 0; }]
  ];
  function li(i) { var l = C.lights || []; return l.length > i ? l[i] : -1; }
  function carSvg() {
    return '<svg class="car" viewBox="0 0 220 380">' +
      '<path class="shadow" d="M58 30 Q110 6 162 30 L176 120 L178 300 Q176 360 110 366 Q44 360 42 300 L44 120 Z"/>' +
      '<path class="body" d="M58 28 Q110 4 162 28 L176 118 L178 298 Q176 356 110 362 Q44 356 42 298 L44 118 Z"/>' +
      '<path class="zone hood" d="M60 34 Q110 14 160 34 L168 100 L52 100 Z"/>' +
      '<path class="glass" d="M58 108 L162 108 L152 150 L68 150 Z"/>' +
      '<rect class="roof" x="66" y="156" width="88" height="120" rx="14"/>' +
      '<path class="glass" d="M66 282 L154 282 L160 306 L60 306 Z"/>' +
      '<path class="zone trunk" d="M56 312 L164 312 L170 330 Q160 352 110 356 Q60 352 50 330 Z"/>' +
      '<g class="door fl" style="transform-origin:43px 150px"><rect x="38" y="150" width="9" height="66" rx="4"/></g>' +
      '<g class="door fr" style="transform-origin:177px 150px"><rect x="173" y="150" width="9" height="66" rx="4"/></g>' +
      '<g class="door rl" style="transform-origin:43px 222px"><rect x="38" y="222" width="9" height="62" rx="4"/></g>' +
      '<g class="door rr" style="transform-origin:177px 222px"><rect x="173" y="222" width="9" height="62" rx="4"/></g>' +
      '<ellipse class="head l" cx="74" cy="26" rx="14" ry="6"/><ellipse class="head r" cx="146" cy="26" rx="14" ry="6"/>' +
      '<g class="beam"><path d="M62 22 L46 -8 L102 -8 L86 22 Z"/><path d="M134 22 L118 -8 L174 -8 L158 22 Z"/></g>' +
      '<circle class="turn l" cx="50" cy="40" r="6"/><circle class="turn r" cx="170" cy="40" r="6"/>' +
      '<circle class="turn l" cx="48" cy="340" r="6"/><circle class="turn r" cx="172" cy="340" r="6"/>' +
      '</svg>';
  }
  THEMES.auto = {
    name: 'Auto · CAN', pv: ['#0b1a3a', '#5ac8fa', '#f97316'],
    opts: [{ k: 'rpm', label: 'Máximo del tacómetro (rpm)', def: '7000', ch: [['6000', '6000'], ['7000', '7000'], ['8000', '8000']] },
      { k: 'log', label: 'Registro del bus CAN', def: 'on', ch: [['on', 'Mostrar'], ['off', 'Ocultar']] }],
    html: function () {
      var L = RC * 0.75, max = +opt('rpm'), red = (max - 1000) / max;
      return '<div class="screen autoc stagger">' +
        '<div class="card carcard"><div class="head"><h4>Puertas</h4><span class="pill" data-k="doorSum">—</span></div>' + carSvg() + '</div>' +
        '<div class="card rpmcard"><h4>Motor</h4><div class="ring rpm"><svg viewBox="0 0 300 300">' +
        '<circle class="trk" cx="150" cy="150" r="' + RR + '" stroke-dasharray="' + L + ' ' + RC + '"/>' +
        '<circle class="redz" cx="150" cy="150" r="' + RR + '" stroke-dasharray="' + (L * (1 - red)) + ' ' + RC + '" stroke-dashoffset="' + (-L * red) + '"/>' +
        '<circle class="val" id="rpmVal" cx="150" cy="150" r="' + RR + '" stroke-dasharray="0 ' + RC + '"/></svg>' +
        '<div class="center"><b data-k="rpm">—</b><span>rpm</span></div></div>' +
        '<div class="chips"><span class="chip2">' + ci('gear') + '<b data-k="gear">—</b></span><span class="chip2">CAN <b data-k="canKmh">—</b> km/h</span><span class="chip2">GPS <b data-k="kmhInt">0</b> km/h</span></div></div>' +
        '<div class="card indcard"><h4>Señales</h4><div class="inds">' + INDICATORS.map(function (d, i) {
          return '<div class="ind nd" id="ind' + i + '">' + ci(d[0]) + '<div><b>' + d[1] + '</b><span>sin dato</span></div></div>';
        }).join('') + '</div><div class="turns"><span class="tl" id="tl">' + ci('left') + '</span><span class="fuel">' + ci('fuel') + '<b data-k="fuel">—</b></span><span class="tr" id="tr">' + ci('right') + '</span></div></div>' +
        '<div class="card logcard"><div class="head">' + ci('bus') + '<h4>Bus CAN</h4><span class="st" data-k="canStatus"></span><span class="pill" data-k="canPk">0 paquetes</span><button class="rec" id="recBtn">● Grabar en USB</button></div><p class="profile" data-k="canProfile"></p><div class="canlog" id="canlog"></div></div>' +
        '</div>';
    },
    tick: function () {
      var d = C.doors || [], open = [];
      DOORS.forEach(function (k, i) {
        var o = d[i] > 0, el = $('.car .' + (i < 4 ? 'door.' : 'zone.') + k);
        if (el) el.classList.toggle('open', o);
        if (o) open.push(DOOR_NAMES[i]);
      });
      setK('doorSum', !d.length || d[0] < 0 ? 'Sin datos de puertas' : open.length ? 'Abierta: ' + open.join(', ') : 'Todo cerrado');
      $('.carcard .pill').classList.toggle('warn', open.length > 0);
      var lt = C.lights || [], car = $('.car');
      car.classList.toggle('low', lt[1] > 0); car.classList.toggle('high', lt[0] > 0);
      car.classList.toggle('tl', lt[7] > 0 || lt[5] > 0); car.classList.toggle('tr', lt[6] > 0 || lt[5] > 0);
      $('#tl').classList.toggle('on', lt[7] > 0 || lt[5] > 0); $('#tr').classList.toggle('on', lt[6] > 0 || lt[5] > 0);
      INDICATORS.forEach(function (d, i) {
        var v = d[2](), el = $('#ind' + i), sp = $('span', el);
        el.className = 'ind ' + (v == null || v < 0 ? 'nd' : v > 0 ? 'on' : 'off');
        sp.textContent = v == null || v < 0 ? 'sin dato' : v > 0 ? (i === 1 ? 'señal activa' : 'encendido') : 'apagado';
      });
      setK('gear', C.gear >= 0 && GEARS[C.gear] ? GEARS[C.gear] : C.gear >= 0 ? C.gear : '—');
      setK('canKmh', C.speed >= 0 ? C.speed : '—');
      setK('fuel', C.fuel >= 0 ? C.fuel + (C.fuel <= 100 ? ' %' : '') : '—');
      var age = C.ageMs;
      setK('canStatus', (C.status || 'Esperando al servicio CAN…') + (age >= 0 ? ' · último hace ' + (age / 1000).toFixed(1) + ' s' : ''));
      setK('canPk', (C.packets || 0) + ' paquetes');
      setK('canProfile', C.profile ? 'Perfil configurado en la radio → ' + C.profile : '');
      var rb = $('#recBtn');
      if (rb) { rb.classList.toggle('on', !!C.recording); rb.textContent = C.recording ? '■ Detener · ' + (C.recLines || 0) + ' líneas' : '● Grabar en USB'; }
      var lg = $('#canlog');
      if (lg) {
        var lines = (C.log || []).slice(-14);
        if (!lines.length) lg.innerHTML = '<p class="empty">Aún no llegan datos. Abre "Datos del auto" y revisa que el tipo de auto sea Toyota RAV4 y que la caja CAN esté conectada.</p>';
        else lg.innerHTML = lines.map(function (l) {
          var p = l.split('|');
          return '<div><span class="t">' + esc(p[0]) + '</span><span class="k ' + esc(p[1]) + '">' + esc(p[1]) + '</span><span class="x">' + esc(p.slice(2).join('|')) + '</span></div>';
        }).join('');
      }
    },
    frame: function (dt) {
      var tgt = C.rpm > 0 ? C.rpm : 0, max = +opt('rpm');
      shownRpm += params.shot ? tgt - shownRpm : (tgt - shownRpm) * Math.min(1, dt * 5);
      setK('rpm', C.rpm >= 0 ? Math.round(shownRpm / 10) * 10 : '—');
      var el = $('#rpmVal');
      if (el) {
        el.setAttribute('stroke-dasharray', (RC * 0.75 * Math.min(1, shownRpm / max)).toFixed(1) + ' ' + RC);
        el.classList.toggle('hot', shownRpm > max - 1000);
      }
    }
  };

  var ORDER = ['terminal', 'glass', 'dusk', 'cockpit', 'home', 'auto'];

  // ================= montaje =================
  function applyLook() {
    if (!THEMES[prefs.theme]) prefs.theme = 'cockpit';
    body.setAttribute('data-theme', prefs.theme);
    body.setAttribute('data-mode', resolvedMode());
    body.setAttribute('data-fx', effFx());
    body.setAttribute('data-modepref', prefs.mode);
    body.setAttribute('data-icons', prefs.icons);
    var ac = ACCENTS[prefs.accent];
    if (ac) { body.style.setProperty('--acc', ac); body.style.setProperty('--glow', hexA(ac, 0.5)); }
    else { body.style.removeProperty('--acc'); body.style.removeProperty('--glow'); }
    (THEMES[prefs.theme].opts || []).forEach(function (o) { body.setAttribute('data-o-' + o.k, opt(o.k)); });
  }
  function mountTheme() {
    applyLook();
    current = THEMES[prefs.theme];
    $('#view').innerHTML = current.html();
    if (current.mount) current.mount();
    renderDock();
    tick();
    initParticles();
  }

  function renderDock() {
    var pinned = apps.filter(function (a) { return a.pinned && !a.hidden; });
    if (!pinned.length) pinned = visibleApps().slice(0, 4);
    $('#dock').innerHTML = pinned.slice(0, 6).map(function (a) {
      return '<button class="dock-app" data-app="' + esc(a.key) + '"><img src="' + a.icon + '" alt=""></button>';
    }).join('') + '<button class="dock-app set-btn" id="btnSet" aria-label="Ajustes Launcher"><svg viewBox="0 0 24 24"><path d="M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zm7.4-2.5a7.6 7.6 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.4 7.4 0 0 0-1.7-1L15 3h-4l-.4 2.7a7.4 7.4 0 0 0-1.7 1l-2.5-1-2 3.5L6.6 11a7.6 7.6 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1a7.4 7.4 0 0 0 1.7 1L11 21h4l.4-2.7a7.4 7.4 0 0 0 1.7-1l2.5 1 2-3.5z"/></svg></button>' +
      '<button class="dock-app apps-btn" id="btnApps" aria-label="Todas las apps"><span><i></i><i></i><i></i><i></i></span></button>';
  }

  function loadApps() {
    try { apps = JSON.parse(R.apps()) || []; } catch (e) { apps = []; }
    apps.sort(function (a, b) { return a.label.localeCompare(b.label, 'es'); });
    renderDock();
    if (current && current.apps) current.apps();
    if ($('#drawer').classList.contains('open')) renderDrawer();
  }

  // ================= datos en vivo =================
  function poll() {
    try { S = JSON.parse(R.state()) || {}; } catch (e) { S = {}; }
    if ((prefs.theme === 'auto' || C.recording) && R.can) try { C = JSON.parse(R.can()) || {}; } catch (e) { }
    updateRec();
    tick();
  }
  function tick() {
    var d = new Date();
    setK('time', hhmm(d));
    setK('ampm', ampm(d));
    setK('dateShort', DAYS[d.getDay()].slice(0, 3) + ' ' + d.getDate() + ' ' + MONTHS[d.getMonth()].slice(0, 3));
    setK('dateLong', dateLong(d));
    setK('greet', greet());
    setK('dist', num(S.distKm, 1));
    setK('moving', fmtMin(S.movingMin));
    setK('avgKmh', num(S.avgKmh));
    setK('maxKmh', num(S.maxKmh));
    setK('battV', S.batteryV > 0 ? S.batteryV.toFixed(1) : '—');
    setK('battState', battText());
    setK('sats', S.satsUsed >= 0 ? S.satsUsed + '/' + S.satsSeen : '—');
    setK('satsUsedTxt', S.satsUsed >= 0 ? S.satsUsed : '—');
    setK('satsTxt', S.fix ? S.satsUsed + ' satélites, con señal' : 'buscando señal…');
    setK('heading', S.heading || '—');
    setK('alt', S.alt != null ? Math.round(S.alt) + ' m' : '—');
    setK('acc', S.acc != null ? '±' + Math.round(S.acc) + ' m' : '—');
    setK('lat', S.lat != null ? S.lat.toFixed(5) : '—');
    setK('lon', S.lon != null ? S.lon.toFixed(5) : '—');
    setK('cpuTemp', S.cpuTemp || '—');
    setK('volTxt', S.vol != null ? S.vol + '/' + S.volMax : '—');
    setK('gpsLine', S.fix ? 'Rumbo ' + (S.heading || '—') + ' · ±' + Math.round(S.acc || 0) + ' m' : 'Buscando GPS…');
    $('#stMid').textContent = prefs.theme === 'terminal' ? 'rav4@k4811 · ' + (S.fix ? 'driving' : 'idle') : (S.fix ? 'Rumbo ' + (S.heading || '—') + (S.alt != null ? ' · ' + Math.round(S.alt) + ' m s. n. m.' : '') : '');
    $$('[data-w="batt"]').forEach(function (e) { e.style.width = battPct() + '%'; });

    toggleIco('gps', S.fix); toggleIco('bt', S.bt); toggleIco('wifi', S.wifi); toggleIco('batt', S.batteryV > 0);
    var bi = $('[data-on="batt"]'); bi.classList.toggle('low', battClass() === 'low'); bi.classList.toggle('chg', battClass() === 'chg');

    watchEvents();
    if (current && current.tick) current.tick();
    if (prefs.theme === 'dusk' && d.getSeconds() % 30 === 0) paintSky();
    if (prefs.mode === 'auto' && body.getAttribute('data-mode') !== resolvedMode()) applyLook();
    syncControls();
  }
  function toggleIco(k, on) { var e = $('[data-on="' + k + '"]'); if (e) e.classList.toggle('on', !!on); }

  setInterval(function () { hist.push(S.kmh || 0); if (hist.length > 180) hist.shift(); }, 1000);

  function drawSpark(cv, color) {
    if (!cv) return;
    var w = cv.clientWidth, h = cv.clientHeight, dpr = window.devicePixelRatio || 1;
    if (cv.width !== Math.round(w * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
    var c = cv.getContext('2d');
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.clearRect(0, 0, w, h);
    c.strokeStyle = 'rgba(127,127,127,.18)';
    c.lineWidth = 1;
    for (var g = 1; g < 4; g++) { c.beginPath(); c.moveTo(0, h * g / 4); c.lineTo(w, h * g / 4); c.stroke(); }
    if (hist.length < 2) return;
    var max = Math.max(60, Math.max.apply(null, hist) * 1.15), n = 180;
    c.beginPath();
    for (var i = 0; i < hist.length; i++) {
      var x = w - (hist.length - 1 - i) * w / (n - 1), y = h - hist[i] / max * (h - 4) - 2;
      if (i) c.lineTo(x, y); else c.moveTo(x, y);
    }
    c.strokeStyle = color; c.lineWidth = 2; c.shadowColor = color; c.shadowBlur = effFx() === 'high' ? 10 : 0; c.stroke();
    c.lineTo(w, h); c.lineTo(w - (hist.length - 1) * w / (n - 1), h); c.closePath();
    c.shadowBlur = 0; c.globalAlpha = .12; c.fillStyle = color; c.fill(); c.globalAlpha = 1;
  }

  // ================= animación por cuadro =================
  var lastT = 0, frames = 0, fpsStart = 0;
  function frame(t) {
    var dt = lastT ? Math.min(0.1, (t - lastT) / 1000) : 0.016;
    lastT = t;
    var target = S.kmh || 0;
    shownKmh += params.shot ? target - shownKmh : (target - shownKmh) * Math.min(1, dt * 4);
    if (Math.abs(target - shownKmh) < 0.05) shownKmh = target;
    setK('kmhInt', Math.round(shownKmh));
    if (current && current.frame) current.frame(dt);
    drawParticles(dt);
    measureFps(t);
    requestAnimationFrame(frame);
  }

  // Si la radio no llega a ~35 fps con efectos altos, baja a "suaves" una sola vez y avisa.
  function measureFps(t) {
    if (booting || prefs.fx !== 'high' || R.pref('fxChecked', '') === '1' || SIM) return;
    if (!fpsStart) { fpsStart = t; frames = 0; return; }
    frames++;
    if (t - fpsStart > 4000) {
      var fps = frames * 1000 / (t - fpsStart);
      R.setPref('fxChecked', '1');
      if (fps < 35) { setPref('fx', 'low'); toast('Efectos reducidos para que vaya fluido (' + Math.round(fps) + ' fps). Cámbialo en el Panel.'); }
    }
  }

  // ================= partículas =================
  var P = [], cv = $('#particles'), cx = cv.getContext('2d'), pAcc = 0;
  function initParticles() {
    P = [];
    if (effFx() !== 'high') return;
    var n = prefs.theme === 'terminal' ? 0 : prefs.theme === 'cockpit' ? 34 : 22;
    for (var i = 0; i < n; i++) P.push(newP(true));
  }
  function newP(any) {
    var W = innerWidth, H = innerHeight;
    if (prefs.theme === 'cockpit') return { x: any ? Math.random() * W : W + 20, y: H * (.08 + Math.random() * .42), l: 20 + Math.random() * 80, v: .4 + Math.random(), a: .15 + Math.random() * .4 };
    return { x: Math.random() * W, y: any ? Math.random() * H : H + 20, r: 1 + Math.random() * 3.5, v: 6 + Math.random() * 16, a: .1 + Math.random() * .35, ph: Math.random() * 6.28 };
  }
  function drawParticles(dt) {
    if (body.classList.contains('freeze')) return;
    if (!P.length) { if (cv.width) { cv.width = 0; } return; }
    pAcc += dt;
    if (pAcc < 1 / 30) return;   // 30 fps alcanzan para el fondo y ahorran CPU
    dt = pAcc; pAcc = 0;
    var W = innerWidth, H = innerHeight;
    if (cv.width !== W) { cv.width = W; cv.height = H; }
    cx.clearRect(0, 0, W, H);
    var dusk = prefs.theme === 'dusk', night = dusk && (hourNow() >= 19 || hourNow() < 6);
    for (var i = 0; i < P.length; i++) {
      var p = P[i];
      if (prefs.theme === 'cockpit') {
        p.x -= (60 + shownKmh * 9) * p.v * dt;
        if (p.x + p.l < 0) { P[i] = newP(false); continue; }
        var gr = cx.createLinearGradient(p.x, 0, p.x + p.l, 0);
        gr.addColorStop(0, 'rgba(0,229,255,0)'); gr.addColorStop(1, 'rgba(0,229,255,' + p.a + ')');
        cx.strokeStyle = gr; cx.lineWidth = 1.5; cx.beginPath(); cx.moveTo(p.x, p.y); cx.lineTo(p.x + p.l, p.y); cx.stroke();
      } else {
        p.y -= p.v * dt; p.ph += dt; p.x += Math.sin(p.ph) * 8 * dt;
        if (p.y < -10) { P[i] = newP(false); continue; }
        var col = night ? '255,230,140' : dusk ? '255,220,200' : '200,220,255';
        cx.fillStyle = 'rgba(' + col + ',' + (p.a * (night ? (0.5 + 0.5 * Math.sin(p.ph * 3)) : 1)) + ')';
        cx.beginPath(); cx.arc(p.x, p.y, p.r, 0, 6.283); cx.fill();
      }
    }
  }

  // ================= panel y cajón =================
  function openSheet(id) {
    closeSheets(true);
    freeze();
    var el = $('#' + id);
    el.classList.add('open');
    $('#scrim').classList.add('on');
    if (id === 'drawer') renderDrawer();
    if (id === 'panel') {
      // el panel se arma recién al abrirlo: primero la animación y la barra de carga, después el contenido
      el.classList.add('loading');
      setTimeout(function () {
        renderPanelState();
        el.classList.remove('loading');
        setTimeout(renderPerms, 150);   // revisar permisos le pregunta varias cosas al sistema: al final
      }, 60);
    }
  }
  function closeSheets(silent) {
    var was = $$('.sheet.open').length > 0;
    $$('.sheet').forEach(function (s) { s.classList.remove('open'); });
    if (!silent) unfreezeSoon();
    $('#scrim').classList.remove('on');
    return was;
  }
  function renderDrawer() {
    var list = showHiddenApps ? apps : visibleApps();
    $('#appGrid').innerHTML = list.map(appHtml).join('');
    $$('#appGrid .app').forEach(function (e, i) { e.style.animationDelay = Math.min(i, 28) * 0.02 + 's'; });
    var hc = apps.length - visibleApps().length;
    $('#appCount').textContent = visibleApps().length + ' apps · ' + hc + ' ocultas';
    $('#showHidden').classList.toggle('on', showHiddenApps);
  }

  function renderPanelState() {
    if (!$('#panel')) return;   // los ajustes viven en la ventana aparte "Ajustes Launcher"
    $('#themeList').innerHTML = ORDER.map(function (k) {
      var t = THEMES[k], c = t.pv;
      return '<button class="th' + (k === prefs.theme ? ' on' : '') + '" data-theme-pick="' + k + '"><div class="pv" style="background:linear-gradient(135deg,' + c[0] + ',' + c[0] + ' 40%,' + c[1] + ')">' +
        '<i style="left:12%;top:22%;width:30%;height:56%;background:' + c[2] + ';opacity:.85"></i><i style="left:48%;top:22%;width:40%;height:24%;background:rgba(255,255,255,.35)"></i>' +
        '<i style="left:48%;top:54%;width:40%;height:24%;background:rgba(255,255,255,.2)"></i></div><b>' + t.name + '</b></button>';
    }).join('');
    var t = THEMES[prefs.theme];
    $('#themeOptsTitle').textContent = 'Opciones de ' + t.name;
    $('#themeOpts').innerHTML = (t.opts || []).map(function (o) {
      return '<div class="opt"><label>' + o.label + '</label><div class="seg" data-pref="t.' + prefs.theme + '.' + o.k + '">' +
        o.ch.map(function (c) { return '<button data-v="' + c[0] + '">' + c[1] + '</button>'; }).join('') + '</div></div>';
    }).join('');
    (t.opts || []).forEach(function (o) { opt(o.k); });
    $('#accents').innerHTML = '<button class="theme" data-accent="theme" title="Del tema"></button>' + Object.keys(ACCENTS).map(function (k) {
      return '<button data-accent="' + k + '" style="background:' + ACCENTS[k] + '"></button>';
    }).join('');
    $$('#accents button').forEach(function (b) { b.classList.toggle('on', b.getAttribute('data-accent') === prefs.accent); });
    $('#hFrom').textContent = pad(+prefs.darkFrom) + ':00';
    $('#hTo').textContent = pad(+prefs.darkTo) + ':00';
    $$('.seg').forEach(function (seg) {
      var k = seg.getAttribute('data-pref');
      $$('button', seg).forEach(function (b) { b.classList.toggle('on', b.getAttribute('data-v') === prefs[k]); });
    });
    var b = {};
    try { b = JSON.parse(R.boot ? R.boot() : '{}'); } catch (e) { }
    var sec = function (ms) { return ms >= 0 ? (ms / 1000).toFixed(1) + ' s' : '—'; };
    $('#version').textContent = 'RAV4 Launcher ' + R.version() + (SIM ? ' · simulación en navegador' : '') +
      ' · Arranque: sistema→launcher ' + sec(b.proc) + ', pantalla ' + sec(b.page) + ', apps ' + sec(b.apps);
  }

  // sliders de volumen y brillo
  var dragging = null, sendT = 0;
  function sliderSet(el, f) { $('.fill', el).style.width = (Math.max(0, Math.min(1, f)) * 100) + '%'; }
  function syncControls() {
    if (!$('#volSlider')) return;
    if (dragging !== 'vol' && S.volMax) { sliderSet($('#volSlider'), S.vol / S.volMax); $('#volVal').textContent = S.vol + ' / ' + S.volMax; }
    if (dragging !== 'br' && S.bright != null) { sliderSet($('#brSlider'), S.bright / 100); $('#brVal').textContent = S.bright + ' %'; }
    var tw = $('[data-toggle="wifi"]'), tb = $('[data-toggle="bt"]');
    tw.classList.toggle('on', !!S.wifi); tb.classList.toggle('on', !!S.bt);
  }
  function bindSlider(id, kind) {
    var el = $(id);
    if (!el) return;
    function val(e) { var r = el.getBoundingClientRect(); return (e.clientX - r.left) / r.width; }
    function apply(f, final) {
      f = Math.max(0, Math.min(1, f));
      sliderSet(el, f);
      if (kind === 'vol') {
        var v = Math.round(f * (S.volMax || 40));
        $('#volVal').textContent = v + ' / ' + (S.volMax || 40);
        if (final || Date.now() - sendT > 120) { sendT = Date.now(); R.setVolume(v); }
      } else {
        var p = Math.max(S.brightMin || 18, Math.round(f * 100));
        $('#brVal').textContent = p + ' %';
        if (final || Date.now() - sendT > 120) { sendT = Date.now(); R.setBrightness(p); }
      }
    }
    el.addEventListener('pointerdown', function (e) {
      if (kind === 'br' && S.canBright === false) { R.permission('brightness'); toast('Autoriza "Modificar ajustes del sistema" para el launcher'); return; }
      dragging = kind; el.classList.add('drag'); el.setPointerCapture(e.pointerId); apply(val(e));
    });
    el.addEventListener('pointermove', function (e) { if (dragging === kind) apply(val(e)); });
    function end(e) { if (dragging !== kind) return; apply(val(e), true); dragging = null; el.classList.remove('drag'); setTimeout(poll, 400); }
    el.addEventListener('pointerup', end);
    el.addEventListener('pointercancel', function () { dragging = null; el.classList.remove('drag'); });
  }

  function stepVol(d) { var v = Math.max(0, Math.min(S.volMax || 40, (S.vol || 0) + d)); R.setVolume(v); S.vol = v; syncControls(); setTimeout(poll, 350); }
  function stepBr(d) {
    if (S.canBright === false) { R.permission('brightness'); return; }
    var p = Math.max(S.brightMin || 18, Math.min(100, (S.bright || 50) + d)); R.setBrightness(p); S.bright = p; syncControls();
  }

  function setPref(k, v) {
    prefs[k] = v; R.setPref(k, v);
    if (k === 'theme') { mountTheme(); closeSheetsSoon(); }
    else if (k.indexOf('t.') === 0) { mountTheme(); renderPanelState(); }
    else { applyLook(); initParticles(); renderPanelState(); if (k === 'fx' && current.apps) current.apps(); }
  }
  function closeSheetsSoon() { setTimeout(function () { closeSheets(); }, 250); }

  // ================= gestos =================
  // Deslizar → abre el panel (desde la izquierda, lado del conductor); ← abre las apps.
  // En "Inicio", ← / → cambian de página salvo que el gesto empiece en el borde izquierdo.
  var g = null, suppressClick = false;
  document.addEventListener('pointerdown', function (e) {
    g = { x: e.clientX, y: e.clientY, t: Date.now(), edge: e.clientX < innerWidth * 0.12, target: e.target };
    if (g.edge && !$('.sheet.open')) $('#stage').classList.add('dragging-edge');
    freeze();
    if (effFx() !== 'off') ripple(e);
    startLongPress(e);
  }, true);
  document.addEventListener('pointermove', function (e) {
    if (g && (Math.abs(e.clientX - g.x) > 12 || Math.abs(e.clientY - g.y) > 12)) cancelLongPress();
  }, true);
  document.addEventListener('pointerup', function (e) {
    $('#stage').classList.remove('dragging-edge');
    unfreezeSoon();
    cancelLongPress();
    if (!g || dragging) { g = null; return; }
    var dx = e.clientX - g.x, dy = e.clientY - g.y, start = g;
    g = null;
    if (Math.abs(dx) < 70 || Math.abs(dy) > Math.abs(dx) * 0.7) return;
    suppressClick = true; setTimeout(function () { suppressClick = false; }, 60);
    var drawer = $('#drawer').classList.contains('open');
    if (drawer) { if (dx > 0) closeSheets(); return; }
    if (prefs.theme === 'home' && !start.edge && start.target.closest && start.target.closest('#pages')) { showHomePage(homePage + (dx < 0 ? 1 : -1)); return; }
    if (dx < 0) openSheet('drawer');
  }, true);
  document.addEventListener('pointercancel', function () { g = null; cancelLongPress(); $('#stage').classList.remove('dragging-edge'); unfreezeSoon(); }, true);
  document.addEventListener('click', function (e) { if (suppressClick || longFired) { e.stopPropagation(); e.preventDefault(); longFired = false; } }, true);

  var lpT = null, longFired = false;
  function startLongPress(e) {
    longFired = false;
    var a = e.target.closest && e.target.closest('[data-app]');
    if (!a) return;
    lpT = setTimeout(function () {
      longFired = true;
      if (navigator.vibrate) navigator.vibrate(20);
      R.appMenu(a.getAttribute('data-app'));
    }, 550);
  }
  function cancelLongPress() { clearTimeout(lpT); lpT = null; }

  function ripple(e) {
    var t = e.target.closest && e.target.closest('.tile,.app,.dock-app,.th,.list button,.media button,.round,.seg button');
    if (!t) return;
    var r = document.createElement('i');
    r.className = 'ripple'; r.style.left = e.clientX + 'px'; r.style.top = e.clientY + 'px';
    document.body.appendChild(r);
    setTimeout(function () { r.parentNode && r.parentNode.removeChild(r); }, 600);
  }

  // ================= clics =================
  document.addEventListener('click', function (e) {
    var t = e.target;
    var el;
    if ((el = t.closest('[data-app]'))) { R.launch(el.getAttribute('data-app')); if (SIM) toast('Simulado: abrir ' + (el.textContent || 'app')); setTimeout(closeSheets, 300); return; }
    if (t.closest('#btnApps')) { openSheet('drawer'); return; }
    if (t.closest('#btnSet')) { R.openSettings(); if (SIM) toast('Simulado: se abre la ventana Ajustes Launcher'); return; }
    if (t.closest('[data-close]') || t.id === 'scrim') { closeSheets(); return; }
    if (t.closest('#showHidden')) { showHiddenApps = !showHiddenApps; renderDrawer(); return; }
    if ((el = t.closest('[data-theme-pick]'))) { setPref('theme', el.getAttribute('data-theme-pick')); return; }
    if ((el = t.closest('.seg button'))) { setPref(el.parentNode.getAttribute('data-pref'), el.getAttribute('data-v')); return; }
    if (t.closest('#grantAll')) { R.grant('all'); return; }
    if ((el = t.closest('[data-grant]'))) { R.grant(el.getAttribute('data-grant')); return; }
    if (t.closest('#recBtn')) { if (C.recording) stopRec(); else startRec(); return; }
    if (t.closest('#recBadge')) { $('#recGuide').classList.remove('mini'); return; }
    if (t.closest('#rgMin')) { $('#recGuide').classList.toggle('mini'); return; }
    if (t.closest('#rgDone')) { nextStep(true); return; }
    if (t.closest('#rgSkip')) { nextStep(false); return; }
    if (t.closest('#rgStop')) { stopRec(); return; }
    if (t.closest('#rdOk')) { $('#recDone').classList.remove('on'); return; }
    if ((el = t.closest('[data-accent]'))) { setPref('accent', el.getAttribute('data-accent')); return; }
    if ((el = t.closest('[data-hour]'))) { var hp = el.getAttribute('data-hour').split(':'); setPref(hp[0], String((+prefs[hp[0]] + +hp[1] + 24) % 24)); return; }
    if ((el = t.closest('[data-media]'))) { R.media(el.getAttribute('data-media')); return; }
    if ((el = t.closest('[data-open]'))) { R.open(el.getAttribute('data-open')); if (SIM) toast('Simulado: abrir ' + el.textContent.trim()); return; }
    if ((el = t.closest('[data-toggle]'))) { R.toggle(el.getAttribute('data-toggle')); setTimeout(poll, 900); return; }
    if ((el = t.closest('[data-act]'))) {
      var a = el.getAttribute('data-act');
      if (a === 'mute') { R.mute(); setTimeout(poll, 350); }
      if (a === 'resetTrip') { R.resetTrip(); hist = []; toast('Viaje reiniciado'); setTimeout(poll, 200); }
      if (a === 'checkUpdate') { R.checkUpdate(); closeSheets(); }
      if (a === 'useNative') R.useNative();
      return;
    }
    if (t.id === 'volDown') stepVol(-1);
    if (t.id === 'volUp') stepVol(1);
    if (t.id === 'brDown') stepBr(-10);
    if (t.id === 'brUp') stepBr(10);
  });

  // ================= permisos =================
  function renderPerms() {
    if (!$('#perms')) return;
    var list = [];
    try { list = JSON.parse(R.perms ? R.perms() : '[]'); } catch (e) { }
    var missing = list.filter(function (p) { return !p.ok; }).length;
    $('#perms').innerHTML = list.map(function (p) {
      return '<div class="perm' + (p.ok ? ' ok' : '') + '"><span class="pi">' + (p.ok ? '✓' : '!') + '</span><div><b>' + esc(p.name) + '</b><small>' + esc(p.desc) + '</small></div>' +
        (p.ok ? '<em>Concedido</em>' : '<button data-grant="' + p.id + '">Dar</button>') + '</div>';
    }).join('');
    var g = $('#grantAll');
    g.textContent = missing ? 'Conceder los ' + missing + ' que faltan' : 'Todos los permisos concedidos ✓';
    g.disabled = !missing;
  }

  // ================= grabación del bus CAN: indicador + prueba guiada =================
  var STEPS = [
    ['Espera 10 segundos sin tocar nada', 'Sirve de referencia: así se ve el auto "quieto".'],
    ['Abre y cierra la puerta del conductor', 'Déjala abierta 3 segundos antes de cerrarla.'],
    ['Abre y cierra la puerta del copiloto', ''],
    ['Abre y cierra la puerta trasera izquierda', ''],
    ['Abre y cierra la puerta trasera derecha', ''],
    ['Abre y cierra el maletero', ''],
    ['Abre y cierra el capó', 'Si no es cómodo, puedes saltar este paso.'],
    ['Prende y apaga las luces bajas', ''],
    ['Prende y apaga las luces altas', ''],
    ['Prende y apaga los neblineros', 'Si tu RAV4 no tiene, sáltalo.'],
    ['Direccional izquierda unos segundos', ''],
    ['Direccional derecha unos segundos', ''],
    ['Luces de emergencia unos segundos', ''],
    ['Pon y quita el freno de mano', ''],
    ['Abróchate y suéltate el cinturón', ''],
    ['Cierra y abre los seguros', 'Con el botón de la puerta.'],
    ['En P: acelera a ~3000 rpm y suelta', 'Hazlo dos veces, despacio.']
  ];
  var step = 0;
  function fmtDur(ms) { var s2 = Math.floor((ms || 0) / 1000); return Math.floor(s2 / 60) + ':' + pad(s2 % 60); }
  function mark(txt) { if (R.canMark) R.canMark(txt); }
  function showStep() {
    var done = step >= STEPS.length;
    $('#rgN').textContent = done ? 'Prueba completa' : 'Paso ' + (step + 1) + ' de ' + STEPS.length;
    $('#rgStep').textContent = done ? 'Listo. Pulsa "Terminar y guardar".' : STEPS[step][0];
    $('#rgHint').textContent = done ? 'Si quieres, maneja un poco antes de terminar: también se graba.' : STEPS[step][1];
    $('#rgBar').style.width = (Math.min(step, STEPS.length) / STEPS.length * 100) + '%';
    $('#rgDone').style.display = $('#rgSkip').style.display = done ? 'none' : '';
    var h = $('#rgStep'); h.classList.remove('pop'); void h.offsetWidth; h.classList.add('pop');
    if (!done) mark('▶ ' + STEPS[step][0]);
  }
  function nextStep(ok) {
    if (step < STEPS.length) mark((ok ? '✓ ' : '✗ salteado: ') + STEPS[step][0]);
    step++;
    showStep();
  }
  function startRec() {
    var res = R.canRecord(true);
    if (res === 'PERMISO') { toast('Acepta el permiso de almacenamiento y vuelve a pulsar Grabar'); return; }
    if (res.indexOf('ERROR') === 0) { toast(res.slice(7)); return; }
    C.recording = res;
    step = 0;
    $('#recGuide').classList.remove('mini');
    showStep();
    closeSheets();
    poll();
  }
  function stopRec() {
    if (step < STEPS.length) mark('■ grabación detenida en el paso ' + (step + 1));
    var res = R.canRecord(false), lines = C.recLines || 0, d = $('#recDone');
    C.recording = '';
    updateRec();
    d.classList.remove('ok'); d.classList.add('on');
    $('#rdTitle').textContent = 'Asegurando los datos en el USB…';
    $('#rdText').textContent = '';
    $('#rdOk').style.visibility = 'hidden';
    setTimeout(function () {
      d.classList.add('ok');
      var usb = res.indexOf('USB') === 0;
      $('#rdTitle').textContent = usb ? 'Guardado en el USB ✓' : 'Guardado en la radio';
      $('#rdText').innerHTML = '<b>' + esc(res.replace(/^[^:]*: /, '')) + '</b><br>' + lines + ' líneas · ' + (usb ? 'Ya puedes sacar el USB y traer el archivo.' :
        'No se pudo usar el USB: el archivo quedó en la memoria interna de la radio, carpeta RAV4Launcher.');
      $('#rdOk').style.visibility = 'visible';
    }, 1600);
    setTimeout(poll, 300);
  }
  var lastLines = 0;
  function updateRec() {
    var on = !!C.recording;
    body.classList.toggle('recording', on);
    $('#recGuide').classList.toggle('on', on);
    if (!on) return;
    $('#recTime').textContent = $('#rgTime').textContent = fmtDur(C.recMs);
    var ln = C.recLines || 0, el = $('#recLn');
    el.textContent = ln + ' líneas';
    if (ln !== lastLines) { el.classList.remove('bump'); void el.offsetWidth; el.classList.add('bump'); lastLines = ln; }
    $('#rgSync').textContent = C.syncAgoMs >= 0 ? (C.recUsb === false ? 'en memoria interna' : 'guardado en USB hace ' + Math.max(0, Math.round(C.syncAgoMs / 1000)) + ' s') : '';
  }

  // ================= API para Android (WebHome.java) =================
  window.RAV4 = {
    back: function () { if (!closeSheets() && prefs.theme === 'home') showHomePage(0); },
    resume: function () { closeSheets(); poll(); },
    // al volver de "Ajustes Launcher" con cambios: releer todo y rearmar el tema
    reloadPrefs: function () {
      prefs = { theme: R.pref('theme', 'cockpit'), mode: R.pref('mode', 'dark'), fx: R.pref('fx', 'low'), icons: 'plain',
        accent: R.pref('accent', 'theme'), clock: R.pref('clock', '12'), darkFrom: R.pref('darkFrom', '18'), darkTo: R.pref('darkTo', '6') };
      mountTheme();
    },
    appsChanged: loadApps
  };

  // ================= simulador (solo navegador) =================
  function makeSim() {
    var kmh = params.kmh ? +params.kmh : 0, target = 45, dist = 3.2, max = 0, moving = 14, vol = 18, br = 62, wifi = true, bt = true, bearing = 60, batt = 13.8;
    var simPerm = false;
    var store = {}, simDoor = -1, simLog = [], simLastT = 0, simRec = !!params.rec, simRecN = 0, simRecT = Date.now() - 42000;
    function pad2(n) { return (n < 10 ? '0' : '') + n; }
    try { store = JSON.parse(localStorage.getItem('rav4sim') || '{}'); } catch (e) { }
    setInterval(function () {
      if (Math.random() < 0.03) target = [0, 20, 45, 60, 80, 105][Math.floor(Math.random() * 6)];
      if (params.kmh) { kmh = +params.kmh; max = Math.max(max, kmh + 18); return; }
      kmh = Math.max(0, kmh + (target - kmh) * 0.08 + (Math.random() - 0.5) * 1.5);
      if (kmh > max) max = kmh;
      if (kmh > 1) { dist += kmh / 3600 * 0.5; moving += 0.5 / 60; }
      bearing = (bearing + (Math.random() - 0.5) * 6 + 360) % 360;
      batt = kmh > 1 ? 13.9 + Math.random() * 0.2 : 12.6;
    }, 500);
    var COLORS = ['#ff375f', '#ff9f0a', '#30d158', '#0a84ff', '#bf5af2', '#64d2ff', '#ffd60a', '#ff453a', '#5e5ce6', '#32ade6'];
    var NAMES = ['Música', 'Mapas', 'Waze', 'Spotify', 'YouTube', 'ZLINK', 'Radio FM', 'Bluetooth', 'Cámara', 'Videos', 'Galería', 'Ajustes',
      'Chrome', 'Archivos', 'Clima', 'Notas', 'Teléfono', 'Mensajes', 'Calculadora', 'Reloj', 'Podcasts', 'Ecualizador', 'Radio Diagnóstico', 'Tienda'];
    var simApps = NAMES.map(function (n, i) {
      var c = COLORS[i % COLORS.length];
      var svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="' + c +
        '"/><stop offset="1" stop-color="#111"/></linearGradient></defs><rect width="96" height="96" rx="20" fill="url(#g)"/><text x="48" y="62" font-size="44" font-family="Arial" font-weight="bold" fill="#fff" text-anchor="middle">' +
        n.charAt(0) + '</text></svg>';
      return { key: 'sim/' + i, pkg: 'sim.' + i, label: n, icon: 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg), hidden: i === 23, pinned: i < 4 };
    });
    function hd(b) { var d = ['N', 'NE', 'E', 'SE', 'S', 'SO', 'O', 'NO']; return d[Math.round(b / 45) % 8]; }
    return {
      state: function () {
        var h = (Date.now() / 1000) % 3600;
        return JSON.stringify({ kmh: kmh, maxKmh: max, distKm: dist, movingMin: moving, avgKmh: moving > 0 ? dist / (moving / 60) : 0, fix: true,
          satsUsed: 9, satsSeen: 14, lat: -12.12, lon: -77.03, alt: 112, acc: 4, bearing: bearing, heading: hd(bearing), batteryV: batt,
          vol: vol, volMax: 40, bright: br, brightMin: 18, canBright: true, wifi: wifi, bt: bt, cpuTemp: (48 + Math.round(h % 6)) + ' °C', sessionMin: 30 });
      },
      apps: function () { return JSON.stringify(simApps); },
      launch: function () { }, appMenu: function (k) { toast('Simulado: menú de la app (fijar / ocultar / desinstalar)'); },
      setVolume: function (v) { vol = v; }, mute: function () { vol = vol ? 0 : 18; }, setBrightness: function (p) { br = p; },
      media: function (w) { toast('Simulado: ' + w); }, open: function () { },
      toggle: function (w) { if (w === 'wifi') wifi = !wifi; else bt = !bt; },
      pref: function (k, d) { return store[k] != null ? store[k] : d; },
      setPref: function (k, v) { store[k] = v; try { localStorage.setItem('rav4sim', JSON.stringify(store)); } catch (e) { } },
      resetTrip: function () { dist = 0; max = 0; moving = 0; }, checkUpdate: function () { toast('Simulado: buscar actualización'); },
      useNative: function () { toast('Simulado: volver a la interfaz nativa'); },
      openSettings: function () { },
      version: function () { return 'web-preview'; }, boot: function () { return '{"proc":-1,"page":120,"apps":300}'; }, permission: function () { },
      perms: function () {
        return JSON.stringify([['location', 'Ubicación (GPS)', 'Velocidad, viaje y rumbo'], ['storage', 'Almacenamiento', 'Grabar el bus CAN en el USB'],
          ['mic', 'Micrófono', 'Comandos de voz'], ['camera', 'Cámara', 'Cámara de retroceso / dashcam'], ['phone', 'Teléfono y contactos', 'Llamar desde el launcher'],
          ['settings', 'Modificar ajustes del sistema', 'Brillo y su mínimo seguro'], ['install', 'Instalar apps', 'Actualizarse desde GitHub'],
          ['notif', 'Acceso a notificaciones', 'Ver y controlar la música que suena'], ['overlay', 'Mostrar sobre otras apps', 'Velocímetro flotante encima de Waze/CarPlay'],
          ['usage', 'Acceso de uso', 'Apps recientes y más usadas'], ['battery', 'Sin optimización de batería', 'Que Android no lo cierre (ni la grabación CAN)'],
          ['dnd', 'No molestar', 'Modo manejo'], ['home', 'Launcher predeterminado', 'Que el botón Inicio abra este launcher']]
          .map(function (p, i) { return { id: p[0], name: p[1], desc: p[2], ok: simPerm || i % 3 === 0 }; }));
      },
      grant: function () { simPerm = true; toast('Simulado: se abre la pantalla del permiso'); setTimeout(renderPerms, 500); },
      canMark: function (t) { var d = new Date(); simLog.push(pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds()) + '|marca|' + t); simRecN++; },
      canRecord: function (on) { simRec = on; simRecN = 0; simRecT = Date.now(); return on ? 'USB: /storage/XXXX-XXXX/RAV4Launcher/can_simulado.txt' : '/storage/XXXX-XXXX/RAV4Launcher/can_simulado.txt'; },
      can: function () {
        if (simRec) simRecN += 3;
        var t = Date.now() / 1000, rpm = kmh > 1 ? Math.round(900 + kmh * 32 + Math.sin(t * 3) * 120) : 780;
        if (Math.random() < 0.02) simDoor = simDoor >= 0 ? -1 : Math.floor(Math.random() * 6);
        var doors = [0, 0, 0, 0, 0, 0]; if (simDoor >= 0) doors[simDoor] = 1;
        if (params.door) doors[+params.door] = 1;
        var night = new Date().getHours() >= 18 || new Date().getHours() < 6, blink = Math.floor(t / 8) % 3 === 1 ? 1 : 0;
        var hx = function (n) { return (n < 16 ? '0' : '') + n.toString(16).toUpperCase(); };
        var tm = new Date(), ts = pad2(tm.getHours()) + ':' + pad2(tm.getMinutes()) + ':' + pad2(tm.getSeconds());
        if (Math.floor(t * 2) !== simLastT) {
          simLastT = Math.floor(t * 2);
          var sp = Math.round(kmh);
          simLog.push(ts + '|auto|AA 02 40 ' + hx(sp >> 8) + ' ' + hx(sp & 255) + ' ' + hx(rpm >> 8) + ' ' + hx(rpm & 255) + ' 00 3A 9C 00 00 01 01 3E 00 00 3E 7E');
          if (Math.random() < 0.3) simLog.push(ts + '|puertas|AA 03 01 ' + hx(doors.reduce(function (a, v, i) { return a | (v << (7 - i)); }, 0)));
          if (simLog.length > 40) simLog.splice(0, simLog.length - 40);
        }
        return JSON.stringify({ status: 'Simulación en navegador (en la radio son datos reales del bus CAN)', connected: true, packets: simLog.length * 7, ageMs: 180,
          recording: simRec ? 'USB: /storage/XXXX-XXXX/RAV4Launcher/can_simulado.txt' : '', recLines: simRecN, recMs: simRec ? Date.now() - simRecT : 0, syncAgoMs: simRec ? 1200 : -1, recUsb: true, profile: 'auto: {"brand":"toyota","model":"RAV4 2013"} (simulado) · caja CAN: hiworld', speed: Math.round(kmh), rpm: rpm, handbrake: kmh > 1 ? 0 : 1, fuel: 62, gear: kmh > 1 ? 4 : 1, beltDriver: 1, beltPassenger: 0,
          doors: doors, locks: [kmh > 1 ? 1 : 0, 0, 0, 0, 0], lights: [0, night ? 1 : 0, 0, 0, night ? 1 : 0, 0, blink, 0], radar: [0, 0, 0, 0], log: simLog });
      }
    };
  }

  // ================= arranque =================
  // Desde v1.3.13 los efectos parten en "Suaves": el desenfoque de vidrio era demasiado para el GPU de la radio.
  if (!SIM && !R.pref('fxv2', '')) { R.setPref('fxv2', '1'); R.setPref('fx', prefs.fx); }
  scale();
  window.addEventListener('resize', function () { scale(); if (current && current.apps) current.apps(); });
  // Primero la pantalla; lo demás (apps, datos) llega después y se completa solo.
  mountTheme();
  requestAnimationFrame(function () { if (R.ready) R.ready(); });
  setTimeout(function () { loadApps(); poll(); setInterval(poll, 500); requestAnimationFrame(frame); }, 0);
  // reactivar animaciones cuando ya cargó (2.5 s) o al primer toque, lo que ocurra antes
  setTimeout(enableFx, 2500);
  document.addEventListener('pointerdown', function () { setTimeout(enableFx, 400); }, { once: true });
  if (params.rec) { step = +params.step || 1; showStep(); }
  if (params.drawer) openSheet('drawer');
})();
