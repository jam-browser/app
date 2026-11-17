/**
 * 소리 끄기 (모든 프레임, 문서가 만들어질 때 가장 먼저 실행)
 *  - Jam Browser 앱은 어떤 소리도 내지 않는다 (사용자 요구사항)
 *  - 안드로이드 WebView 에는 "음소거" 기능이 없어서, 페이지 안에서 소리가 나는 길을 모두 막는다
 *      <video>/<audio> · new Audio() : 항상 muted + volume 0 (페이지가 바꾸려 해도 다시 0)
 *      Web Audio(AudioContext)       : 출력(destination)을 볼륨 0 노드로 바꿔치기
 *      음성 합성(speechSynthesis)    : 아무것도 하지 않음
 *      같은 출처 iframe 의 새 창 객체 : 거기에도 똑같이 적용 (빈 iframe 으로 우회하는 것 방지)
 *  - 영상 재생 자체는 막지 않는다 (Jam 영상 동기화는 소리 없이 그대로 동작)
 */
(function () {
  'use strict';

  function install(win) {
    try {
      if (!win || win.__jamMuted) return;
      Object.defineProperty(win, '__jamMuted', { value: true });
    } catch (e) {
      return;
    }

    // ---------- <video> / <audio> ----------
    var P = win.HTMLMediaElement && win.HTMLMediaElement.prototype;
    if (P) {
      var dMuted = Object.getOwnPropertyDescriptor(P, 'muted');
      var dVol = Object.getOwnPropertyDescriptor(P, 'volume');
      var wanted = new WeakMap(); // 페이지가 원한 값 (페이지에는 그 값을 보여 주고, 실제로는 항상 0)
      var force = function (el) {
        try {
          if (!dMuted.get.call(el)) dMuted.set.call(el, true);
          if (dVol.get.call(el) !== 0) dVol.set.call(el, 0);
        } catch (e) {}
      };
      var want = function (el) {
        var w = wanted.get(el);
        if (!w) wanted.set(el, (w = { muted: false, volume: 1 }));
        return w;
      };
      try {
        Object.defineProperty(P, 'muted', {
          configurable: false,
          enumerable: true,
          get: function () { return want(this).muted; },
          set: function (v) { want(this).muted = !!v; force(this); },
        });
        Object.defineProperty(P, 'volume', {
          configurable: false,
          enumerable: true,
          get: function () { return want(this).volume; },
          set: function (v) { var n = Number(v); if (n >= 0 && n <= 1) want(this).volume = n; force(this); },
        });
        var play = P.play;
        P.play = function () { force(this); return play.apply(this, arguments); };
      } catch (e) {}
      var onEvent = function (e) { if (e.target instanceof win.HTMLMediaElement) force(e.target); };
      ['play', 'playing', 'loadstart', 'loadedmetadata', 'volumechange', 'canplay'].forEach(function (t) {
        win.document.addEventListener(t, onEvent, true);
      });
      win.setInterval(function () {
        var list = win.document.querySelectorAll('video, audio');
        for (var i = 0; i < list.length; i++) force(list[i]);
      }, 1000);
    }

    // ---------- Web Audio ----------
    try {
      var AC = win.AudioContext || win.webkitAudioContext;
      var BAC = win.BaseAudioContext || (AC && Object.getPrototypeOf(AC.prototype).constructor);
      var dd = BAC && Object.getOwnPropertyDescriptor(BAC.prototype, 'destination');
      if (dd && dd.get) {
        var sinks = new WeakMap();
        var gainValue = Object.getOwnPropertyDescriptor(win.AudioParam.prototype, 'value');
        Object.defineProperty(BAC.prototype, 'destination', {
          configurable: false,
          enumerable: true,
          get: function () {
            var real = dd.get.call(this);
            if (win.OfflineAudioContext && this instanceof win.OfflineAudioContext) return real; // 파일로만 만드는 소리
            var g = sinks.get(this);
            if (!g) {
              g = this.createGain();
              gainValue.set.call(g.gain, 0);
              var param = g.gain;
              // 페이지가 볼륨을 올려도 0 으로 유지
              try {
                Object.defineProperty(param, 'value', { get: function () { return 0; }, set: function () {} });
                ['setValueAtTime', 'linearRampToValueAtTime', 'exponentialRampToValueAtTime', 'setTargetAtTime', 'setValueCurveAtTime'].forEach(function (k) {
                  param[k] = function () { return param; };
                });
              } catch (e) {}
              g.connect(real);
              try {
                ['maxChannelCount', 'channelCount', 'channelCountMode', 'channelInterpretation'].forEach(function (k) {
                  Object.defineProperty(g, k, { get: function () { return real[k]; }, set: function () {} });
                });
              } catch (e) {}
              sinks.set(this, g);
            }
            return g;
          },
        });
      }
    } catch (e) {}

    // ---------- 음성 합성 ----------
    try {
      if (win.speechSynthesis) win.speechSynthesis.speak = function () {};
    } catch (e) {}

    // ---------- 같은 출처 iframe ----------
    try {
      var IF = win.HTMLIFrameElement && win.HTMLIFrameElement.prototype;
      ['contentWindow', 'contentDocument'].forEach(function (k) {
        var d = IF && Object.getOwnPropertyDescriptor(IF, k);
        if (!d || !d.get) return;
        Object.defineProperty(IF, k, {
          configurable: false,
          enumerable: true,
          get: function () {
            var v = d.get.call(this);
            try {
              var w = k === 'contentWindow' ? v : v && v.defaultView;
              if (w) install(w);
            } catch (e) {}
            return v;
          },
        });
      });
    } catch (e) {}
  }

  install(window);
})();
