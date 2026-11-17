/**
 * Jam 탭 웹페이지 안에서 실행되는 스크립트 (데스크톱 src/webview-preload.js 를 폰용으로 옮긴 것)
 *  - 내 화면 영역(뷰포트) / 스크롤 / 터치 위치 / 동영상 상태를 앱으로 보낸다
 *  - 다른 참여자의 커서, 보고 있는 영역(색 사각형 + 해상도), 클릭 표시를 페이지 위에 그린다
 *  - 다른 사이트 이동 권한이 없으면 다른 사이트로 가는 링크를 잠근다 (진짜 잠금은 앱이 한다)
 *  - 동영상 재생 위치를 맞춘다 (유튜브 광고 중에는 맞추지 않고, 광고가 끝나면 따라간다)
 *  - 사이트가 클립보드에 글자를 넣으면 앱에 알린다 (명령어 붙여넣기 사기 대비)
 *
 * 데스크톱과 같은 좌표 규칙(앵커)을 써서 PC ↔ 폰처럼 화면 크기가 달라도 같은 내용을 가리킨다.
 * 앱과는 jamBridge(WebMessageListener)로만 주고받는다: {ch, d} JSON 문자열.
 * 페이지의 CSP / Trusted Types에 막히지 않도록 innerHTML, <style> 없이 DOM API와 인라인 스타일만 쓴다.
 */
(function () {
  'use strict';
  if (window.top !== window) return; // 맨 위 문서에서만
  if (window.__jamPage) return;
  Object.defineProperty(window, '__jamPage', { value: true });

  var bridge = window.jamBridge;
  if (!bridge) return;
  // 페이지 스크립트가 다리를 쓰지 못하게 전역에서 치운다 (가능한 경우)
  try { delete window.jamBridge; } catch (e) {}

  var send = function (ch, d) {
    try { bridge.postMessage(JSON.stringify({ ch: ch, d: d === undefined ? null : d })); } catch (e) {}
  };
  var handlers = {};
  var on = function (ch, fn) { handlers[ch] = fn; };
  bridge.onmessage = function (ev) {
    var m;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    var fn = m && handlers[m.ch];
    if (fn) try { fn(m.d); } catch (e) {}
  };

  // ======================================================================
  // 클립보드 감시
  // ======================================================================
  (function () {
    var fire = function (kind, text) {
      var selection = String(window.getSelection ? window.getSelection() : '').slice(0, 4000);
      setTimeout(function () { send('jam:clipboard', { kind: kind, text: typeof text === 'string' ? text.slice(0, 4000) : null, selection: selection }); }, 150);
    };
    var c = navigator.clipboard;
    try {
      if (c && c.writeText) { var wt = c.writeText.bind(c); c.writeText = function (t) { fire('writeText', String(t)); return wt(t); }; }
      if (c && c.write) { var w = c.write.bind(c); c.write = function (items) { fire('write', null); return w(items); }; }
      var ex = Document.prototype.execCommand;
      Document.prototype.execCommand = function (cmd) {
        var k = String(cmd).toLowerCase();
        if (k === 'copy' || k === 'cut') fire('execCommand', null);
        return ex.apply(this, arguments);
      };
    } catch (e) {}
    document.addEventListener('copy', function () { fire('copy-event', null); }, true);
    document.addEventListener('cut', function () { fire('copy-event', null); }, true);
    window.addEventListener('load', function () {
      setTimeout(function () {
        var re = /clipboard\s*\.\s*write(Text)?\s*\(|execCommand\s*\(\s*['"](copy|cut)['"]/i;
        var scripts = document.querySelectorAll('script:not([src])');
        for (var i = 0; i < scripts.length; i++) if (re.test(scripts[i].textContent || '')) return send('jam:clipboard-site', { where: 'script' });
      }, 500);
    });
  })();

  function throttle(fn, ms) {
    var last = 0, timer = null, lastArgs = null;
    return function () {
      lastArgs = arguments;
      var wait = ms - (Date.now() - last);
      if (wait <= 0) { last = Date.now(); fn.apply(null, lastArgs); }
      else if (!timer) timer = setTimeout(function () { timer = null; last = Date.now(); fn.apply(null, lastArgs); }, wait);
    };
  }

  // shared/site.js 의 siteOf 와 같은 규칙
  var SECOND_LEVEL = { co: 1, or: 1, go: 1, ac: 1, ne: 1, re: 1, pe: 1, com: 1, net: 1, org: 1, gov: 1, edu: 1, mil: 1 };
  function siteOf(u) {
    var host;
    try {
      var p = new URL(u, location.href);
      if (p.protocol !== 'http:' && p.protocol !== 'https:') return null;
      host = p.hostname.toLowerCase();
    } catch (e) { return null; }
    if (host === 'localhost' || /^[\d.]+$/.test(host) || host.indexOf(':') >= 0) return host;
    var parts = host.split('.');
    if (parts.length <= 2) return host;
    var n = parts.length;
    var take = parts[n - 1].length === 2 && SECOND_LEVEL[parts[n - 2]] ? 3 : 2;
    return parts.slice(-take).join('.');
  }

  var docEl = function () { return document.scrollingElement || document.documentElement; };
  var docSize = function () { var el = docEl(); return { w: Math.max(el.scrollWidth, 1), h: Math.max(el.scrollHeight, 1) }; };
  function css(el, styles) {
    for (var k in styles) el.style.setProperty(k, styles[k], 'important');
    return el;
  }
  // 손가락으로 확대(핀치 줌)했으면 실제로 보이는 영역은 visualViewport
  function visible() {
    var vv = window.visualViewport;
    if (vv) return { left: vv.pageLeft, top: vv.pageTop, w: vv.width, h: vv.height, ox: vv.offsetLeft, oy: vv.offsetTop, scale: vv.scale || 1 };
    return { left: window.scrollX, top: window.scrollY, w: window.innerWidth, h: window.innerHeight, ox: 0, oy: 0, scale: 1 };
  }

  // ======================================================================
  // 앵커: 해상도·창 크기·확대 배율이 달라도 "같은 내용"을 가리키기 위한 좌표 (데스크톱과 같은 규칙)
  // ======================================================================
  function pathOf(el) {
    var parts = [];
    while (el && el.nodeType === 1 && el !== document.documentElement) {
      if (el.id && /^[A-Za-z][\w-]*$/.test(el.id) && document.querySelectorAll('#' + el.id).length === 1) {
        parts.unshift('#' + el.id);
        break;
      }
      var i = 1, sib = el;
      while ((sib = sib.previousElementSibling)) if (sib.tagName === el.tagName) i++;
      parts.unshift(el.tagName.toLowerCase() + ':nth-of-type(' + i + ')');
      el = el.parentElement;
    }
    return parts.join('>');
  }

  function isPinned(el) {
    for (var e = el; e && e !== document.body; e = e.parentElement) {
      var pos = getComputedStyle(e).position;
      if (pos === 'fixed' || pos === 'sticky') return true;
    }
    return false;
  }

  function anchorAt(x, y, skipPinned) {
    var el = document.elementFromPoint(x, y);
    if (!el || el === document.documentElement || el === document.body) return null;
    if (el.tagName === 'JAM-PRESENCE') return null;
    if (skipPinned && isPinned(el)) return null;
    var r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return null;
    var p = pathOf(el);
    if (!p || p.length > 600) return null;
    return { p: p, fx: (x - r.left) / r.width, fy: (y - r.top) / r.height };
  }

  function resolveAnchor(a) {
    if (!a || typeof a.p !== 'string') return null;
    var el;
    try { el = document.querySelector(a.p); } catch (e) { return null; }
    if (!el) return null;
    var r = el.getBoundingClientRect();
    if (r.width < 1 && r.height < 1) return null;
    return { x: r.left + window.scrollX + a.fx * r.width, y: r.top + window.scrollY + a.fy * r.height };
  }

  function edgeAnchor(edge) {
    var v = visible();
    var xs = [0.5, 0.35, 0.65, 0.2, 0.8].map(function (f) { return v.ox + v.w * f; });
    for (var step = 0; step < 12; step++) {
      var off = 4 + step * 24;
      var y = edge === 'top' ? v.oy + off : v.oy + v.h - off;
      if (off > v.h) break;
      for (var i = 0; i < xs.length; i++) {
        var a = anchorAt(xs[i], y, true);
        if (a) { a.off = off; return a; }
      }
    }
    return null;
  }

  // ======================================================================
  // 내 화면 영역 / 스크롤
  // ======================================================================
  var suppressScrollUntil = 0;

  function viewport(user) {
    var el = docEl();
    var s = docSize();
    var v = visible();
    var maxX = el.scrollWidth - v.w;
    var maxY = el.scrollHeight - v.h;
    return {
      sx: maxX > 0 ? v.left / maxX : 0,
      sy: maxY > 0 ? v.top / maxY : 0,
      vx: v.left / s.w,
      vy: v.top / s.h,
      vw: Math.min(v.w / s.w, 1),
      vh: Math.min(v.h / s.h, 1),
      top: edgeAnchor('top'),
      bot: edgeAnchor('bottom'),
      w: Math.round(v.w),
      h: Math.round(v.h),
      z: Math.round(window.devicePixelRatio * v.scale * 100) / 100,
      user: user,
    };
  }

  var lastViewKey = '';
  function sendViewport(user) {
    var v = viewport(user);
    var key = JSON.stringify([v.top, v.bot, v.vy, v.w, v.h, v.z]);
    if (!user && key === lastViewKey) return;
    lastViewKey = key;
    send('jam:viewport', v);
  }

  var lastUserInput = 0;
  ['wheel', 'keydown', 'pointerdown', 'touchstart', 'touchmove'].forEach(function (type) {
    window.addEventListener(type, function (e) { if (e.isTrusted) lastUserInput = Date.now(); }, { capture: true, passive: true });
  });
  var isUserScroll = function () { return Date.now() - lastUserInput < 1000 && Date.now() >= suppressScrollUntil; };

  var onScroll = throttle(function () { sendViewport(isUserScroll()); }, 100);
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', throttle(function () { sendViewport(false); }, 200));
  if (window.visualViewport) {
    window.visualViewport.addEventListener('scroll', onScroll);
    window.visualViewport.addEventListener('resize', throttle(function () { sendViewport(isUserScroll()); }, 200));
  }
  var onReady = function () { sendViewport(false); };
  if (document.readyState === 'loading') window.addEventListener('DOMContentLoaded', onReady);
  else onReady();
  setInterval(function () { sendViewport(false); }, 1500);

  function scrollToAnchor(top, x, y, behavior) {
    var el = docEl();
    var v = visible();
    suppressScrollUntil = Date.now() + (behavior === 'smooth' ? 900 : 400);
    var pos = resolveAnchor(top);
    window.scrollTo({
      left: x * Math.max(el.scrollWidth - v.w, 0),
      top: pos ? pos.y - (top.off || 0) : y * Math.max(el.scrollHeight - v.h, 0),
      behavior: behavior,
    });
  }
  on('jam:scroll-to', function (d) { scrollToAnchor(d.top, d.x, d.y, 'instant'); });
  on('jam:follow-view', function (d) { scrollToAnchor(d.top, 0, d.vy / Math.max(1 - (d.vh || 0), 0.0001), 'instant'); });
  on('jam:scroll-to-view', function (v) {
    var view = views_.get(v.id);
    var d = view ? view.data : v;
    scrollToAnchor(d.top, 0, d.vy / Math.max(1 - (d.vh || 0), 0.0001), 'smooth');
  });

  // ======================================================================
  // 내 커서 / 클릭
  //  - 마우스(태블릿·DeX)는 데스크톱처럼 움직임을 그대로 보낸다
  //  - 손가락은 누른 곳을 "커서 + 클릭"으로 보여 주고, 손을 떼고 2.5초 뒤 커서를 숨긴다
  // ======================================================================
  var pointerShown = false;
  var hideTimer = null;
  var sendCursor = throttle(function (e) {
    var s = docSize();
    send('jam:cursor', { x: e.pageX / s.w, y: e.pageY / s.h, a: anchorAt(e.clientX, e.clientY) });
  }, 40);
  var hideCursor = function () {
    clearTimeout(hideTimer);
    if (!pointerShown) return;
    pointerShown = false;
    send('jam:cursor', null);
  };
  document.addEventListener('pointermove', function (e) {
    if (e.pointerType !== 'mouse' && !e.isPrimary) return;
    if (e.pointerType !== 'mouse' && e.buttons === 0) return;
    pointerShown = true;
    sendCursor(e);
  }, { passive: true, capture: true });
  document.addEventListener('pointerdown', function (e) {
    if (!e.isPrimary) return;
    clearTimeout(hideTimer);
    pointerShown = true;
    var s = docSize();
    var a = anchorAt(e.clientX, e.clientY);
    send('jam:cursor', { x: e.pageX / s.w, y: e.pageY / s.h, a: a });
    send('jam:click', { x: e.pageX / s.w, y: e.pageY / s.h, a: a });
  }, { capture: true, passive: true });
  var later = function (e) {
    if (e.pointerType === 'mouse') return;
    clearTimeout(hideTimer);
    hideTimer = setTimeout(hideCursor, 2500);
  };
  document.addEventListener('pointerup', later, { capture: true, passive: true });
  document.addEventListener('pointercancel', later, { capture: true, passive: true });
  document.addEventListener('mouseleave', hideCursor, { capture: true });
  window.addEventListener('blur', hideCursor);
  document.addEventListener('visibilitychange', function () { if (document.hidden) hideCursor(); });
  window.addEventListener('pagehide', hideCursor);

  // ======================================================================
  // 오버레이 레이어 (다른 사람 커서 / 화면 사각형 / 클릭)
  // ======================================================================
  var SVG_NS = 'http://www.w3.org/2000/svg';
  var FONT = '"Noto Sans KR","Malgun Gothic",Roboto,sans-serif';
  var layer = null;
  var cursors = new Map();
  var views_ = new Map();

  function getLayer() {
    if (layer && layer.host.isConnected) return layer;
    var host = css(document.createElement('jam-presence'), {
      position: 'absolute', top: '0', left: '0', width: '0', height: '0', overflow: 'visible',
      'z-index': '2147483647', 'pointer-events': 'none', display: 'block',
    });
    var root = host.attachShadow({ mode: 'closed' });
    var views = document.createElement('div');
    var cursorsBox = document.createElement('div');
    root.append(views, cursorsBox);
    (document.body || document.documentElement).appendChild(host);
    layer = { host: host, root: root, views: views, cursorsBox: cursorsBox };
    cursors.forEach(function (c) { cursorsBox.appendChild(c.el); });
    views_.forEach(function (v) { views.appendChild(v.el); });
    return layer;
  }

  function buildCursor() {
    var el = css(document.createElement('div'), { position: 'absolute', left: '0', top: '0', transition: 'transform 80ms linear', 'will-change': 'transform' });
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('width', '18');
    svg.setAttribute('height', '22');
    svg.setAttribute('viewBox', '0 0 18 22');
    css(svg, { display: 'block', filter: 'drop-shadow(0 1px 2px rgba(0,0,0,.45))' });
    var path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('d', 'M1 1 L1 17 L5.5 13 L8.5 20.5 L11.5 19.2 L8.6 12 L15 12 Z');
    path.setAttribute('stroke', '#fff');
    path.setAttribute('stroke-width', '1.5');
    path.setAttribute('stroke-linejoin', 'round');
    svg.appendChild(path);
    var label = css(document.createElement('div'), {
      position: 'absolute', left: '14px', top: '16px', padding: '2px 8px', 'border-radius': '10px',
      font: '600 12px/1.4 ' + FONT, color: '#111', 'white-space': 'nowrap', 'box-shadow': '0 1px 3px rgba(0,0,0,.3)',
    });
    el.append(svg, label);
    return { el: el, path: path, label: label, data: null };
  }

  function layoutCursor(c) {
    var s = docSize();
    var pos = resolveAnchor(c.data.a) || { x: c.data.x * s.w, y: c.data.y * s.h };
    css(c.el, { transform: 'translate(' + pos.x + 'px, ' + pos.y + 'px)' });
  }

  on('jam:cursor', function (data) {
    var box = getLayer().cursorsBox;
    var c = cursors.get(data.id);
    if (!c) { c = buildCursor(); cursors.set(data.id, c); }
    if (!c.el.isConnected) box.appendChild(c.el);
    c.data = data;
    c.path.setAttribute('fill', data.color);
    c.label.textContent = data.name;
    css(c.label, { background: data.color });
    layoutCursor(c);
  });
  function removeCursor(id) {
    var c = cursors.get(id);
    if (!c) return;
    c.el.remove();
    cursors.delete(id);
  }
  on('jam:cursor-remove', removeCursor);

  function buildView() {
    var el = css(document.createElement('div'), {
      position: 'absolute', 'box-sizing': 'border-box', 'border-radius': '8px', 'border-width': '2px',
      'border-style': 'solid', transition: 'left 120ms, top 120ms, width 120ms, height 120ms',
    });
    var label = css(document.createElement('div'), {
      position: 'absolute', left: '-2px', top: '-2px', padding: '2px 8px', 'border-radius': '8px 0 8px 0',
      font: '600 11px/1.5 ' + FONT, color: '#111', 'white-space': 'nowrap',
    });
    el.appendChild(label);
    return { el: el, label: label, data: null };
  }

  function layoutView(v) {
    var s = docSize();
    var d = v.data;
    var t = resolveAnchor(d.top);
    var b = resolveAnchor(d.bot);
    var top = d.vy * s.h;
    var height = d.vh * s.h;
    if (t && b && b.y > t.y) { top = t.y - (d.top.off || 0); height = b.y + (d.bot.off || 0) - top; }
    else if (t) top = t.y - (d.top.off || 0);
    else if (b) top = b.y + (d.bot.off || 0) - height;
    var fullWidth = d.vw >= 0.99;
    var left = fullWidth ? 0 : d.vx * s.w;
    var width = fullWidth ? s.w : d.vw * s.w;
    css(v.el, { left: left + 'px', top: top + 'px', width: Math.max(width, 40) + 'px', height: Math.max(height, 30) + 'px' });
  }

  on('jam:view', function (data) {
    var box = getLayer().views;
    var v = views_.get(data.id);
    if (!v) { v = buildView(); views_.set(data.id, v); }
    if (!v.el.isConnected) box.appendChild(v.el);
    v.data = data;
    css(v.el, { 'border-color': data.color, background: data.color + '14' });
    css(v.label, { background: data.color });
    v.label.textContent = data.name + ' · ' + data.w + '×' + data.h + (data.z && data.z !== 1 ? ' · ' + Math.round(data.z * 100) + '%' : '');
    layoutView(v);
  });
  function removeView(id) {
    var v = views_.get(id);
    if (!v) return;
    v.el.remove();
    views_.delete(id);
  }
  on('jam:view-remove', removeView);

  var relayout = function () {
    cursors.forEach(layoutCursor);
    views_.forEach(layoutView);
  };
  window.addEventListener('resize', throttle(relayout, 100));
  setInterval(relayout, 1500);

  on('jam:clear', function () {
    Array.from(cursors.keys()).forEach(removeCursor);
    Array.from(views_.keys()).forEach(removeView);
    locked = false;
  });

  on('jam:click', function (d) {
    var root = getLayer().root;
    var s = docSize();
    var pos = resolveAnchor(d.a) || { x: d.x * s.w, y: d.y * s.h };
    var p = css(document.createElement('div'), {
      position: 'absolute', left: pos.x + 'px', top: pos.y + 'px', width: '28px', height: '28px',
      margin: '-14px 0 0 -14px', 'border-radius': '50%', border: '3px solid ' + d.color, 'box-sizing': 'border-box',
    });
    root.appendChild(p);
    try {
      p.animate([{ transform: 'scale(.3)', opacity: 1 }, { transform: 'scale(1.8)', opacity: 0 }], { duration: 700, easing: 'ease-out', fill: 'forwards' });
    } catch (e) {}
    setTimeout(function () { p.remove(); }, 750);
  });

  // ======================================================================
  // 다른 사이트로 가는 링크 잠금 (권한이 없을 때) — 진짜 잠금은 앱(shouldOverrideUrlLoading)이 한다
  // ======================================================================
  var locked = false;
  on('jam:policy', function (p) { locked = !!(p && p.locked); });
  document.addEventListener('click', function (e) {
    if (!locked) return;
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a || a.target === '_blank') return;
    var s = siteOf(a.href);
    if (!s || s === siteOf(location.href)) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    send('jam:blocked', a.href);
  }, { capture: true });

  // ======================================================================
  // 동영상 재생 맞추기 (+ 유튜브 광고 예외)
  // ======================================================================
  var video = null;
  var adShowing = false;
  var ignoreLocalUntil = 0;
  var localTimer = null;
  var initTimer = null;
  var MEDIA_EVENTS = ['play', 'pause', 'seeked', 'ratechange'];

  function isAd() {
    return !!document.querySelector('.html5-video-player.ad-showing, .html5-video-player.ad-interrupting, .ad-showing video, .ytp-ad-player-overlay');
  }

  function pickVideo() {
    var best = null;
    var bestArea = 20000; // 너무 작은 미리보기 영상은 무시 (폰 화면은 작아서 데스크톱보다 기준을 낮춤)
    var list = document.querySelectorAll('video');
    for (var i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      var area = r.width * r.height;
      if (area > bestArea) { best = list[i]; bestArea = area; }
    }
    return best;
  }

  var mediaState = function () { return { playing: !video.paused && !video.ended, time: video.currentTime, rate: video.playbackRate }; };

  function onLocalMedia() {
    if (Date.now() < ignoreLocalUntil) return;
    clearTimeout(localTimer);
    localTimer = setTimeout(function () {
      if (!video || adShowing || isAd() || Date.now() < ignoreLocalUntil) return;
      send('jam:media', mediaState());
    }, 350);
  }

  function attachVideo(v) {
    if (video) MEDIA_EVENTS.forEach(function (ev) { video.removeEventListener(ev, onLocalMedia); });
    video = v;
    clearTimeout(initTimer);
    if (!v) return;
    MEDIA_EVENTS.forEach(function (ev) { v.addEventListener(ev, onLocalMedia); });
    ignoreLocalUntil = Date.now() + 2000;
    send('jam:media-ready');
    initTimer = setTimeout(function () {
      if (video === v && !adShowing && !isAd()) send('jam:media-init', mediaState());
    }, 2200);
  }

  setInterval(function () {
    var v = pickVideo();
    if (v !== video) attachVideo(v);
    var ad = !!video && isAd();
    if (ad !== adShowing) {
      adShowing = ad;
      send('jam:ad', ad);
      if (!ad && video) {
        ignoreLocalUntil = Date.now() + 1500;
        send('jam:media-ready');
      }
    }
    if (video && !adShowing && !video.paused) send('jam:media-tick', mediaState());
  }, 1000);

  on('jam:media-apply', function (s) {
    if (!video || adShowing || isAd()) return;
    ignoreLocalUntil = Date.now() + 800;
    var tolerance = s.playing ? 1.0 : 0.3;
    if (Math.abs(video.currentTime - s.time) > tolerance) video.currentTime = s.time;
    if (s.rate && video.playbackRate !== s.rate) video.playbackRate = s.rate;
    if (s.playing && video.paused) { var p = video.play(); if (p && p.catch) p.catch(function () {}); }
    if (!s.playing && !video.paused) video.pause();
  });

  send('jam:ready');
})();
