/*
 * Fernbedienungs-Navigation für das Home-Assistant-Frontend (läuft in der WebView der App).
 *
 *  - findet bedienbare Elemente (auch in Shadow-DOMs): Karten mit Aktionen, Buttons, Slider, ...
 *  - Steuerkreuz: räumliche Navigation, die aktive Karte bekommt einen Rahmen
 *  - OK: sendet Maus-Ereignisse (down / up / click) wie ein echter Mausklick. Die Karten von
 *    Home Assistant werten daraus selbst kurz (tap), lang (hold) und doppelt (double_tap) aus.
 *  - bei offenem Dialog (More-Info) wird nur innerhalb des Dialogs navigiert
 *
 * Wird von der App nach jedem Seitenaufbau injiziert; mehrfaches Ausführen ist unschädlich.
 */
(function () {
  'use strict';
  var ACCENT = window.__tvAccent || '#03A9F4';
  if (window.__tv) { window.__tv.setAccent(ACCENT); return; }

  var cur = null;                       // aktuell markiertes Element
  var downTarget = null;                // Ziel des laufenden OK-Drucks
  var lastClick = { t: 0, el: null };   // für die Doppelklick-Erkennung (event.detail)
  var cursorCache = new WeakMap();
  var cache = { t: 0, list: [], dialog: null };

  // ---------------------------------------------------------------- Rahmen
  var ov = document.createElement('div');
  ov.id = '__tv_focus';
  ov.style.cssText = 'position:fixed;left:0;top:0;right:auto;bottom:auto;width:0;height:0;z-index:2147483647;' +
    'margin:0;padding:0;background:transparent;overflow:visible;color:inherit;' +
    'pointer-events:none;box-sizing:border-box;border-radius:12px;display:none;';
  try { ov.setAttribute('popover', 'manual'); } catch (e) {}
  var raised = null;      // Dialog, über dem der Rahmen zuletzt in den Top-Layer gehoben wurde
  function toTop() {
    try { if (ov.matches(':popover-open')) ov.hidePopover(); ov.showPopover(); } catch (e) {}
  }
  function raise(d) {
    // Modale <dialog>-Elemente liegen im Top-Layer und verdecken sonst jeden z-index -> Rahmen als Popover nachziehen
    if (raised === d) return;
    raised = d;
    try { if (ov.matches(':popover-open')) ov.hidePopover(); ov.showPopover(); } catch (e) {}
  }
  function paint() {
    ov.style.border = '4px solid ' + ACCENT;
    ov.style.boxShadow = '0 0 0 2px rgba(0,0,0,.5), 0 0 22px ' + ACCENT;
  }
  paint();
  document.documentElement.appendChild(ov);

  function tick() {
    requestAnimationFrame(tick);
    if (!cur || !cur.isConnected) { ov.style.display = 'none'; return; }
    var r = cur.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) { ov.style.display = 'none'; return; }
    ov.style.display = 'block';
    raise(cache.dialog || 1);
    ov.style.left = (r.left - 4) + 'px';
    ov.style.top = (r.top - 4) + 'px';
    ov.style.width = (r.width + 8) + 'px';
    ov.style.height = (r.height + 8) + 'px';
  }
  requestAnimationFrame(tick);

  // ---------------------------------------------------------------- Hilfsfunktionen
  function parentOf(n) {
    // Flat-Tree: in Slots eingehängte Knoten gehören zum Slot (z. B. More-Info-Inhalt im Drawer-Dialog)
    if (n.assignedSlot) return n.assignedSlot;
    var p = n.parentNode;
    if (!p) return null;
    if (p.nodeType === 11 && p.host) return p.host;       // Shadow-Root -> Host
    return p.nodeType === 1 ? p : null;
  }
  function inside(el, root) {
    var n = el;
    while (n) { if (n === root) return true; n = parentOf(n); }
    return false;
  }
  function rectOf(el) { return el.getBoundingClientRect(); }
  function zero(el) { var r = rectOf(el); return r.width < 4 || r.height < 4; }
  function contains(b, a) {
    return a.left >= b.left - 2 && a.right <= b.right + 2 && a.top >= b.top - 2 && a.bottom <= b.bottom + 2;
  }

  var ROLE = { button: 1, slider: 1, 'switch': 1, tab: 1, link: 1, menuitem: 1, checkbox: 1, radio: 1, option: 1, combobox: 1 };
  var TAG = { BUTTON: 1, A: 1, INPUT: 1, SELECT: 1, TEXTAREA: 1, 'HA-SWITCH': 1, 'HA-ICON-BUTTON': 1 };
  var SKIP = { SCRIPT: 1, STYLE: 1, LINK: 1, META: 1, HEAD: 1, TEMPLATE: 1, NOSCRIPT: 1, DEFS: 1 };
  var DIALOG = { 'HA-DIALOG': 1, 'HA-WA-DIALOG': 1, 'DIALOG': 1, 'MWC-DIALOG': 1, 'HA-ADAPTIVE-DIALOG': 1, 'HA-BOTTOM-SHEET': 1 };

  function isExplicit(el) {
    var role = el.getAttribute && el.getAttribute('role');
    return !!(role && ROLE[role]) || !!TAG[el.tagName];
  }
  function isSlider(el) {
    var role = el.getAttribute && el.getAttribute('role');
    return role === 'slider' || el.tagName === 'HA-CONTROL-SLIDER' ||
      (el.tagName === 'INPUT' && el.type === 'range');
  }
  function cursorOf(el) {
    var v = cursorCache.get(el);
    if (v === undefined) {
      try { v = getComputedStyle(el).cursor; } catch (e) { v = ''; }
      cursorCache.set(el, v);
    }
    return v;
  }

  // ---------------------------------------------------------------- Kandidaten sammeln
  function test(el, out) {
    if (typeof SVGElement !== 'undefined' && el instanceof SVGElement && el.tagName.toLowerCase() !== 'svg') return;
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') return;
    var strong = isExplicit(el) || el.actionHandler !== undefined;
    var r = el.getBoundingClientRect();
    if (r.width < 14 || r.height < 14) return;
    if (!strong) {
      if (r.width > innerWidth * 0.8 && r.height > innerHeight * 0.6) return;
      if (cursorOf(el) !== 'pointer') return;
      var p = parentOf(el);
      if (p && cursorOf(p) === 'pointer') return;          // cursor wird vererbt -> nur der Ursprung zählt
    }
    if (el.checkVisibility && !el.checkVisibility({ checkVisibilityCSS: true })) return;
    out.push(el);
  }

  function walk(root, out, st) {
    var list = root.querySelectorAll('*');
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (SKIP[el.tagName]) continue;
      if (DIALOG[el.tagName] && (el.open === true || el.hasAttribute('open'))) st.dialog = el;
      if (el.shadowRoot) walk(el.shadowRoot, out, st);
      test(el, out);
    }
  }

  function filter(els) {
    var rects = els.map(rectOf), seen = {}, items = [];
    els.forEach(function (el, i) {
      var r = rects[i];
      var k = Math.round(r.left / 6) + ',' + Math.round(r.top / 6) + ',' + Math.round(r.width / 6) + ',' + Math.round(r.height / 6);
      if (seen[k]) return;
      seen[k] = 1;
      items.push({ el: el, r: r });
    });
    return items.filter(function (a) {
      if (isExplicit(a.el)) return true;
      for (var i = 0; i < items.length; i++) {
        var b = items[i];
        if (b !== a && contains(b.r, a.r)) return false;   // Unterelemente einer Karte (z. B. Icon) entfallen
      }
      return true;
    }).map(function (i) { return i.el; });
  }

  function compute() {
    cursorCache = new WeakMap();
    var out = [], st = { dialog: null };
    walk(document, out, st);
    var res = filter(out);
    if (st.dialog) {
      var d = st.dialog;
      var inD = res.filter(function (e) { return inside(e, d); });
      if (inD.length) res = inD;
    }
    cache = { t: Date.now(), list: res, dialog: st.dialog };
    return res;
  }
  function candidates(force) {
    if (!force && Date.now() - cache.t < 400) return cache.list;
    return compute();
  }
  function invalidate() { cache.t = 0; }

  // ---------------------------------------------------------------- Auswahl
  function inHeader(el) {
    var n = el;
    while (n) { if (n.tagName === 'HA-DIALOG-HEADER') return true; n = parentOf(n); }
    return false;
  }
  function first(list) {
    var best = null, bs = 1e12;
    if (cache.dialog) {          // im Dialog zuerst die Bedienelemente, nicht den Zurück-Knopf im Kopf
      var body = list.filter(function (el) { return !inHeader(el); });
      if (body.length) list = body;
    }
    list.forEach(function (el) {
      var r = rectOf(el);
      if (r.width < 14) return;
      var inView = r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;
      var s = (inView ? 0 : 1e6) + Math.round(r.top / 40) * 4000 + r.left;
      if (s < bs) { bs = s; best = el; }
    });
    return best;
  }

  function ensureVisible(el) {
    var r = rectOf(el);
    if (r.top < 90 || r.bottom > innerHeight - 90 || r.left < 0 || r.right > innerWidth) {
      try { el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' }); } catch (e) {}
    }
  }

  var edit = null;                      // Farbwähler-Modus: { x, y, picker }
  function setEdit(e) {
    edit = e;
    ov.style.border = '4px solid ' + (e ? '#4CAF50' : ACCENT);
  }
  function pickerOf(el) {
    var n = el;
    while (n) { if (n.tagName && /COLOR-PICKER|HS-COLOR/.test(n.tagName)) return n; n = parentOf(n); }
    return null;
  }
  function sliderOf(el) {
    var n = el;
    while (n) { if (n.nodeType === 1 && isSlider(n)) return n; n = parentOf(n); }
    return null;
  }
  // Tasten an einen Regler senden (ha-control-slider wertet code/key aus); mehrere Schritte pro Druck
  function sliderKey(sl, key, n) {
    var t = (sl.shadowRoot && sl.shadowRoot.querySelector('[role=slider]')) || sl;
    try { t.focus && t.focus({ preventScroll: true }); } catch (e) {}
    var kc = { ArrowUp: 38, ArrowDown: 40, ArrowLeft: 37, ArrowRight: 39 }[key];
    function ev(type) {
      return new KeyboardEvent(type, { key: key, code: key, keyCode: kc, which: kc, bubbles: true, composed: true, cancelable: true });
    }
    // Schrittzahl so wählen, dass ein Druck etwa 5 % des Wertebereichs bewegt (z. B. Kelvin-Regler: 2000–6500, Schritt 1)
    var mn = parseFloat(sl.min !== undefined ? sl.min : t.getAttribute('aria-valuemin'));
    var mx = parseFloat(sl.max !== undefined ? sl.max : t.getAttribute('aria-valuemax'));
    var stp = parseFloat(sl.step) || 1;
    if (isFinite(mn) && isFinite(mx) && mx > mn) n = Math.max(1, Math.min(400, Math.round((mx - mn) * 0.05 / stp)));
    for (var i = 0; i < n; i++) t.dispatchEvent(ev('keydown'));
    t.dispatchEvent(ev('keyup'));
  }
  // Tippen an Koordinaten (Maus- und Touch-Ereignisse), z. B. um die Markierung im Farbwähler zu setzen
  function tapAt(x, y) {
    var el = deep(x, y);
    if (!el) return;
    fire(el, 'pointerdown', x, y, 1);
    fire(el, 'mousedown', x, y, 1);
    touchEvt('touchstart', el, x, y);
    fire(el, 'pointerup', x, y, 0);
    fire(el, 'mouseup', x, y, 0);
    touchEvt('touchend', el, x, y);
  }

  function select(el, quiet) {
    if (!el) return;
    if (edit) setEdit(null);
    toTop();
    cur = el;
    try {
      var br = getComputedStyle(el).borderRadius;
      ov.style.borderRadius = (br && br !== '0px') ? br : '12px';
    } catch (e) {}
    if (!quiet) ensureVisible(el);
  }

  function scrollParent(el) {
    var n = el;
    while (n) {
      try {
        var cs = getComputedStyle(n);
        if ((cs.overflowY === 'auto' || cs.overflowY === 'scroll') && n.scrollHeight > n.clientHeight + 4) return n;
      } catch (e) {}
      n = parentOf(n);
    }
    return document.scrollingElement || document.documentElement;
  }

  function slide(el, dir) {
    var step = dir === 'left' ? -1 : 1;
    if (el.tagName === 'INPUT' && el.type === 'range') {
      var s = parseFloat(el.step) || 1;
      el.value = String(parseFloat(el.value) + step * s);
      el.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
      el.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
      return;
    }
    var t = (el.shadowRoot && el.shadowRoot.querySelector('[role=slider]')) || el;
    var key = dir === 'left' ? 'ArrowLeft' : 'ArrowRight';
    ['keydown', 'keyup'].forEach(function (type) {
      t.dispatchEvent(new KeyboardEvent(type, { key: key, code: key, bubbles: true, composed: true, cancelable: true }));
    });
  }

  function nav(dir) {
    var list = candidates(false);
    if (!cur || !cur.isConnected || zero(cur) || (cache.dialog && !inside(cur, cache.dialog))) {
      select(first(list));
      return;
    }
    if (edit && edit.slider) {
      sliderKey(edit.slider, { up: 'ArrowUp', down: 'ArrowDown', left: 'ArrowLeft', right: 'ArrowRight' }[dir], 5);
      return;
    }
    if (edit) {
      var pr = rectOf(edit.picker), st = 10;
      var nx = edit.x + (dir === 'right' ? st : dir === 'left' ? -st : 0);
      var ny = edit.y + (dir === 'down' ? st : dir === 'up' ? -st : 0);
      edit.x = Math.max(pr.left + 4, Math.min(pr.right - 4, nx));
      edit.y = Math.max(pr.top + 4, Math.min(pr.bottom - 4, ny));
      tapAt(edit.x, edit.y);
      return;
    }
    if (isSlider(cur) && (dir === 'left' || dir === 'right')) { slide(cur, dir); return; }

    var cr = rectOf(cur);
    var cx = cr.left + cr.width / 2, cy = cr.top + cr.height / 2;
    var best = null, bestScore = 1e12;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === cur || !el.isConnected) continue;
      var r = rectOf(el);
      if (r.width < 14 || r.height < 14) continue;
      var x = r.left + r.width / 2, y = r.top + r.height / 2;
      var prim, perp, overlap;
      if (dir === 'right') { prim = x - cx; perp = Math.abs(y - cy); overlap = Math.min(r.bottom, cr.bottom) - Math.max(r.top, cr.top); }
      else if (dir === 'left') { prim = cx - x; perp = Math.abs(y - cy); overlap = Math.min(r.bottom, cr.bottom) - Math.max(r.top, cr.top); }
      else if (dir === 'down') { prim = y - cy; perp = Math.abs(x - cx); overlap = Math.min(r.right, cr.right) - Math.max(r.left, cr.left); }
      else { prim = cy - y; perp = Math.abs(x - cx); overlap = Math.min(r.right, cr.right) - Math.max(r.left, cr.left); }
      if (prim <= 4) continue;
      if (overlap > 0) perp *= 0.3;
      var score = prim + perp * 2.2;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    if (best) { select(best); return; }

    // nichts mehr in dieser Richtung: Seite weiterscrollen
    var sp = scrollParent(cur);
    var amt = (dir === 'down' || dir === 'right') ? 320 : -320;
    try {
      if (dir === 'up' || dir === 'down') sp.scrollBy({ top: amt, behavior: 'smooth' });
      else sp.scrollBy({ left: amt, behavior: 'smooth' });
    } catch (e) {}
  }

  // ---------------------------------------------------------------- OK-Taste
  function deep(x, y) {
    var el = document.elementFromPoint(x, y);
    while (el && el.shadowRoot) {
      var inner = el.shadowRoot.elementFromPoint(x, y);
      if (!inner || inner === el) break;
      el = inner;
    }
    return el;
  }

  function target() {
    if (!cur || !cur.isConnected) return null;
    var r = rectOf(cur);
    if (r.width < 2 || r.height < 2) return null;
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    var d = deep(x, y);
    var el = (d && (inside(d, cur) || inside(cur, d))) ? d : cur;
    return { el: el, x: x, y: y };
  }

  function fire(el, type, x, y, detail) {
    var down = type === 'mousedown' || type === 'pointerdown';
    var init = {
      bubbles: true, cancelable: true, composed: true, view: window,
      clientX: x, clientY: y, screenX: x, screenY: y,
      button: 0, buttons: down ? 1 : 0, detail: detail || 0
    };
    try {
      if (type.indexOf('pointer') === 0 && typeof PointerEvent !== 'undefined') {
        init.pointerId = 1; init.pointerType = 'mouse'; init.isPrimary = true;
        el.dispatchEvent(new PointerEvent(type, init));
      } else {
        el.dispatchEvent(new MouseEvent(type, init));
      }
    } catch (e) {}
  }

  // Echte Touch-Ereignisse nachbilden: Die Aktions-Auswertung von Home Assistant (tap/hold/double_tap)
  // startet ihren Halte-Timer bei touchstart und wertet bei touchend aus.
  function touchEvt(type, el, x, y) {
    try {
      var t = new Touch({ identifier: 1, target: el, clientX: x, clientY: y, pageX: x, pageY: y,
                          screenX: x, screenY: y, radiusX: 1, radiusY: 1, force: 1 });
      var end = type === 'touchend';
      var ev = new TouchEvent(type, {
        bubbles: true, cancelable: true, composed: true, view: window,
        touches: end ? [] : [t], targetTouches: end ? [] : [t], changedTouches: [t]
      });
      el.dispatchEvent(ev);
      return ev;
    } catch (e) { return null; }
  }

  ['hass-more-info', 'show-dialog', 'dialog-closed', 'hass-action'].forEach(function (n) {
    window.addEventListener(n, function (e) { console.log('TVDBG event ' + n + ' ' + JSON.stringify(e.detail || {}).slice(0, 200)); }, true);
  });
  window.addEventListener('error', function (e) { console.log('TVDBG error ' + e.message); });
  window.addEventListener('unhandledrejection', function (e) { console.log('TVDBG reject ' + e.reason); });

  // Diagnose: Zustand der Dialoge nach hass-more-info protokollieren
  function dumpDialogs() {
    try { dumpDialogs2(); } catch (e) { console.log('TVDBG dump-fehler ' + e); }
  }
  function dumpDialogs2() {
    var out = [];
    (function walk(root, depth) {
      if (!root || depth > 12 || out.length > 12) return;
      var all = root.querySelectorAll ? root.querySelectorAll('*') : [];
      for (var i = 0; i < all.length; i++) {
        var e = all[i], n = e.localName || '';
        if (/dialog|more-info|wa-dialog/.test(n)) {
          var r = e.getBoundingClientRect(), cs = getComputedStyle(e);
          out.push(n + ' open=' + (e.open !== undefined ? e.open : e.hasAttribute('open')) + ' rect=' +
            [r.left, r.top, r.width, r.height].map(Math.round).join(',') + ' op=' + cs.opacity + ' vis=' + cs.visibility +
            ' disp=' + cs.display + ' tf=' + cs.transform + ' clip=' + cs.clipPath + ' anim=' + (e.getAnimations ? e.getAnimations().length : '-'));
        }
        if (e.shadowRoot) walk(e.shadowRoot, depth + 1);
      }
    })(document, 0);
    console.log('TVDBG dialogs: ' + out.join(' | '));
  }
  console.log('TVDBG tv-nav v15 geladen');
  // Workaround: Der Detail-Dialog (Bottom-Sheet) erhält im Amazon-WebView die Höhe 0 und bleibt unsichtbar.
  // Dann Höhe erzwingen (Ursache vermutlich Viewport-Einheiten wie dvh/svh) und Werte protokollieren.
  function fixDialogs() {
    try {
      (function walk(root, depth) {
        if (!root || depth > 12) return;
        var all = root.querySelectorAll ? root.querySelectorAll('dialog') : [];
        for (var i = 0; i < all.length; i++) {
          var e = all[i];
          if (!e.open) continue;
          var r = e.getBoundingClientRect();
          if (r.height > 5) continue;
          var cs = getComputedStyle(e);
          console.log('TVDBG fix dialog vh=' + (function(){var d=document.createElement('div');d.style.cssText='position:absolute;height:100vh';document.body.appendChild(d);var h=d.getBoundingClientRect().height;d.remove();return h;})() + ' inner=' + window.innerHeight + ' h=' + cs.height + ' maxH=' + cs.maxHeight + ' minH=' + cs.minHeight +
            ' pos=' + cs.position + ' inset=' + cs.inset + ' margin=' + cs.margin + ' cls=' + e.className);
          // Inline-Styles würden von Lit beim nächsten Rendern überschrieben -> Stylesheet in den Shadow-Root einfügen
          var rn = e.getRootNode();
          if (rn && rn.appendChild && !rn.querySelector('style[data-tvfix]')) {
            var st = document.createElement('style');
            st.setAttribute('data-tvfix', '1');
            var ih = window.innerHeight;   // vh/dvh werten in diesem WebView zu 0 aus -> Pixelwerte verwenden
            st.textContent = 'dialog.drawer, dialog[open] { top: auto !important; bottom: 0 !important; height: auto !important; ' +
              'zoom: 0.7 !important; min-height: ' + Math.round(ih * 0.55 / 0.7) + 'px !important; ' +
              'max-height: ' + Math.round(ih * 0.95 / 0.7) + 'px !important; }';
            rn.appendChild(st);
          }
          e.style.setProperty('height', 'auto', 'important');
          e.style.setProperty('top', 'auto', 'important');
          e.style.setProperty('zoom', '0.7', 'important');
          e.style.setProperty('min-height', Math.round(window.innerHeight * 0.55 / 0.7) + 'px', 'important');
          e.style.setProperty('max-height', Math.round(window.innerHeight * 0.95 / 0.7) + 'px', 'important');
          var r2 = e.getBoundingClientRect();
          console.log('TVDBG fix danach rect=' + [r2.left, r2.top, r2.width, r2.height].map(Math.round).join(',') +
            ' h=' + getComputedStyle(e).height + ' tag=' + (rn.host ? rn.host.localName : '?'));
        }
        var any = root.querySelectorAll ? root.querySelectorAll('*') : [];
        for (var j = 0; j < any.length; j++) if (any[j].shadowRoot) walk(any[j].shadowRoot, depth + 1);
      })(document, 0);
    } catch (e) { console.log('TVDBG fix-fehler ' + e); }
  }
  window.addEventListener('hass-more-info', function () { [250, 600, 1200].forEach(function (t) { setTimeout(fixDialogs, t); }); }, true);

  window.addEventListener('hass-more-info', function () { setTimeout(dumpDialogs, 300); setTimeout(dumpDialogs, 1500); }, true);

  function pressDown() {
    var t = target();
    if (!t) { nav('down'); return; }
    if (pickerOf(cur) || sliderOf(cur)) { downTarget = { picker: true }; return; }
    downTarget = t;
    fire(t.el, 'pointerdown', t.x, t.y, 1);
    t.touch = !!touchEvt('touchstart', t.el, t.x, t.y);
    if (!t.touch) fire(t.el, 'mousedown', t.x, t.y, 1);
  }

  function pressUp() {
    var t = downTarget || target();
    downTarget = null;
    if (!t) return;
    if (t.picker) {
      if (edit) { setEdit(null); return; }
      var pk = pickerOf(cur), rr = rectOf(cur), sl = sliderOf(cur);
      if (pk) setEdit({ x: rr.left + rr.width / 2, y: rr.top + rr.height / 2, picker: pk });
      else if (sl) setEdit({ slider: sl });
      return;
    }
    var now = Date.now();
    var detail = (lastClick.el === t.el && now - lastClick.t < 450) ? 2 : 1;   // nur für Elemente ohne Touch-Auswertung
    lastClick = { t: now, el: t.el };
    fire(t.el, 'pointerup', t.x, t.y, 0);
    var handled = false;
    if (t.touch) {
      var ev = touchEvt('touchend', t.el, t.x, t.y);
      handled = !!(ev && ev.defaultPrevented);
      console.log('TVDBG touchend handled=' + handled + ' el=' + (t.el.localName || '?'));      // Home Assistant verhindert den Folge-Klick selbst
      if (!handled) { fire(t.el, 'mousedown', t.x, t.y, 1); }
    }
    if (!handled) {
      fire(t.el, 'mouseup', t.x, t.y, 0);
      fire(t.el, 'click', t.x, t.y, detail);
    }
    invalidate();
    setTimeout(invalidate, 350);
    setTimeout(toTop, 200);
    setTimeout(toTop, 700);
  }

  // ---------------------------------------------------------------- Seitenwechsel
  ['pushState', 'replaceState'].forEach(function (m) {
    var orig = history[m];
    history[m] = function () {
      var r = orig.apply(this, arguments);
      invalidate();
      return r;
    };
  });
  window.addEventListener('popstate', invalidate);
  window.addEventListener('location-changed', invalidate);

  // Startauswahl, sobald Karten sichtbar sind (und nach jedem Seitenwechsel)
  setInterval(function () {
    if (!cur || !cur.isConnected || zero(cur)) {
      var l = candidates(true);
      if (l.length) select(first(l), true);
    }
  }, 700);

  window.__tv = {
    nav: nav,
    down: pressDown,
    up: pressUp,
    setAccent: function (c) { ACCENT = c || ACCENT; paint(); },
    info: function () { return { candidates: candidates(true).length, cur: cur ? cur.tagName : null, dialog: !!cache.dialog }; }
  };
})();
