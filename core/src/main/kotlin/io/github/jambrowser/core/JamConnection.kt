package io.github.jambrowser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Jam 서버 연결 하나 (참여자 = 손님 쪽). 데스크톱 app.js 의 connect() 와 같은 순서로 동작한다.
 *
 *  1. 서버 → hello { nonce }             (평문 JSON)
 *  2. 나   → join { code, proof, p }      (평문 JSON. 비밀키는 보내지 않는다)
 *       proof = HMAC(auth, "nonce:CODE"), p = base64(AES-GCM(enc, {name, resume, id?}, "c2s"))
 *  3. 서버 → pending (호스트 승인 대기) → welcome   (여기부터 모두 암호화된 바이너리 프레임)
 *
 * 콜백은 OkHttp 스레드에서 불린다. 화면 쪽에서 메인 스레드로 넘겨야 한다.
 */
class JamConnection(
    private val http: OkHttpClient,
    private val invite: Invite,
    private val name: String,
    private val resume: String?,
    private val listener: Listener,
    /** 브라우저 계정 신원 서명 (친구만 Jam 용, 나중에). nonce, 방 코드 → {pub, sig} 또는 null */
    private val identity: ((nonce: String, code: String) -> JsonObject?)? = null,
) {
    interface Listener {
        fun onPending() {}
        fun onWelcome(conn: JamConnection, welcome: JsonObject)
        fun onMessage(conn: JamConnection, msg: JsonObject)
        /** welcome 전에 실패 (code: bad-invite, rejected, not-friend, full, busy, timeout, closed, bad-secret, bad-url …) */
        fun onFailed(conn: JamConnection, code: String, message: String)
        /** welcome 뒤에 연결이 끊김 */
        fun onClosed(conn: JamConnection) {}
    }

    private val keys: JamCrypto.Keys? = try { JamCrypto.deriveKeys(invite.secret) } catch (e: IllegalArgumentException) { null }
    private var ws: WebSocket? = null
    @Volatile private var settled = false
    @Volatile private var welcomed = false
    @Volatile private var closedByMe = false
    private var timer: ScheduledFuture<*>? = null
    private val lock = Any()

    val isOpen: Boolean get() = welcomed && ws != null && !closedByMe

    fun start(): JamConnection {
        if (keys == null) {
            settled = true
            listener.onFailed(this, "bad-secret", "초대 코드의 비밀키가 올바르지 않아요")
            return this
        }
        val req = try {
            Request.Builder().url(invite.server.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")).build()
        } catch (e: IllegalArgumentException) {
            settled = true
            listener.onFailed(this, "bad-url", "서버 주소가 올바르지 않아요")
            return this
        }
        arm(7000)
        ws = http.newWebSocket(req, Socket())
        return this
    }

    /** 암호화해서 보낸다. 연결이 없으면 false */
    fun send(msg: JsonObject): Boolean {
        val k = keys ?: return false
        val w = ws ?: return false
        if (!welcomed || closedByMe) return false
        val frame = JamCrypto.seal(k, msg.toString(), JamCrypto.C2S)
        return w.send(frame.toByteString())
    }

    fun close() {
        closedByMe = true
        cancelTimer()
        ws?.close(1000, null)
        ws = null
    }

    private fun arm(ms: Long) {
        cancelTimer()
        timer = SCHEDULER.schedule({ fail("timeout", "서버에 연결할 수 없어요 (시간 초과)") }, ms, TimeUnit.MILLISECONDS)
    }

    private fun cancelTimer() {
        timer?.cancel(false)
        timer = null
    }

    private fun fail(code: String, message: String) {
        synchronized(lock) {
            if (settled) return
            settled = true
        }
        cancelTimer()
        ws?.cancel()
        ws = null
        if (!closedByMe) listener.onFailed(this, code, message)
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private inner class Socket : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            // 인증 전 평문: hello(nonce) / 오류
            val msg = try { Json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null } ?: return
            when (str(msg, "type")) {
                "hello" -> {
                    val nonce = str(msg, "nonce") ?: return
                    val k = keys ?: return
                    val inner = buildJsonObject {
                        put("name", name)
                        if (resume != null) put("resume", resume) else put("resume", JsonNull)
                        identity?.invoke(nonce, invite.code)?.let { put("id", it) }
                    }
                    val sealed = JamCrypto.seal(k, inner.toString(), JamCrypto.C2S)
                    val first = buildJsonObject {
                        put("type", "join")
                        put("code", invite.code)
                        put("proof", JamCrypto.makeProof(k, nonce, invite.code))
                        put("p", Base64.getEncoder().encodeToString(sealed))
                    }
                    webSocket.send(first.toString())
                }
                "error" -> fail(str(msg, "code") ?: "error", str(msg, "message") ?: "오류가 났어요")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val k = keys ?: return
            val msg = try {
                Json.parseToJsonElement(JamCrypto.open(k, bytes.toByteArray(), JamCrypto.S2C)) as? JsonObject
            } catch (e: Exception) {
                null // 키가 다르거나 위조된 메시지 → 무시
            } ?: return
            if (!settled) {
                when (str(msg, "type")) {
                    "welcome" -> {
                        synchronized(lock) {
                            if (settled) return
                            settled = true
                            welcomed = true
                        }
                        cancelTimer()
                        listener.onWelcome(this@JamConnection, msg)
                    }
                    "pending" -> {
                        arm(130_000) // 호스트 승인 대기 (서버는 2분)
                        listener.onPending()
                    }
                    "error" -> fail(str(msg, "code") ?: "error", str(msg, "message") ?: "오류가 났어요")
                }
                return
            }
            listener.onMessage(this@JamConnection, msg)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!settled) {
                val c = response?.code
                return when (c) {
                    429 -> fail("blocked", "잘못된 시도가 많아 잠시 차단됐어요. 10분 뒤에 다시 해 보세요")
                    403 -> fail("forbidden", "서버가 연결을 거절했어요")
                    404 -> fail("bad-invite", "초대 코드가 올바르지 않거나 Jam이 끝났어요.")
                    else -> fail("closed", "서버에 연결할 수 없어요")
                }
            }
            ended()
        }

        private fun ended() {
            if (!settled) return fail("closed", "서버에 연결할 수 없어요")
            val wasWelcomed = welcomed
            welcomed = false
            ws = null
            if (wasWelcomed && !closedByMe) listener.onClosed(this@JamConnection)
        }
    }

    companion object {
        private val SCHEDULER = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "jam-timer").apply { isDaemon = true }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}

/** JSON 도우미 */
fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull()
fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.contentOrNull == "true"
fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
fun JsonElement?.asStr(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
