package io.github.jambrowser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 데스크톱 저장소 / 중계 서버 / node 를 찾는다. 없으면 테스트를 건너뛴다 (Assume) */
object Env {
    private val repoDir = File(System.getProperty("jam.repoDir") ?: ".").absoluteFile
    private val parent = repoDir.parentFile

    val node: String? by lazy {
        val candidates = listOfNotNull(System.getenv("JAM_NODE"), "node", "C:/Program Files/nodejs/node.exe")
        candidates.firstOrNull { c ->
            try {
                ProcessBuilder(c, "-v").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
            } catch (e: Exception) {
                false
            }
        }
    }

    /** server/jam-server.js 와 ws 모듈이 있는 데스크톱 폴더 */
    val desktopDir: File? by lazy {
        val env = System.getenv("JAM_DESKTOP_DIR")?.takeIf { it.isNotBlank() }
        listOfNotNull(env, "jam-browser-build", "browser", "jam-browser", "jam-browser-work")
            .map { if (File(it).isAbsolute) File(it) else File(parent, it) }
            .firstOrNull { File(it, "server/jam-server.js").exists() && File(it, "node_modules/ws").exists() }
    }

    val relayPath: File? by lazy {
        val env = System.getenv("JAM_RELAY_PATH")?.takeIf { it.isNotBlank() }
        listOfNotNull(env, "jam-backend-build/jam-relay.js", "backend/jam-relay.js", "jam-backend/jam-relay.js")
            .map { if (File(it).isAbsolute) File(it) else File(parent, it) }
            .firstOrNull { it.exists() && File(it.parentFile, "node_modules/ws").exists() }
    }

    val nodeScripts = File(repoDir, "core/src/test/node")

    fun requireDesktop(): Pair<String, File> {
        Assume.assumeTrue("node 가 없어서 건너뜀", node != null)
        Assume.assumeTrue("데스크톱 저장소(server/jam-server.js + node_modules/ws)를 찾지 못해 건너뜀 — JAM_DESKTOP_DIR 지정", desktopDir != null)
        return node!! to desktopDir!!
    }
}

/** core/src/test/node/desktop-host.js 를 띄워서 데스크톱 호스트 역할을 시킨다 */
class DesktopHarness(access: String = "approval", relay: Boolean = false) : AutoCloseable {
    private val proc: Process
    private val events = LinkedBlockingQueue<JsonObject>()
    private val backlog = ArrayList<JsonObject>()
    val ready: JsonObject

    init {
        val (node, desktop) = Env.requireDesktop()
        if (relay) Assume.assumeTrue("중계 서버(backend 저장소 jam-relay.js)를 찾지 못해 건너뜀 — JAM_RELAY_PATH 지정", Env.relayPath != null)
        val cmd = mutableListOf(node, File(Env.nodeScripts, "desktop-host.js").path, desktop.path, "--access", access)
        if (relay) cmd += listOf("--relay", Env.relayPath!!.path)
        proc = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        Thread {
            proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val o = try { Json.parseToJsonElement(line) as? JsonObject } catch (e: Exception) { null }
                if (o != null) events.add(o) else println("[desktop-host] $line")
            }
        }.apply { isDaemon = true }.start()
        ready = next("ready", 15_000)
    }

    val invite: String get() = ready.str("invite")!!
    val relayInvite: String? get() = ready.str("relayInvite")
    val code: String get() = ready.str("code")!!
    val secret: String get() = ready.str("secret")!!

    /** 다음 이벤트 (ev 가 같고 pred 를 만족하는 것). 앞에 온 다른 이벤트는 보관해 둔다 */
    fun next(ev: String, timeoutMs: Long = 5000, pred: (JsonObject) -> Boolean = { true }): JsonObject {
        backlog.firstOrNull { it.str("ev") == ev && pred(it) }?.let { backlog.remove(it); return it }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val e = events.poll(left.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                ?: throw AssertionError("데스크톱 호스트에서 '$ev' 를 기다리다 시간 초과. 받은 것: $backlog")
            if (e.str("ev") == "fatal") throw AssertionError("desktop-host 오류: ${e.str("message")}")
            if (e.str("ev") == ev && pred(e)) return e
            backlog += e
        }
    }

    /** 호스트가 받은 메시지 중 type 이 같은 것 */
    fun hostMsg(type: String, timeoutMs: Long = 5000, pred: (JsonObject) -> Boolean = { true }): JsonObject =
        next("host-msg", timeoutMs) { it.obj("msg")?.str("type") == type && pred(it.obj("msg")!!) }.obj("msg")!!

    fun send(msg: JsonObject) = command(buildJsonObject { put("cmd", "send"); put("msg", msg) })

    fun dropGuests() {
        command(buildJsonObject { put("cmd", "drop-guests") })
        next("dropped")
    }

    fun relaySeen(find: List<String>): JsonObject {
        command(buildJsonObject { put("cmd", "relay-seen"); put("find", JsonArray(find.map(::JsonPrimitive))) })
        return next("relay-seen")
    }

    private fun command(o: JsonObject) {
        proc.outputStream.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
        proc.outputStream.flush()
    }

    override fun close() {
        try {
            command(buildJsonObject { put("cmd", "quit") })
            proc.waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        proc.destroyForcibly()
    }
}

/** 폰(JamConnection) 쪽 테스트 손님: 받은 메시지를 큐에 쌓는다 */
class PhoneGuest(invite: String, name: String, resume: String? = null) : JamConnection.Listener {
    val events = LinkedBlockingQueue<JsonObject>()
    private val backlog = ArrayList<JsonObject>()
    @Volatile var pendingSeen = false
    @Volatile var welcome: JsonObject? = null
    @Volatile var failure: Pair<String, String>? = null
    @Volatile var closed = false
    val conn: JamConnection = JamConnection(JamConnection.defaultClient(), Invite.parse(invite)!!, name, resume, this).start()

    override fun onPending() { pendingSeen = true }
    override fun onWelcome(conn: JamConnection, welcome: JsonObject) { this.welcome = welcome; events.add(buildJsonObject { put("type", "__welcome") }) }
    override fun onMessage(conn: JamConnection, msg: JsonObject) { events.add(msg) }
    override fun onFailed(conn: JamConnection, code: String, message: String) { failure = code to message; events.add(buildJsonObject { put("type", "__failed"); put("code", code) }) }
    override fun onClosed(conn: JamConnection) { closed = true; events.add(buildJsonObject { put("type", "__closed") }) }

    fun next(type: String, timeoutMs: Long = 5000, pred: (JsonObject) -> Boolean = { true }): JsonObject {
        backlog.firstOrNull { it.str("type") == type && pred(it) }?.let { backlog.remove(it); return it }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val e = events.poll(left.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                ?: throw AssertionError("폰에서 '$type' 를 기다리다 시간 초과 (실패=$failure). 받은 것: $backlog")
            if (e.str("type") == type && pred(e)) return e
            backlog += e
        }
    }

    fun awaitWelcome(timeoutMs: Long = 8000): JsonObject {
        next("__welcome", timeoutMs)
        return welcome!!
    }

    fun close() = conn.close()
}
