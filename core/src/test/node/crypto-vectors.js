'use strict';
/**
 * 암호화 호환성 확인: 데스크톱의 진짜 shared/jam-crypto.js 로 계산한 값을 출력한다
 *   node crypto-vectors.js <데스크톱 폴더> <비밀키> <nonce> <방코드> <폰이 seal 한 프레임(base64)>
 * 출력(JSON): enc/auth 키(hex), proof, 폰 프레임을 c2s 로 연 결과, 폰이 열어 볼 s2c 프레임
 */
const path = require('path');
const [desktopDir, secret, nonce, code, phoneFrame] = process.argv.slice(2);
const { deriveKeys, makeProof, seal, open, C2S, S2C } = require(path.join(path.resolve(desktopDir), 'shared', 'jam-crypto.js'));

const keys = deriveKeys(secret);
let opened = null;
let wrongDirection = false;
try {
  opened = open(keys, Buffer.from(phoneFrame, 'base64'), C2S);
} catch (e) {
  opened = { error: String(e.message) };
}
try {
  open(keys, Buffer.from(phoneFrame, 'base64'), S2C); // 방향이 다르면 열리면 안 된다
} catch {
  wrongDirection = true;
}
process.stdout.write(
  JSON.stringify({
    enc: keys.enc.toString('hex'),
    auth: keys.auth.toString('hex'),
    proof: makeProof(keys, nonce, code),
    opened,
    wrongDirectionRejected: wrongDirection,
    forPhone: seal(keys, { type: 'chat', text: '데스크톱에서 보낸 글 ✓', n: 42 }, S2C).toString('base64'),
  })
);
