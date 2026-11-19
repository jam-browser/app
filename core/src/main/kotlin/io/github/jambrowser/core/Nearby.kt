package io.github.jambrowser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 근처 Jam (같은 와이파이, 초대 링크 없이) — 데스크톱 server/nearby.js · shared/pairing.js · server/pair-client.js 와 같은 규칙
 *
 *  찾기 : 호스트 PC 가 2초마다 UDP 3032 로 알림을 보낸다 {t:"jam-nearby", v:1, id, name, port, code, access, n}
 *         (비밀키는 없음 — 방 코드만으로는 들어갈 수 없다)
 *  참여 : 호스트 서버에 X25519 키 교환 → 6자리 확인 코드(SAS)를 양쪽 화면에 띄워 사람이 비교
 *         → 호스트가 수락하면 그 연결의 임시 키로 암호화된 {초대 비밀키, 일회용 입장권}을 받는다
 *         → 받은 값으로 평소처럼(증명 + AES-GCM) 참여 (입장권 덕분에 다시 승인받지 않음)
 */
object Nearby {
    const val PORT = 3032
    const val EXPIRE_MS = 7000L
    private const val MAX_ENTRIES = 64
    private val ACCESS = setOf("approval", "friends", "open")

    data class Beacon(
        val id: String,
        val name: String,
        val port: Int,
        val code: String,
        val access: String,
        val n: Int,
        val app: String,
        val ip: String,
    ) {
        val key: String get() = "$id:$code"
        val server: String get() = "ws://${if (ip.contains(':')) "[$ip]" else ip}:$port"
        val accessLabel: String get() = when (access) { "open" -> "자유 입장"; "friends" -> "친구만"; else -> "호스트 승인" }
    }

    /** 같은 네트워크(사설 · 링크 로컬 · 이 기기) 주소인지 (데스크톱 isPrivateIp 와 같음) */
    fun isPrivateIp(raw: String?): Boolean {
        val ip = raw.orEmpty().removePrefix("::ffff:").removePrefix("/")
        if (ip.startsWith("relay:")) return false
        if (Regex("^(10\\.|127\\.|192\\.168\\.|169\\.254\\.)").containsMatchIn(ip)) return true
        Regex("^172\\.(\\d+)\\.").find(ip)?.let { val b = it.groupValues[1].toInt(); if (b in 16..31) return true }
        return ip == "::1" || Regex("^(fc|fd|fe80)", RegexOption.IGNORE_CASE).containsMatchIn(ip)
    }

    private fun clean(s: String?, max: Int) = s.orEmpty().replace(Regex("[\\u0000-\\u001f\\u007f]"), "").replace(Regex("\\s+"), " ").trim().take(max)

    /** 받은 알림 검사 (형식이 하나라도 틀리면 null) */
    fun parseBeacon(buf: ByteArray, len: Int, ip: String): Beacon? {
        if (len > 600 || !isPrivateIp(ip)) return null
        val m = try { Json.parseToJsonElement(String(buf, 0, len, Charsets.UTF_8)) as? JsonObject } catch (e: Exception) { null } ?: return null
        if (m.str("t") != "jam-nearby" || m.num("v") != 1.0) return null
        val port = m.num("port") ?: return null
        val n = m.num("n") ?: return null
        if (port != Math.floor(port) || port < 1 || port > 65535) return null
        if (n != Math.floor(n) || n < 0 || n > 64) return null
        val id = m.str("id")?.takeIf { Regex("^[A-Za-z0-9_-]{8,32}$").matches(it) } ?: return null
        val code = m.str("code")?.takeIf { Regex("^[A-Z0-9]{4,8}$").matches(it) } ?: return null
        val access = m.str("access")?.takeIf { it in ACCESS } ?: return null
        return Beacon(id, clean(m.str("name"), 24).ifEmpty { "이름 없음" }, port.toInt(), code, access, n.toInt(), clean(m.str("app"), 16), ip.removePrefix("::ffff:").removePrefix("/"))
    }

    /**
     * 참여자: 근처 알림을 모아 onChange(목록) 로 알려 준다 (7초 동안 안 들리면 뺌). onChange 는 받는 스레드에서 불린다.
     * 안드로이드에서는 와이파이 멀티캐스트 잠금을 잡은 채로 써야 브로드캐스트가 잘 들어온다.
     */
    class Scanner(private val onChange: (List<Beacon>) -> Unit, private val port: Int = PORT, private val clock: () -> Long = System::currentTimeMillis) {
        private val seen = LinkedHashMap<String, Pair<Beacon, Long>>()
        private var sock: DatagramSocket? = null
        @Volatile private var running = false
        private var lastKey = ""
        /** start() 가 실패한 이유 (포트를 다른 앱이 쓰는 중 등) */
        var lastError: Exception? = null; private set

        /** 듣기 시작. 포트를 열 수 없으면 false */
        fun start(): Boolean {
            if (running) return true
            val s = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                    bind(InetSocketAddress(this@Scanner.port)) // apply 안의 port 는 소켓의 것(-1)
                }
            } catch (e: Exception) {
                lastError = e
                return false
            }
            sock = s
            running = true
            Thread({ loop(s) }, "jam-nearby").apply { isDaemon = true }.start()
            return true
        }

        fun stop() {
            running = false
            sock?.close()
            sock = null
            synchronized(seen) { seen.clear() }
        }

        fun list(): List<Beacon> = synchronized(seen) { seen.values.map { it.first } }.sortedWith(compareBy({ it.name }, { it.ip }))

        private fun loop(s: DatagramSocket) {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val ip = p.address?.hostAddress ?: continue
                    val b = parseBeacon(buf, p.length, ip) ?: continue
                    synchronized(seen) {
                        val prev = seen[b.key]
                        if (prev == null && seen.size >= MAX_ENTRIES) return@synchronized
                        // 같은 호스트가 브로드캐스트 · 이 기기(127.0.0.1) 두 길로 들리면 같은 네트워크 주소를 우선
                        val keepIp = prev != null && prev.first.ip != b.ip && b.ip.startsWith("127.") && !prev.first.ip.startsWith("127.") && clock() - prev.second < EXPIRE_MS
                        seen[b.key] = (if (keepIp) b.copy(ip = prev!!.first.ip) else b) to clock()
                    }
                } catch (e: SocketTimeoutException) {
                    // 1초마다 오래된 것 정리
                } catch (e: Exception) {
                    if (!running) break
                }
                sweep()
                emit()
            }
        }

        private fun sweep() = synchronized(seen) {
            val now = clock()
            seen.entries.removeAll { now - it.value.second > EXPIRE_MS }
        }

        private fun emit() {
            val l = list()
            val key = l.joinToString("|")
            if (key != lastKey) {
                lastKey = key
                onChange(l)
            }
        }
    }
}

/** X25519 (RFC 7748). 근처 페어링의 일회용 키 교환에만 쓴다 (안드로이드 10 에는 기본 XDH 가 없어서 직접) */
object X25519 {
    private val P = BigInteger.valueOf(2).pow(255) - BigInteger.valueOf(19)
    private val A24 = BigInteger.valueOf(121665)
    private val random = SecureRandom()
    val BASE: ByteArray = ByteArray(32).also { it[0] = 9 }

    private fun le(b: ByteArray) = BigInteger(1, b.reversedArray())
    private fun toLe(x: BigInteger): ByteArray {
        val be = x.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        require(k.size == 32 && u.size == 32)
        val kk = k.copyOf()
        kk[0] = (kk[0].toInt() and 248).toByte()
        kk[31] = ((kk[31].toInt() and 127) or 64).toByte()
        val n = le(kk)
        val uu = u.copyOf()
        uu[31] = (uu[31].toInt() and 127).toByte()
        val x1 = le(uu).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = n.testBit(t)
            if (swap != bit) { val a = x2; x2 = x3; x3 = a; val b = z2; z2 = z3; z3 = b }
            swap = bit
            val a = (x2 + z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = (x2 - z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = (da + cb).pow(2).mod(P)
            z3 = x1.multiply((da - cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa + A24.multiply(e)).mod(P)
        }
        if (swap) { x2 = x3; z2 = z3 }
        return toLe(x2.multiply(z2.modPow(P - BigInteger.valueOf(2), P)).mod(P))
    }

    /** (개인 키, 공개 키) */
    fun newKeyPair(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(32).also(random::nextBytes)
        return priv to scalarMult(priv, BASE)
    }
}

/** shared/pairing.js 와 같은 계산 */
object Pairing {
    private val BOX_AAD = "jam-pair box".toByteArray()
    private val random = SecureRandom()

    /** a = 참여자 공개 키, b = 서버 공개 키 (둘 다 base64url) */
    fun transcript(a: String, b: String, nonce: String, code: String): String =
        MessageDigest.getInstance("SHA-256").digest("jam-pair v1|$a|$b|$nonce|${code.uppercase()}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    class Derived(val key: ByteArray, val sas: String)

    /** 내 임시 개인 키 + 상대 공개 키 → 키 · 6자리 (상대 키가 이상하면 null) */
    fun derive(myPriv: ByteArray, theirPubB64: String, th: String): Derived? {
        val pub = try { JamCrypto.unb64u(theirPubB64) } catch (e: IllegalArgumentException) { return null }
        if (pub.size != 32) return null
        val shared = X25519.scalarMult(myPriv, pub)
        if (shared.all { it == 0.toByte() }) return null // 작은 위수 점(약한 키) 거절
        val salt = th.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val key = JamCrypto.hkdf(shared, salt, "jam-pair key".toByteArray(), 32)
        val s = JamCrypto.hkdf(shared, salt, "jam-pair sas".toByteArray(), 4)
        shared.fill(0)
        val v = ((s[0].toLong() and 0xff) shl 24) or ((s[1].toLong() and 0xff) shl 16) or ((s[2].toLong() and 0xff) shl 8) or (s[3].toLong() and 0xff)
        return Derived(key, (v % 1_000_000).toString().padStart(6, '0'))
    }

    fun formatSas(sas: String) = "${sas.take(3)} ${sas.drop(3)}"

    /** 상자 = base64url(iv(12) ‖ 태그(16) ‖ 암호문), AAD "jam-pair box" */
    fun sealBox(key: ByteArray, json: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(BOX_AAD)
        val out = c.doFinal(json.toByteArray(Charsets.UTF_8)) // 암호문 ‖ 태그
        val ct = out.copyOfRange(0, out.size - 16)
        val tag = out.copyOfRange(out.size - 16, out.size)
        return JamCrypto.b64u(iv + tag + ct)
    }

    fun openBox(key: ByteArray, box: String): JsonObject? {
        val buf = try { JamCrypto.unb64u(box) } catch (e: IllegalArgumentException) { return null }
        if (buf.size < 29 || buf.size > 8192) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, buf, 0, 12))
            c.updateAAD(BOX_AAD)
            val pt = c.doFinal(buf.copyOfRange(28, buf.size) + buf.copyOfRange(12, 28))
            Json.parseToJsonElement(String(pt, Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 근처 호스트와 페어링 (server/pair-client.js 의 pairWithHost 와 같은 순서)
 *   서버 → hello{nonce} · 나 → pair{code, epk} · 서버 → pair-hello{epk} → 6자리 표시
 *   나 → pair-req{box(name)} · 서버 → pending … pair-ok{box(secret, ticket)}
 * 콜백은 OkHttp 스레드에서 불린다.
 */
class NearbyPairing(
    private val http: OkHttpClient,
    private val beacon: Nearby.Beacon,
    private val name: String,
    private val listener: Listener,
    private val timeoutMs: Long = 140_000,
) {
    interface Listener {
        fun onSas(sas: String)
        fun onPending() {}
        /** 성공: 이 초대로 참여하면 된다 (입장권 포함) */
        fun onDone(invite: Invite)
        fun onFailed(code: String, message: String)
    }

    private var ws: WebSocket? = null
    private var timer: ScheduledFuture<*>? = null
    @Volatile private var done = false
    private val eph = X25519.newKeyPair()
    private val epk = JamCrypto.b64u(eph.second)
    private var nonce: String? = null
    private var key: ByteArray? = null

    fun start(): NearbyPairing {
        if (!Nearby.isPrivateIp(beacon.ip)) { fail("forbidden", "근처 페어링은 같은 네트워크에서만 할 수 있어요"); return this }
        timer = SCHEDULER.schedule({ fail("timeout", "호스트가 응답하지 않아요 (시간 초과)") }, timeoutMs, TimeUnit.MILLISECONDS)
        val req = try {
            Request.Builder().url(beacon.server.replaceFirst("ws://", "http://")).build()
        } catch (e: IllegalArgumentException) {
            fail("bad-url", "주소가 올바르지 않아요"); return this
        }
        ws = http.newWebSocket(req, Socket())
        return this
    }

    fun cancel() = fail("cancelled", "페어링을 취소했어요")

    @Synchronized
    private fun fail(code: String, message: String) {
        if (done) return
        done = true
        finish()
        listener.onFailed(code, message)
    }

    private fun finish() {
        timer?.cancel(false)
        key?.fill(0)
        eph.first.fill(0)
        ws?.close(1000, null)
        ws = null
    }

    private inner class Socket : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (done) return
            val msg = try { Json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null } ?: return
            when (msg.str("type")) {
                "hello" -> {
                    if (nonce != null) return
                    nonce = msg.str("nonce") ?: return
                    webSocket.send(buildJsonObject { put("type", "pair"); put("code", beacon.code); put("epk", epk) }.toString())
                }
                "pair-hello" -> {
                    val n = nonce ?: return
                    if (key != null) return
                    val theirs = msg.str("epk").orEmpty()
                    val th = Pairing.transcript(epk, theirs, n, beacon.code)
                    val d = Pairing.derive(eph.first, theirs, th) ?: return fail("bad-key", "호스트의 키가 올바르지 않아요")
                    key = d.key
                    listener.onSas(d.sas)
                    val box = Pairing.sealBox(d.key, buildJsonObject { put("name", name) }.toString())
                    webSocket.send(buildJsonObject { put("type", "pair-req"); put("box", box) }.toString())
                }
                "pending" -> listener.onPending()
                "pair-ok" -> {
                    val k = key ?: return
                    val inner = Pairing.openBox(k, msg.str("box").orEmpty())
                    val secret = inner?.str("secret")
                    val ticket = inner?.str("ticket")
                    if (secret == null || !Regex("^[A-Za-z0-9_-]{43}$").matches(secret) || ticket == null) {
                        return fail("bad-box", "호스트가 보낸 값이 올바르지 않아요")
                    }
                    synchronized(this@NearbyPairing) {
                        if (done) return
                        done = true
                    }
                    finish()
                    listener.onDone(Invite(beacon.server, beacon.code, secret, relay = false, ticket = ticket))
                }
                "error" -> fail(msg.str("code") ?: "error", msg.str("message") ?: "페어링에 실패했어요")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            fail("closed", if (response?.code == 429) "잘못된 시도가 많아 잠시 차단됐어요" else "호스트에 연결할 수 없어요")

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail("closed", "호스트에 연결할 수 없어요")
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    }

    companion object {
        private val SCHEDULER = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "jam-pair").apply { isDaemon = true } }
    }
}
