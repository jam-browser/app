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
 *  - 참여 요청은 자동 승인 (이름이 "거절" 로 시작하면 거절)
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
const relayPath = opt('--relay');

// ws 모듈은 데스크톱 저장소의 node_modules 에서 찾는다
const req = (m) => require(require.resolve(m, { paths: [desktopDir, path.dirname(relayPath || desktopDir)] }));
const WebSocket = req('ws');
const { createJamServer } = require(path.join(desktopDir, 'server', 'jam-server.js'));
const { newSecret, deriveKeys, seal, open, C2S, S2C } = require(path.join(desktopDir, 'shared', 'jam-crypto.js'));

const out = (o) => process.stdout.write(JSON.stringify(o) + '\n');

async function main() {
  const relayKey = crypto.randomBytes(24).toString('base64url');
  const jam = await createJamServer({ port: 0, host: '127.0.0.1', localCreateOnly: false, relayKey });
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
      out({
        ev: 'ready',
        code,
        secret,
        hostId: msg.you.id,
        invite: `jam://127.0.0.1:${jam.port}/${code}/${secret}`,
        relayInvite: tunnel ? `jamr://127.0.0.1:${relay.port}/${tunnel.route}/${code}/${secret}` : null,
      });
      return;
    }
    if (msg.type === 'join-request') {
      send({ type: 'approve', id: msg.id, ok: !String(msg.name).startsWith('거절') });
    }
    out({ ev: 'host-msg', msg });
  });
  ws.on('close', () => out({ ev: 'host-closed' }));

  const rl = readline.createInterface({ input: process.stdin });
  rl.on('line', async (line) => {
    let c;
    try {
      c = JSON.parse(line);
    } catch {
      return;
    }
    if (c.cmd === 'send') send(c.msg);
    else if (c.cmd === 'drop-guests') {
      // 와이파이 끊김 흉내: 서버 쪽에서 손님 연결을 강제로 끊는다
      for (const room of jam.rooms.values()) {
        for (const m of room.members.values()) if (m.id !== room.hostId) m.ws.terminate();
      }
      out({ ev: 'dropped' });
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
  });
  rl.on('close', () => process.exit(0));
}

main().catch((e) => {
  out({ ev: 'fatal', message: String(e && e.stack || e) });
  process.exit(1);
});
