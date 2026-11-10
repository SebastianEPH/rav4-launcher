/* Ajustes RAV4 — ventana aparte (SettingsActivity). Claves: las de la interfaz web llevan "web.", las nativas no. */
(function () {
  'use strict';
  var $ = function (s) { return document.querySelector(s); };
  var $$ = function (s) { return Array.prototype.slice.call(document.querySelectorAll(s)); };
  var R = window.Radio || sim();
  var ONOFF = [['on', 'Sí'], ['off', 'No']];
  var WEB = [
    ['terminal', 'Terminal', '#07090d,#3cff8f', [['phosphor', 'Color del fósforo', 'green', [['green', 'Verde'], ['amber', 'Ámbar'], ['blue', 'Azul']]], ['crt', 'Líneas de monitor', 'on', ONOFF]]],
    ['glass', 'Glass', '#2a5bff,#b44bff', [['aurora', 'Colores de la aurora', 'ocean', [['ocean', 'Océano'], ['sunset', 'Atardecer'], ['forest', 'Bosque']]]]],
    ['dusk', 'Dusk', '#e58fa6,#231a3a', [['sky', 'Cielo', 'hour', [['hour', 'Según la hora'], ['day', 'Día'], ['sunset', 'Atardecer'], ['night', 'Noche']]], ['greet', 'Saludo', 'on', ONOFF]]],
    ['cockpit', 'Cockpit', '#030406,#00e5ff', [['scale', 'Escala del velocímetro (km/h)', '180', [['140', '140'], ['180', '180'], ['220', '220']]], ['road', 'Carretera animada', 'on', ONOFF]]],
    ['home', 'Inicio', '#120c2a,#ff375f', [['cols', 'Columnas de íconos', '6', [['5', '5'], ['6', '6'], ['7', '7']]], ['widgets', 'Widgets', 'on', ONOFF]]],
    ['auto', 'Auto · CAN', '#0b1a3a,#f97316', [['rpm', 'Máximo del tacómetro', '7000', [['6000', '6000'], ['7000', '7000'], ['8000', '8000']]], ['log', 'Registro del bus CAN', 'on', [['on', 'Mostrar'], ['off', 'Ocultar']]]]]
  ];
  var NATIVE = [['terminal', 'Terminal', '#0b0e13,#58a6ff'], ['glass', 'Glass', '#0b1426,#2b1747'], ['dusk', 'Dusk', '#e58fa6,#231a3a'],
    ['cockpit', 'Cockpit', '#000,#0a84ff'], ['home', 'Inicio', '#1c1c1e,#30d158']];
  var ACC = { blue: '#0a84ff', cyan: '#5ac8fa', green: '#30d158', orange: '#ff9f0a', pink: '#ff375f', purple: '#bf5af2', red: '#ff453a' };

  function get(k, d) { return R.pref(k, d); }
  function set(k, v) { R.setPref(k, v); render(); }
  function isWeb() { return get('engine', 'native') === 'web'; }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function hourTxt(h) { return get('web.clock', '12') === '24' ? pad(h) + ':00' : (h % 12 || 12) + (h < 12 ? ' AM' : ' PM'); }

  function render() {
    var web = isWeb();
    $$('.webonly').forEach(function (e) { e.classList.toggle('hide', !web); });
    $$('.nativeonly').forEach(function (e) { e.classList.toggle('hide', web); });
    var tk = web ? 'web.theme' : 'theme', cur = get(tk, web ? 'cockpit' : 'terminal');
    $('#themes').innerHTML = (web ? WEB : NATIVE).map(function (t) {
      return '<button class="th' + (t[0] === cur ? ' on' : '') + '" data-theme="' + t[0] + '"><i style="background:linear-gradient(135deg,' + t[2] + ')"></i><span>' + t[1] + '</span></button>';
    }).join('');
    var opts = [];
    if (web) WEB.forEach(function (t) { if (t[0] === cur) { opts = t[3]; $('#optsTitle').textContent = 'Opciones de ' + t[1]; } });
    $('#optsWrap').classList.toggle('hide', !opts.length);
    $('#opts').innerHTML = opts.map(function (o) {
      return '<div class="row"><b>' + o[1] + '</b><div class="seg" data-k="web.t.' + cur + '.' + o[0] + '" data-d="' + o[2] + '">' +
        o[3].map(function (c) { return '<button data-v="' + c[0] + '">' + c[1] + '</button>'; }).join('') + '</div></div>';
    }).join('');
    var mk = web ? 'web.mode' : 'mode';
    $('#modeSeg').setAttribute('data-k', mk);
    $('#modeSeg').innerHTML = '<button data-v="dark">Oscuro</button><button data-v="light">Claro</button><button data-v="auto">Automático</button>';
    $('#hoursRow').classList.toggle('hide', !(web && get(mk, 'dark') === 'auto'));
    $('#hFrom').textContent = hourTxt(+get('web.darkFrom', '18'));
    $('#hTo').textContent = hourTxt(+get('web.darkTo', '6'));
    var ac = get('web.accent', 'theme');
    $('#accents').innerHTML = '<button class="theme' + (ac === 'theme' ? ' on' : '') + '" data-acc="theme"></button>' + Object.keys(ACC).map(function (k) {
      return '<button data-acc="' + k + '" class="' + (ac === k ? 'on' : '') + '" style="background:' + ACC[k] + '"></button>';
    }).join('');
    var defs = { engine: 'native', 'web.clock': '12', 'web.fx': 'low', wall: 'ondas', 'web.mode': 'dark', mode: 'dark' };
    $$('.seg[data-k]').forEach(function (seg) {
      var k = seg.getAttribute('data-k'), v = get(k, seg.getAttribute('data-d') || defs[k] || '');
      $$('.seg[data-k="' + k + '"] button').forEach(function (b) { b.classList.toggle('on', b.getAttribute('data-v') === v); });
    });
    document.body.setAttribute('data-ab', get('autobright', 'off'));
    renderBands();
    $('#version').textContent = 'RAV4 Launcher ' + R.version();
    setTimeout(renderPerms, 30);
  }

  function bandList() {
    return get('autobright.bands', '7:90,19:55,22:25').split(',').map(function (b) {
      var hp = b.split(':'); return { h: +hp[0], p: +hp[1] };
    }).filter(function (b) { return !isNaN(b.h) && !isNaN(b.p); }).sort(function (a, b) { return a.h - b.h; });
  }
  function saveBands(list) {
    list.sort(function (a, b) { return a.h - b.h; });
    R.setPref('autobright.bands', list.map(function (b) { return b.h + ':' + b.p; }).join(','));
  }
  function renderBands() {
    var list = bandList();
    $('#bands').innerHTML = list.map(function (b, i) {
      return '<div class="band" data-i="' + i + '"><div class="hr"><button data-bh="-1">−</button><em>' +
        (b.h < 10 ? '0' + b.h : b.h) + ':00</em><button data-bh="1">+</button></div>' +
        '<div class="br"><div class="lvl">Brillo <b>' + b.p + ' %</b></div><input type="range" min="18" max="100" value="' + b.p + '"></div>' +
        '<button class="del" data-del="' + i + '"' + (list.length <= 1 ? ' style="visibility:hidden"' : '') + '>✕</button></div>';
    }).join('');
  }
  document.addEventListener('input', function (e) {
    if (e.target.type !== 'range' || !e.target.closest('.band')) return;
    var i = +e.target.closest('.band').getAttribute('data-i'), list = bandList();
    list[i].p = +e.target.value;
    e.target.closest('.band').querySelector('.lvl b').textContent = list[i].p + ' %';
    saveBands(list);
  });

  function renderPerms() {
    var list = [];
    try { list = JSON.parse(R.perms()); } catch (e) { }
    var missing = list.filter(function (p) { return !p.ok; }).length;
    $('#perms').innerHTML = list.map(function (p) {
      return '<div class="row' + (p.ok ? ' ok' : '') + '"><span class="pi">' + (p.ok ? '✓' : '!') + '</span><b>' + p.name + '<small>' + p.desc + '</small></b>' +
        (p.ok ? '<em>Concedido</em>' : '<button class="give" data-grant="' + p.id + '">Dar</button>') + '</div>';
    }).join('');
    $('#all').textContent = missing ? 'Conceder los ' + missing + ' que faltan' : 'Todos los permisos concedidos ✓';
    $('#all').disabled = !missing;
  }

  document.addEventListener('click', function (e) {
    var t = e.target, el;
    if (t.closest('#done')) { R.close(); return; }
    if ((el = t.closest('.seg button'))) { set(el.parentNode.getAttribute('data-k'), el.getAttribute('data-v')); return; }
    if ((el = t.closest('[data-theme]'))) { set(isWeb() ? 'web.theme' : 'theme', el.getAttribute('data-theme')); return; }
    if ((el = t.closest('[data-acc]'))) { set('web.accent', el.getAttribute('data-acc')); return; }
    if ((el = t.closest('[data-h]'))) {
      var p = el.getAttribute('data-h').split(':'), k = p[0] === 'from' ? 'web.darkFrom' : 'web.darkTo';
      set(k, String((+get(k, p[0] === 'from' ? '18' : '6') + +p[1] + 24) % 24)); return;
    }
    if ((el = t.closest('[data-bh]'))) {
      var bi = +el.closest('.band').getAttribute('data-i'), l = bandList();
      l[bi].h = (l[bi].h + +el.getAttribute('data-bh') + 24) % 24; saveBands(l); renderBands(); return;
    }
    if (t.closest('#addBand')) { var l2 = bandList(); var used = l2.map(function (b) { return b.h; }); var nh = 12; while (used.indexOf(nh) >= 0) nh = (nh + 1) % 24; l2.push({ h: nh, p: 60 }); saveBands(l2); renderBands(); return; }
    if ((el = t.closest('[data-del]'))) { var l3 = bandList(); if (l3.length > 1) { l3.splice(+el.getAttribute('data-del'), 1); saveBands(l3); renderBands(); } return; }
    if ((el = t.closest('[data-grant]'))) { R.grant(el.getAttribute('data-grant')); return; }
    if (t.closest('#all')) { R.grant('all'); return; }
    if ((el = t.closest('[data-act]'))) {
      var a = el.getAttribute('data-act');
      if (a === 'update') R.checkUpdate(); else R.open(a);
    }
  });

  window.S = { refresh: render };

  function sim() {
    var st = {};
    try { st = JSON.parse(localStorage.getItem('rav4set') || '{}'); } catch (e) { }
    var ok = false;
    return {
      pref: function (k, d) { if (k === 'autobright' && /ab/.test(location.search) && st[k] == null) return 'on'; return st[k] != null ? st[k] : d; },
      setPref: function (k, v) { st[k] = v; try { localStorage.setItem('rav4set', JSON.stringify(st)); } catch (e) { } },
      perms: function () {
        return JSON.stringify([['location', 'Ubicación (GPS)', 'Velocidad, viaje y rumbo'], ['storage', 'Almacenamiento', 'Grabar el bus CAN en el USB'],
          ['settings', 'Modificar ajustes del sistema', 'Brillo y su mínimo seguro'], ['install', 'Instalar apps', 'Actualizarse desde GitHub'],
          ['notif', 'Acceso a notificaciones', 'Ver y controlar la música'], ['home', 'Launcher predeterminado', 'Que Inicio abra este launcher']]
          .map(function (p, i) { return { id: p[0], name: p[1], desc: p[2], ok: ok || i % 2 === 0 }; }));
      },
      grant: function () { ok = true; setTimeout(renderPerms, 300); }, checkUpdate: function () { }, open: function () { },
      version: function () { return 'web-preview'; }, close: function () { }
    };
  }

  render();
})();
