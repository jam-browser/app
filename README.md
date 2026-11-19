# Jam Browser — 안드로이드 앱

친구와 같은 웹페이지를 함께 보는 브라우저 **Jam Browser**의 안드로이드 버전입니다.
PC용 [Jam Browser](https://github.com/jam-browser/browser)와 **같은 프로토콜**을 써서, PC와 폰이 한 Jam에 함께 들어갈 수 있어요.

> 지금은 **손님(참여자)** 으로만 쓸 수 있어요. Jam은 PC의 Jam Browser에서 만들고, 폰은 초대 코드로 참여합니다.

## 할 수 있는 것

- 탭과 주소창이 있는 WebView 브라우저
- 초대 코드를 붙여 넣어 Jam 참여
  - 같은 와이파이의 PC: `jam://192.168.x.x:3030/방코드/비밀키`
  - 중계 서버를 거쳐 인터넷으로: `jamr://중계주소:3031/경로/방코드/비밀키` (TLS면 `jamrs://`)
  - 초대 링크를 누르거나 메신저에서 "공유 → Jam Browser"로 보내면 참여 화면에 채워져요 (바로 들어가지는 않음)
- **근처 Jam (같은 와이파이, 초대 링크 없이)**: 참여 화면에 같은 네트워크의 PC Jam 이 보여요 (데스크톱 `server/nearby.js` 의 UDP 3032 알림)
  - "참여 요청" → X25519 키 교환 → 폰과 PC 화면에 **같은 6자리 확인 코드**가 떠요. 같으면 PC에서 수락
  - 수락되면 그 연결의 임시 키로 암호화된 초대 비밀키 + 일회용 입장권을 받아 평소처럼 참여해요 (중간에서 가로채면 6자리가 달라짐)
  - 참여 화면이 보이는 동안만 듣고(와이파이 멀티캐스트 잠금), 알림에는 비밀키가 없어요
- 다른 사람의 **커서**, 보고 있는 **화면 영역(색 사각형 + 해상도)**, 클릭 표시가 내 페이지 위에 보여요
  - PC와 폰은 화면 크기가 달라도 "같은 내용"을 가리키도록 데스크톱과 같은 앵커 좌표를 써요
  - 폰에서 화면을 누르면 다른 사람에게 커서 + 클릭으로 보여요 (손을 떼면 2.5초 뒤 사라짐)
- **페이지 따라가기 · 영상 위치 맞추기 · 스크롤 따라가기 · 사람 화면 따라가기**
- 채팅, 반응(👍😂😮❤️🔥👏), 대기열
- **핑**: JAM 탭을 길게 누르면 핑 휠이 열려요. 끌어서 고르고 놓으면 그 자리에 모두에게 표시돼요 — 위 ⚠️ 조심 · 아래 ❓ 뭐지? · 오른쪽 👀 이거 봐 · 왼쪽 🏃 갈게요 · 그대로 놓으면 📍 여기
  - 다른 사람이 찍은 핑은 같은 페이지면 그 자리에(데스크톱과 같은 앵커 좌표, 화면 밖이면 가장자리 화살표), 다른 페이지면 알림으로
  - 서버와 같은 도배 방지(순간 6개, 초당 2개). 메뉴 → 설정에서 끌 수 있어요
- **화면 미러링 보기**: PC 호스트가 탭 화면을 미러링하면 폰에 영상이 떠요 (H.264 · VP8 을 안드로이드 MediaCodec 으로, 소리 없음)
  - 32KB 조각을 모아 프레임으로, 중간이 빠지면 키프레임을 요청(`mkey`)하고, 1초마다 받는 지연을 알려(`mfb`) 호스트가 화질을 맞춰요
  - 영상을 길게 누르면 핑 휠 → 호스트 탭 위에 핑이 그려져 영상으로 모두에게 보여요 (`mping`)
  - "숨기기" 로 접어 두고 위쪽 안내의 "보기" 로 다시 열 수 있어요. 폰에서 원격 조작(마우스 · 키)은 아직 안 돼요
- 같은 사이트 안 이동은 누구나, **다른 사이트는 호스트의 권한**이 있어야 해요 (권한이 없으면 "개인 탭에서 열기" / "대기열에 추가")
- **로그인은 각자**: 로그인 · 인증 · 결제 페이지에 있으면 주소를 공유하지 않고 "로그인 대기" 상태가 돼요. 로그인을 마치면 Jam의 최신 페이지로 자동으로 이동해요
- **유튜브 광고**: 광고를 보는 동안은 위치를 맞추지 않고, 광고가 끝나면 Jam 재생 위치로 따라가요
- PC 호스트가 나가서 폰이 호스트가 되면 참여 요청 수락 · 권한 주기 · 내보내기도 할 수 있어요

## 소리를 내지 않아요

이 앱은 **어떤 소리도 내지 않습니다.** 안드로이드 WebView에는 음소거 기능이 없어서, 모든 프레임에 문서가 만들어질 때 가장 먼저 실행되는 스크립트([`jam-mute.js`](app/src/main/assets/jam-mute.js))로 소리 나는 길을 막아요.

- `<video>` · `<audio>` · `new Audio()`: 항상 muted + 볼륨 0 (페이지가 바꿔도 다시 0)
- Web Audio(`AudioContext`): 출력을 볼륨 0 노드로 바꿔치기
- 음성 합성(`speechSynthesis`): 동작 안 함
- 빈 iframe으로 우회하는 경우까지 같은 출처 iframe에도 적용
- 앱 버튼 클릭음도 꺼져 있어요

영상 재생 자체는 막지 않으므로 Jam 영상 동기화는 소리 없이 그대로 동작해요.

## 보안

- **데스크톱과 똑같은 암호화** ([`JamCrypto.kt`](core/src/main/kotlin/io/github/jambrowser/core/JamCrypto.kt) = 데스크톱 `shared/jam-crypto.js`)
  - 초대 코드의 비밀키(32바이트)는 네트워크로 보내지 않아요
  - HKDF-SHA256으로 암호화 키 · 인증 키를 만들고, 서버가 준 일회용 nonce에 HMAC 서명해서 "키를 안다"는 것만 증명
  - 이름 · 재접속 토큰도 암호화해서 보내요 (`p`) → 중계 서버는 이름도 볼 수 없어요
  - 이후 모든 메시지는 AES-256-GCM + 보내는 방향(AAD `c2s`/`s2c`)으로 암호화 · 위변조 검사
- 서버 · 중계 서버는 **전달만** 해요. 쿠키 · 비밀번호 · 로그인 정보는 폰 밖으로 나가지 않아요
- **설치 파일 격리**: apk · exe · zip 같은 설치 · 실행 파일은 앱 전용 "확인 전 보관함"에 `이름.다운로드확인전` 으로 저장돼요. 이 앱은 웹에서 받은 파일을 절대 설치하지 않고, 사용자가 경고를 읽고 직접 "내보내기"를 눌러야 다운로드 폴더로 나가요. 모든 다운로드는 먼저 물어봐요
- **앱 설치 권한은 자기 업데이트에만**: 자동 업데이트 때문에 `REQUEST_INSTALL_PACKAGES` 가 있지만, 설치하는 것은 이 저장소 GitHub 릴리스의 APK 뿐이에요 (아래 "자동 업데이트")
- **클립보드 경고**: 사이트가 클립보드에 글자를 넣으면 내용을 보여 주고 *"명령어라면 절대 실행하지 마세요"* 라고 경고해요 (명령어처럼 보이면 빨간 경고)
- `intent:` · `market:` 같은 앱 밖으로 나가는 주소는 열지 않아요
- 카메라 · 마이크 · 위치 권한은 웹페이지에 주지 않아요. 앱 권한은 인터넷뿐이에요
- 백업 · 기기 이전으로 쿠키 등 개인 정보가 나가지 않게 막아 두었어요
- 페이지 안 스크립트와 앱은 `WebMessageListener`로만 주고받고, 앱은 페이지가 보낸 값을 믿지 않아요 (주소는 앱이 직접 붙이고, 채널 · 크기 · 빈도를 검사)

## 왜 Kotlin + Android WebView인가

| | Kotlin + WebView (선택) | Capacitor (JS 재사용) |
|---|---|---|
| 데스크톱 JS 재사용 | 페이지 안 스크립트(`webview-preload.js`)는 거의 그대로 옮김 | 렌더러 JS를 더 많이 재사용 가능 |
| 소리 끄기 · 스크립트 주입 | `addDocumentStartJavaScript`로 **모든 프레임에, 페이지보다 먼저** 주입 | 앱 WebView 하나에 사이트를 띄우는 구조라 탭 · 주입 · 다운로드 제어를 결국 네이티브 플러그인으로 다시 짜야 함 |
| 다운로드 격리 · 탐색 잠금 | `DownloadListener` · `shouldOverrideUrlLoading`에서 직접 처리 | 플러그인 필요 |
| 프로토콜 테스트 | 프로토콜 코드(`core`)가 순수 JVM이라 **PC에서 데스크톱 서버(node)와 바로 테스트** | 브라우저/기기 안에서만 확인 가능 |

웹페이지를 여러 탭으로 띄우고, 그 안에 스크립트를 넣고, 탐색 · 다운로드를 막는 일이 핵심이라 네이티브 WebView를 직접 다루는 쪽이 더 단순하고 안전했어요. 페이지 위에 커서 · 사각형을 그리는 스크립트는 데스크톱 것을 옮겨서 좌표 규칙이 똑같아요.

## 빌드 (Windows 기준)

필요한 것: **JDK 17 이상**, **Android SDK** (명령줄 도구면 충분), 인터넷 (첫 빌드 때 Gradle · 라이브러리 다운로드)

1. Android SDK 설치 (Android Studio가 있으면 생략)
   - [명령줄 도구](https://developer.android.com/studio#command-line-tools-only)를 받아 `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest` 에 풀기
   - 필요한 패키지 설치:
     ```bash
     sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
     ```
2. 저장소 폴더에 `local.properties` 만들기 (SDK 위치, git에 올라가지 않음):
   ```
   sdk.dir=C:/Users/내이름/AppData/Local/Android/Sdk
   ```
   (`ANDROID_HOME` 환경 변수가 있으면 생략 가능)
3. 빌드:
   ```bash
   ./gradlew :app:assembleDebug
   ```
   결과: `app/build/outputs/apk/debug/app-debug.apk`

## 폰에 설치

- **USB로**: 폰에서 개발자 옵션 → USB 디버깅을 켜고
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **파일로**: APK를 폰에 옮겨 열고, "출처를 알 수 없는 앱 설치"를 이 한 번만 허용하세요.

debug 빌드는 앱 이름 뒤에 `.debug` 가 붙은 별도 앱(`io.github.jambrowser.app.debug`)으로 설치돼요.

## 자동 업데이트

플레이 스토어 밖에서 설치한 앱이라 스스로 업데이트를 확인해요 (메뉴 → 설정 → "자동 업데이트 확인" 으로 끌 수 있어요, 메뉴 → "업데이트 확인" 으로 바로 확인).

1. 앱을 열 때와 6시간마다 `https://api.github.com/repos/jam-browser/app/releases/latest` 를 읽어요 (초안 · 미리보기 릴리스는 건너뜀)
2. 지금보다 높은 버전이고 `.apk` 파일이 있으면 물어봐요 ("업데이트" / "나중에" / "이 버전 건너뛰기")
3. 앱 전용 폴더(`no_backup/updates`)에 받아서 **SHA-256** 을 같은 릴리스의 `<apk 이름>.sha256` 파일(없으면 릴리스 설명의 `SHA-256: …` 줄)과 맞춰 봐요. 해시가 없거나 다르면 설치하지 않아요
4. 설치 전에 한 번 더: 같은 앱(패키지 이름)인지, 더 높은 버전 번호인지, **지금 앱과 같은 서명 키**인지
5. 안드로이드 `PackageInstaller` 가 자기 화면으로 사용자에게 확인받고 설치해요. 처음 한 번은 "이 출처 허용"을 켜야 해요

안드로이드는 서명 키가 다른 APK 로는 업데이트하지 않아요. 그래서 릴리스는 항상 **같은 릴리스 키**로 서명해야 해요.

## 릴리스 만들기

서명 키(`.jks`)와 비밀번호는 **저장소 밖**에 두고, 빌드할 때 환경 변수로만 알려 줘요 (키 · 비밀번호는 절대 커밋하지 않기).

| 환경 변수 | 뜻 |
|---|---|
| `JAM_KEYSTORE` | 릴리스 키 저장소 파일 경로 (PKCS12 `.jks`) |
| `JAM_KEYSTORE_PASSWORD` | 키 저장소 비밀번호 |
| `JAM_KEY_ALIAS` | 키 이름 (없으면 `jam-app`) |
| `JAM_KEY_PASSWORD` | 키 비밀번호 (없으면 저장소 비밀번호와 같음) |

```bash
# 1) app/build.gradle.kts 의 appVersion 올리기 (예: 0.2.0 → 0.3.0, 버전 번호는 자동으로 300)
# 2) 서명된 APK + 해시 파일 만들기
./gradlew :app:releaseApk
#    → app/build/release/jam-browser-<버전>.apk, jam-browser-<버전>.apk.sha256
# 3) GitHub 에 v<버전> 태그로 릴리스를 만들고 두 파일을 함께 올리기
```

환경 변수가 없으면 `assembleRelease` 는 서명 안 된 APK(`app-release-unsigned.apk`)를 만들어요 (설치 · 업데이트 불가).

## 사용법

1. PC의 Jam Browser에서 **Jam 시작하기** → 초대 코드를 복사해 폰으로 보내기 (메신저 등)
2. 폰 앱 아래 **Jam 참여** → 이름 입력 → **붙여넣기** → **참여하기**
3. PC에서 참여 요청을 **수락** (입장 방식이 "링크만 있으면 자유 입장"이면 바로 들어가요)
4. 지금 탭이 **JAM 탭**이 되어 Jam 페이지로 이동해요. 다른 탭은 개인 탭이라 공유되지 않아요

같은 와이파이라면 `jam://` 코드를, 밖에서 들어온다면 PC에서 중계 서버를 설정하고 받은 `jamr://` 코드를 쓰세요.

## 테스트 — 데스크톱 서버와의 호환성

프로토콜 코드(`core`)는 PC에서 **데스크톱 저장소의 진짜 `server/jam-server.js`를 node로 띄워** 맞춰 봅니다.

```bash
./gradlew :core:test
```

확인하는 것:
- HKDF · HMAC 증명 · AES-GCM 프레임이 데스크톱 `shared/jam-crypto.js`와 바이트 단위로 같은지 (양방향)
- 승인 대기 → 참여, 채팅 · 이동 · 다른 사이트 거절/권한 · 뷰포트 · 커서 · 클릭 · 상태 · 영상 · 스크롤 · 반응 · 대기열 · 종료
- 거절 · 잘못된 비밀키(`bad-invite`) · 자유 입장(`open`) · 친구만(`friends` → 폰은 아직 신원 서명이 없어 거절 안내)
- 화면 미러링: 데스크톱 WebCodecs 로 미리 인코딩한 H.264(annexb) · VP8 조각(`core/src/test/node/mirror-clips`, `enc.html` 로 다시 만들 수 있음)을 desktop-host.js 가 mirror.js 와 같은 방식으로 보내고, 폰이 빠짐없이 모으는지 · mkey · mfb(→ 호스트 mstat) · mping
- 근처 Jam: 데스크톱 `nearby.js` 알림 받기, X25519(RFC 7748 벡터) · 페어링 상자, 6자리가 호스트와 같은지, 입장권으로 승인 없이 참여 · 입장권 재사용 불가, 거절
- 핑 주고받기 · 도배 방지, 자동 업데이트(버전 비교 · 해시 확인 · 내려받기)
- 끊겼다 재접속하면 재접속 토큰으로 승인 없이 복귀
- 중계 서버(backend 저장소 `jam-relay.js`)를 거쳐 참여하고, **중계 서버가 본 바이트에 이름 · 채팅 · 주소 · 비밀키가 없는지**
- 폰 동기화 규칙(`JamController`): 따라가기, 같은 사이트 잠금, 로그인 대기 후 자동 이동, 광고 중 영상 맞춤 보류

데스크톱 · 중계 서버 위치: 이 저장소 옆 폴더(`../browser`, `../backend` 등)에서 자동으로 찾고, 없으면 해당 테스트는 건너뜁니다. 직접 지정하려면:

```bash
JAM_DESKTOP_DIR=/path/to/browser JAM_RELAY_PATH=/path/to/backend/jam-relay.js ./gradlew :core:test
```

데스크톱 · backend 폴더에서 `npm install`을 먼저 해 두어야 해요 (`ws` 모듈).

### 에뮬레이터로 확인하기

`desktop-host.js` 는 `--control <포트>` 로 띄우면 HTTP로 명령을 받아서, 에뮬레이터의 폰 앱을 손님으로 붙여 볼 수 있어요.
에뮬레이터에서 PC는 `10.0.2.2` 로 보여요.

```bash
node core/src/test/node/desktop-host.js ../browser --port 3030 --invite-host 10.0.2.2 --control 9400 --relay ../backend/jam-relay.js
# 출력된 invite(jam://10.0.2.2:3030/…)를 앱에 붙여 넣고 참여
curl -X POST http://127.0.0.1:9400/cmd -d '{"cmd":"send","msg":{"type":"navigate","url":"http://10.0.2.2:8088/","title":"테스트"}}'
curl "http://127.0.0.1:9400/events?since=0"   # 호스트가 받은 메시지
```

Android 15 에뮬레이터(API 35)에서 확인한 것: 초대 링크로 참여(직접 · 중계 서버 모두, 승인 대기 → 수락), 호스트 이동 따라가기, 폰 링크 이동이 호스트에 전달,
PC 커서 · 화면 사각형이 폰 페이지의 같은 문단 위에 그려짐, 폰 터치가 커서 + 클릭으로 전달, 영상 재생 위치 · 일시정지 맞춤,
다른 사이트 잠금 안내, 로그인 대기(주소 비공개 → 로그인 뒤 Jam 최신 페이지로), APK 다운로드 격리, 명령어 복사 경고,
그리고 소리 차단(영상은 실제로 muted · 볼륨 0, Web Audio 출력은 볼륨 0 노드).

> 참고: 페이지가 Web Audio를 켜면 안드로이드에는 앱의 오디오 출력 스트림이 "열림"으로 보일 수 있어요. 그 스트림으로 나가는 소리는 볼륨 0(무음)이에요.

## 구조

```
core/   안드로이드와 무관한 Jam 프로토콜 (PC에서 테스트)
  JamCrypto.kt      데스크톱 shared/jam-crypto.js 와 같은 암호화
  Invite.kt         초대 코드 해석 (jam:// · jamr:// · jamrs://)
  Site.kt           같은 사이트 · 로그인 주소 판단 (shared/site.js)
  JamConnection.kt  서버 연결 · 참여 인증 · 암호화 송수신 (OkHttp WebSocket)
  JamController.kt  동기화 규칙 (app.js 의 handleMessage · onJamPageEvent)
  src/test/         호환성 테스트 + node 도우미(desktop-host.js)
app/    안드로이드 앱
  MainActivity.kt   화면 · 탭 · Jam 패널
  Tab.kt            WebView 설정 · 스크립트 주입 · 탐색 잠금
  DownloadGuard.kt  다운로드 확인 + 설치 파일 격리
  AppUpdater.kt     자동 업데이트 (확인 · 받기 · 서명 확인 · PackageInstaller)
  ClipboardGuard.kt 클립보드 경고
  PingWheel.kt      핑 휠 (길게 누르기)
  MirrorView.kt     화면 미러링 보기 (MediaCodec)
  assets/jam-page.js  페이지 안 스크립트 (데스크톱 webview-preload.js 를 폰용으로)
  assets/jam-mute.js  소리 끄기 (모든 프레임)
```

## 아직 안 되는 것 / 다음 할 일

- 폰에서 Jam **만들기(호스트)**: 지금은 PC에서만
- 화면 미러링을 폰에서 **조작**(마우스 · 게임 키): 지금은 보기와 핑만
- **친구만 Jam**(브라우저 계정 Ed25519 신원 서명): 폰은 아직 서명을 보내지 않아서 "링크 + 승인" · "자유 입장" Jam에만 들어갈 수 있어요 (`JamConnection` 에 `identity` 자리는 만들어 두었어요)
- 비밀번호 뱅크 · UI 꾸미기 등 데스크톱 전용 기능
- WebView 버전이 아주 오래되어 문서 시작 스크립트를 지원하지 않으면 iframe 안의 소리까지는 막지 못할 수 있어요 (구글 플레이에서 Android System WebView를 업데이트하세요)

## 라이선스

[MIT](LICENSE)
