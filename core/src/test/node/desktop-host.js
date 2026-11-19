'use strict';
/**
 * 호환성 테스트용 "데스크톱 호스트" 흉내
 *   node desktop-host.js <데스크톱 저장소 폴더> [--access approval|open|friends] [--relay <jam-relay.js 경로>]
 *
 *  - 데스크톱 저장소의 진짜 server/jam-server.js 와 shared/jam-crypto.js 를 그대로 불러 쓴다 (복사본이 아님)
 *  - 데스크톱 앱과 똑같이 방을 만들고(create), 초대 코드를 출력한다
 *  - --relay 를 주면 backend 저장소의 중계 서버 + 데스크톱의 relay-tunnel.js 도 띄우고 jamr:// 초대 코드도 출력
 *  - 호스트가 받은 메시지는 한 줄 JSON 으로 stdout 에 쓴다:  {"ev":"host-msg","msg":{...}}
 *  - stdin 한 줄 JSON 명령:  {"cmd":"send","msg":{...}}  /  {"cmd":"drop-guests"}  /  {"cmd":"quit"}
 *  - 참여 요청은 자동 승인 (이름이 "거절" 로 시작하면 거절, 근처 페어링 요청도 같음)
 *  - --beacon <UDP 포트>: 근처 Jam 알림(server/nearby.js)을 0.5초마다 보낸다
 *  - 중계 서버가 본 바이트는 모두 모아 두었다가 {"cmd":"relay-seen","find":[...]} 로 평문이 보였는지 답한다
 */
const path = require('path');
const crypto = require('crypto');
const readline = require('readline');

const args = process.argv.slice(2);
const desktopDir = path.resolve(args[0]);
const opt = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : null;
};
const access = opt('--access') || 'approval';
const bindHost = opt('--host') || '127.0.0.1';
const inviteHost = opt('--invite-host') || '127.0.0.1';
const relayPath = opt('--relay') ? path.resolve(opt('--relay')) : null;

// ws 모듈은 데스크톱 저장소의 node_modules 에서 찾는다
const req = (m) => require(require.resolve(m, { paths: [desktopDir, path.dirname(relayPath || desktopDir)] }));
const WebSocket = req('ws');
const { createJamServer } = require(path.join(desktopDir, 'server', 'jam-server.js'));
const { newSecret, deriveKeys, seal, open, C2S, S2C } = require(path.join(desktopDir, 'shared', 'jam-crypto.js'));

const out = (o) => {
  process.stdout.write(JSON.stringify(o) + '\n');
  if (global.__record) global.__record(o);
};

async function main() {
  const relayKey = crypto.randomBytes(24).toString('base64url');
  const jam = await createJamServer({ port: Number(opt('--port') || 0), host: bindHost, localCreateOnly: false, relayKey });
  let relay = null;
  let tunnel = null;
  const seen = [];
  if (relayPath) {
    const { createRelay } = require(relayPath);
    const { startRelayTunnel } = require(path.join(desktopDir, 'server', 'relay-tunnel.js'));
    relay = await createRelay({ port: 0, host: '127.0.0.1', onFrame: (_l, _d, data) => seen.push(Buffer.from(data)) });
    tunnel = await startRelayTunnel({ relayUrl: `ws://127.0.0.1:${relay.port}`, localPort: jam.port, relayKey });
  }

  const secret = newSecret();
  const keys = deriveKeys(secret);
  const ws = new WebSocket(`ws://127.0.0.1:${jam.port}`);
  const send = (m) => ws.send(seal(keys, m, C2S));
  let welcomed = false;

  ws.on('message', (raw, isBinary) => {
    if (!isBinary) {
      const msg = JSON.parse(raw);
      if (msg.type === 'hello') {
        ws.send(JSON.stringify({ type: 'create', name: 'PC호스트', secret, url: 'https://example.com/start', title: '시작 페이지', access }));
      } else out({ ev: 'host-error', msg });
      return;
    }
    let msg;
    try {
      msg = open(keys, raw, S2C);
    } catch {
      return out({ ev: 'host-undecryptable' });
    }
    if (msg.type === 'welcome' && !welcomed) {
      welcomed = true;
      const code = msg.room.code;
      // --beacon <UDP 포트>: 데스크톱과 같은 "근처 Jam" 알림을 보낸다 (server/nearby.js 그대로)
      if (opt('--beacon')) {
        const { startBeacon } = require(path.join(desktopDir, 'server', 'nearby.js'));
        startBeacon({
          id: 'testhost' + crypto.randomBytes(4).toString('hex'),
          port: jam.port,
          udpPort: Number(opt('--beacon')),
          intervalMs: 500,
          getRooms: () => [{ code, name: 'PC호스트', access, n: [...jam.rooms.values()][0]?.members.size || 1 }],
        });
      }
      out({
        ev: 'ready',
        code,
        secret,
        hostId: msg.you.id,
        invite: `jam://${inviteHost}:${jam.port}/${code}/${secret}`,
        relayInvite: tunnel ? `jamr://127.0.0.1:${relay.port}/${tunnel.route}/${code}/${secret}` : null,
      });
      return;
    }
    if (msg.type === 'join-request') {
      // --pair-delay <ms>: 근처 페어링 요청은 잠시 뒤에 수락 (에뮬레이터에서 6자리 화면을 보려고)
      const delay = msg.pair ? Number(opt('--pair-delay') || 0) : 0;
      setTimeout(() => send({ type: 'approve', id: msg.id, ok: !String(msg.name).startsWith('거절') }), delay);
    }
    out({ ev: 'host-msg', msg });
  });
  ws.on('close', () => out({ ev: 'host-closed' }));

  // --control <포트>: 127.0.0.1 HTTP 로도 같은 명령을 받는다 (에뮬레이터 수동 확인용)
  //   POST /cmd  {"cmd":"send","msg":{...}}     GET /events?since=N  → 그동안 쌓인 이벤트
  const controlPort = opt('--control');
  if (controlPort) {
    const http = require('http');
    const log = [];
    global.__record = (o) => log.push(o);
    http
      .createServer((q, s) => {
        if (q.method === 'GET' && q.url.startsWith('/events')) {
          const since = Number(new URL(q.url, 'http://x').searchParams.get('since') || 0);
          s.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          return s.end(JSON.stringify({ next: log.length, events: log.slice(since) }));
        }
        let body = '';
        q.on('data', (d) => (body += d));
        q.on('end', () => {
          try {
            handle(JSON.parse(body));
          } catch (e) {
            s.writeHead(400);
            return s.end(String(e));
          }
          s.writeHead(200);
          s.end('ok');
        });
      })
      .listen(Number(controlPort), '127.0.0.1');
  }

  const rl = readline.createInterface({ input: process.stdin });
  rl.on('line', (line) => {
    try {
      handle(JSON.parse(line));
    } catch {}
  });
  rl.on('close', () => {
    if (!controlPort) process.exit(0);
  });

  // 화면 미러링 흉내: 미리 인코딩한 조각 파일(첫 줄 {codec,w,h,fps}, 다음 줄마다 {k,ts,d})을
  // 데스크톱 mirror.js 의 sendChunk 와 똑같이 32KB 조각(mv)으로 보낸다. 끝나면 처음부터 다시 (loop)
  let mirrorTimer = null;
  function mirrorPlay(c) {
    const fs = require('fs');
    const lines = fs.readFileSync(c.file, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    const head = lines.shift();
    const sid = 'testmirror' + crypto.randomBytes(3).toString('hex');
    clearTimeout(mirrorTimer);
    send({ type: 'mirror-start', sid, w: head.w, h: head.h, title: c.title || '시험 화면' });
    out({ ev: 'mirror-started', sid, codec: head.codec });
    let f = 0;
    let idx = 0;
    const PIECE = 32 * 1024;
    const tick = () => {
      if (idx >= lines.length) {
        if (!c.loop) return out({ ev: 'mirror-done', frames: f });
        idx = 0;
      }
      const ch = lines[idx++];
      const buf = Buffer.from(ch.d, 'base64');
      const n = Math.max(1, Math.ceil(buf.length / PIECE));
      const ts = Math.round(performance.now() * 1000);
      for (let i = 0; i < n; i++) {
        const m = { type: 'mv', sid, f, k: ch.k, i, n, ts, d: buf.subarray(i * PIECE, (i + 1) * PIECE).toString('base64') };
        if (ch.k && i === 0) Object.assign(m, { w: head.w, h: head.h, c: head.codec });
        send(m);
      }
      f++;
      mirrorTimer = setTimeout(tick, 1000 / (head.fps || 15));
    };
    setTimeout(tick, 300);
  }

  // 같이 보는 파일 흉내: 데스크톱 jamfiles.js 의 pack 으로 묶고 files.js 처럼 file-offer → file-up(32KB) → file-up-end
  function fileShare(c) {
    const fs = require('fs');
    const { pack } = require(path.join(desktopDir, 'src', 'jamfiles.js'));
    const name = c.name || path.basename(c.file);
    const bytes = c.text != null ? Buffer.from(String(c.text), 'utf8') : fs.readFileSync(c.file);
    const packed = pack([{ p: name, bytes }], name);
    const sha = crypto.createHash('sha256').update(packed).digest('hex');
    const fid = Array.from(crypto.randomBytes(16), (x) => 'abcdefghijklmnopqrstuvwxyz0123456789'[x % 36]).join('');
    send({ type: 'file-offer', fid, name, kind: c.kind || 'pdf', size: packed.length, sha, entry: name, count: 1, net: false });
    const PIECE = 32 * 1024;
    const n = Math.ceil(packed.length / PIECE);
    for (let i = 0; i < n; i++) send({ type: 'file-up', fid, i, d: packed.subarray(i * PIECE, (i + 1) * PIECE).toString('base64') });
    send({ type: 'file-up-end', fid });
    out({ ev: 'file-shared', fid, sha, size: packed.length });
  }

  async function handle(c) {
    if (c.cmd === 'send') send(c.msg);
    else if (c.cmd === 'drop-guests') {
      // 와이파이 끊김 흉내: 서버 쪽에서 손님 연결을 강제로 끊는다
      for (const room of jam.rooms.values()) {
        for (const m of room.members.values()) if (m.id !== room.hostId) m.ws.terminate();
      }
      out({ ev: 'dropped' });
    } else if (c.cmd === 'mirror-play') {
      mirrorPlay(c);
    } else if (c.cmd === 'file-share') {
      fileShare(c);
    } else if (c.cmd === 'file-remove') {
      send({ type: 'file-remove', fid: c.fid });
    } else if (c.cmd === 'mirror-stop') {
      clearTimeout(mirrorTimer);
      mirrorTimer = null;
      send({ type: 'mirror-stop' });
    } else if (c.cmd === 'relay-seen') {
      const all = Buffer.concat(seen).toString('utf8');
      out({ ev: 'relay-seen', bytes: all.length, found: (c.find || []).filter((s) => all.includes(s)) });
    } else if (c.cmd === 'quit') {
      ws.close();
      if (tunnel) tunnel.close();
      await jam.close();
      if (relay) await relay.close();
      process.exit(0);
    }
  }
}

main().catch((e) => {
  out({ ev: 'fatal', message: String(e && e.stack || e) });
  process.exit(1);
});
