package io.github.jambrowser.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 폰 쪽 Jam 동기화 규칙 (데스크톱 src/renderer/app.js 의 jam 상태 · handleMessage · onJamPageEvent 를 옮긴 것)
 *
 *  - Jam 탭 하나가 Jam 과 연결된다 (다른 탭은 개인 탭)
 *  - 같은 사이트 안 이동은 누구나, 다른 사이트는 호스트 / 권한 받은 사람만
 *  - 로그인 · 인증 페이지에 있으면 "로그인 대기": 주소를 공유하지 않고, 로그인을 마치면 Jam 페이지로 자동 이동
 *  - 광고 중이면 영상 위치를 맞추지 않고, 광고가 끝나면 Jam 재생 위치로 따라간다
 *
 * 모든 메서드는 같은 스레드(안드로이드에서는 메인 스레드)에서 불러야 한다.
 */
class JamController(
    private val host: Host,
    private val connect: (Invite, String, String?, JamConnection.Listener) -> JamConnection,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** 화면 · 웹뷰 쪽이 구현한다 */
    interface Host {
        fun runOnMain(block: () -> Unit)
        fun schedule(ms: Long, block: () -> Unit): Cancellable
        /** Jam 탭의 지금 주소 / 제목 / 로딩 여부 / 앞에 보이는지 */
        fun jamTabUrl(): String?
        fun jamTabTitle(): String
        fun jamTabLoading(): Boolean
        fun jamTabInFront(): Boolean
        /** Jam 탭을 이 주소로 이동 (동기화로 인한 이동 — 잠금 검사 없이) */
        fun loadInJamTab(url: String)
        /** Jam 탭 웹페이지 안의 스크립트(jam-page.js)로 보낸다 */
        fun toPage(channel: String, data: JsonElement)
        fun toast(text: String)
        fun changed(what: Change)
        fun chat(line: ChatLine) {}
        fun reaction(emoji: String, color: String) {}
        /** Jam 이 끝났거나 연결이 완전히 끊김 (이유 문장) */
        fun ended(reason: String?) {}
    }

    fun interface Cancellable { fun cancel() }

    enum class Change { STATE, MEMBERS, NOW, QUEUE, LOGIN, REQUESTS, SETTINGS }

    data class Member(
        val id: String,
        val name: String,
        val color: String,
        val canCrossSite: Boolean,
        val presence: JsonObject?,
        var view: JsonObject?,
        val verified: Boolean,
        val friend: Boolean,
    ) {
        val url: String? get() = presence?.str("url")
        val title: String get() = presence?.str("title") ?: ""
        val inAd: Boolean get() = presence?.bool("ad") == true
        val isPrivate: Boolean get() = presence?.bool("private") == true
        val away: Boolean get() = presence?.bool("away") == true

        fun withPresence(p: JsonObject?) = copy(presence = p)

        companion object {
            fun from(o: JsonObject) = Member(
                id = o.str("id") ?: "",
                name = o.str("name") ?: "익명",
                color = o.str("color") ?: "#2ee6a6",
                canCrossSite = o.bool("canCrossSite"),
                presence = o.obj("presence"),
                view = o.obj("view"),
                verified = o.bool("verified"),
                friend = o.bool("friend"),
            )
        }
    }

    data class QueueItem(val id: String, val url: String, val title: String, val addedBy: String, val addedByName: String, val color: String)
    data class Current(val url: String, val title: String, val by: String?)
    data class ChatLine(val system: Boolean, val name: String, val color: String, val text: String, val mine: Boolean)
    data class JoinRequest(val id: String, val name: String, val ip: String, val friend: Boolean)
    private data class Media(val url: String, val playing: Boolean, val time: Double, val rate: Double, val by: String?, val receivedAt: Long)

    enum class Status { IDLE, CONNECTING, PENDING, ACTIVE, RECONNECTING }

    // ---------------- 상태 ----------------
    var status = Status.IDLE; private set
    val active: Boolean get() = status == Status.ACTIVE || status == Status.RECONNECTING
    var youId: String? = null; private set
    var code: String = ""; private set
    var hostId: String? = null; private set
    var members: List<Member> = emptyList(); private set
    var queue: List<QueueItem> = emptyList(); private set
    var current: Current? = null; private set
    var allowAllCrossSite = false; private set
    var access: String = "approval"; private set
    val requests = LinkedHashMap<String, JoinRequest>()

    /** 페이지 따라가기 (끄면 내 Jam 탭이 자유롭게 움직이고, 남을 끌고 가지도 않음) */
    var follow = true
        set(v) { field = v; if (v) catchUp(); sendPresence(); host.changed(Change.STATE) }
    /** 스크롤 따라가기 */
    var followScroll = false
        set(v) { field = v; host.changed(Change.STATE) }
    var followingId: String? = null; private set

    var inAd = false; private set
    var loginWait = false; private set
    var reconnectAttempt = 0; private set

    private var conn: JamConnection? = null
    private var invite: Invite? = null
    private var name: String = ""
    private var resume: String? = null
    private var media: Media? = null
    private var lastSyncedUrl: String? = null
    private var lastTickSent = 0L
    private var myView: JsonObject? = null
    private var loginTimer: Cancellable? = null
    private var reconnectTimer: Cancellable? = null
    private var generation = 0

    val you: Member? get() = members.find { it.id == youId }
    fun member(id: String?) = members.find { it.id == id }
    fun isHost() = youId != null && youId == hostId
    fun canCrossSite() = active && (isHost() || allowAllCrossSite || you?.canCrossSite == true)
    /** 같은 사이트 안은 누구나, 다른 사이트는 권한이 있어야 */
    fun canGo(url: String) = canCrossSite() || current == null || Site.sameSite(url, current?.url)
    val hostName: String get() = member(hostId)?.name ?: "호스트"
    /** 내 Jam 탭이 Jam 페이지에서 벗어나 있는지 (돌아가기 버튼용) */
    fun isDrifted(): Boolean {
        val cur = current ?: return false
        return active && !Site.sameDoc(host.jamTabUrl(), cur.url)
    }

    // ======================================================================
    // 참여 / 나가기
    // ======================================================================
    fun join(inv: Invite, myName: String) {
        if (status != Status.IDLE) leave()
        invite = inv
        name = myName.trim().take(24).ifEmpty { "익명" }
        resume = null
        status = Status.CONNECTING
        host.changed(Change.STATE)
        open(firstJoin = true)
    }

    private fun open(firstJoin: Boolean) {
        val inv = invite ?: return
        val gen = ++generation
        val listener = object : JamConnection.Listener {
            override fun onPending() = host.runOnMain {
                if (gen != generation) return@runOnMain
                status = Status.PENDING
                host.changed(Change.STATE)
            }

            override fun onWelcome(conn: JamConnection, welcome: JsonObject) = host.runOnMain {
                if (gen != generation) return@runOnMain conn.close()
                adoptWelcome(conn, welcome, firstJoin)
            }

            override fun onMessage(conn: JamConnection, msg: JsonObject) = host.runOnMain {
                if (gen == generation && this@JamController.conn === conn) handleMessage(msg)
            }

            override fun onFailed(conn: JamConnection, code: String, message: String) = host.runOnMain {
                if (gen != generation) return@runOnMain
                if (status == Status.RECONNECTING) return@runOnMain retryReconnect(code)
                cleanup()
                host.toast(message)
                host.ended(message)
            }

            override fun onClosed(conn: JamConnection) = host.runOnMain {
                if (gen != generation || this@JamController.conn !== conn) return@runOnMain
                startReconnect()
            }
        }
        conn = connect(inv, name, resume, listener)
    }

    // 와이파이가 잠깐 끊기는 등 연결이 끊어지면 같은 코드로 다시 참여 (재접속 토큰으로 승인 없이 복귀)
    private fun startReconnect() {
        conn = null
        status = Status.RECONNECTING
        reconnectAttempt = 0
        host.changed(Change.STATE)
        retryReconnect(null)
    }

    private fun retryReconnect(failCode: String?) {
        if (failCode in setOf("bad-invite", "rejected", "bad-secret", "kicked") || reconnectAttempt >= 5) {
            cleanup()
            host.toast("Jam 연결이 끊어졌어요")
            host.ended("Jam 연결이 끊어졌어요")
            return
        }
        reconnectAttempt++
        host.changed(Change.STATE)
        reconnectTimer = host.schedule(1000L * reconnectAttempt) {
            if (status == Status.RECONNECTING) open(firstJoin = false)
        }
    }

    fun leave() {
        if (status == Status.IDLE) return
        cleanup()
        host.ended(null)
    }

    private fun cleanup() {
        generation++
        conn?.close()
        conn = null
        reconnectTimer?.cancel()
        loginTimer?.cancel()
        host.toPage("jam:clear", JsonNull)
        host.toPage("jam:policy", buildJsonObject { put("locked", false) })
        status = Status.IDLE
        youId = null; hostId = null; code = ""
        members = emptyList(); queue = emptyList(); current = null
        media = null; inAd = false; loginWait = false; myView = null; followingId = null
        requests.clear()
        resume = null
        host.changed(Change.STATE)
    }

    private fun adoptWelcome(c: JamConnection, welcome: JsonObject, firstJoin: Boolean) {
        val wasReconnect = status == Status.RECONNECTING
        conn = c
        status = Status.ACTIVE
        reconnectAttempt = 0
        val youObj = welcome.obj("you")
        youId = youObj?.str("id")
        resume = welcome.str("resume")
        val room = welcome.obj("room") ?: JsonObject(emptyMap())
        code = room.str("code") ?: invite?.code ?: ""
        hostId = room.str("hostId")
        members = (room["members"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(Member::from) } ?: emptyList()
        queue = parseQueue(room["queue"])
        current = parseCurrent(room.obj("current"))
        room.obj("settings")?.let(::applySettings)
        media = room.obj("media")?.let(::parseMedia)
        if (firstJoin) {
            host.chat(ChatLine(true, "", "", "Jam에 오신 걸 환영해요. JAM 탭에서는 같은 사이트 안이라면 누구나 페이지를 넘길 수 있어요.", false))
            val cur = current
            lastSyncedUrl = cur?.url
            if (cur != null) host.loadInJamTab(cur.url)
            host.toast("${hostName}님의 Jam에 참여했어요")
        } else if (wasReconnect) {
            host.toast("Jam에 다시 연결됐어요")
            catchUp()
        }
        host.changed(Change.STATE)
        applyLock()
        sendPresence()
        myView?.let { publishViewport(it) }
        refreshAllViews()
        applyMedia()
    }

    // ======================================================================
    // 서버 → 나
    // ======================================================================
    fun handleMessage(msg: JsonObject) {
        if (!active) return
        when (msg.str("type")) {
            "members" -> {
                val old = members.associateBy { it.id }
                members = (msg["members"] as? JsonArray)?.mapNotNull { e ->
                    (e as? JsonObject)?.let(Member::from)?.let { m -> if (m.view == null) m.copy(view = old[m.id]?.view) else m }
                } ?: members
                hostId = msg.str("hostId") ?: hostId
                host.changed(Change.MEMBERS)
                applyLock()
                refreshAllViews()
            }
            "presence" -> {
                val id = msg.str("id")
                members = members.map { if (it.id == id) it.withPresence(msg.obj("presence")) else it }
                host.changed(Change.MEMBERS)
                if (id == followingId) syncToFollowed()
            }
            "viewport" -> {
                val by = msg.str("by") ?: return
                val m = member(by) ?: return
                m.view = if (msg.str("url") != null) JsonObject(msg - "type" - "by") else null
                refreshView(m)
                host.changed(Change.MEMBERS)
                if (by == followingId) syncToFollowed()
            }
            "left" -> {
                val id = msg.str("id") ?: return
                host.toPage("jam:cursor-remove", JsonPrimitive(id))
                host.toPage("jam:view-remove", JsonPrimitive(id))
                if (id == followingId) stopFollow("따라가던 사람이 나가서 멈췄어요")
            }
            "navigate" -> {
                val url = msg.str("url") ?: return
                current = Current(url, msg.str("title") ?: "", msg.str("by"))
                if (follow) {
                    val tabUrl = host.jamTabUrl()
                    if (loginWait || (Site.isWebUrl(tabUrl) && Site.isSensitiveUrl(tabUrl))) {
                        host.changed(Change.LOGIN) // 로그인 중에는 이동하지 않고, 로그인 뒤 갈 곳만 최신으로
                    } else {
                        lastSyncedUrl = url
                        if (!sameUrl(tabUrl, url)) host.loadInJamTab(url)
                    }
                }
                host.changed(Change.NOW)
            }
            "current" -> {
                current = parseCurrent(msg.obj("current")) ?: current
                host.changed(Change.NOW)
            }
            "scroll" -> {
                if (followScroll && follow && !loginWait && Site.sameDoc(msg.str("url"), host.jamTabUrl())) {
                    host.toPage("jam:scroll-to", buildJsonObject {
                        put("x", msg.num("x") ?: 0.0); put("y", msg.num("y") ?: 0.0); put("top", msg["top"] ?: JsonNull)
                    })
                }
            }
            "cursor" -> {
                val by = msg.str("by") ?: return
                val m = member(by)
                if (msg["x"] == null || msg["x"] is JsonNull || m == null || !Site.sameDoc(msg.str("url"), host.jamTabUrl())) {
                    host.toPage("jam:cursor-remove", JsonPrimitive(by))
                } else {
                    host.toPage("jam:cursor", buildJsonObject {
                        put("id", m.id); put("name", m.name); put("color", m.color)
                        put("x", msg.num("x") ?: 0.0); put("y", msg.num("y") ?: 0.0); put("a", msg["a"] ?: JsonNull)
                    })
                }
            }
            "click" -> {
                val m = member(msg.str("by")) ?: return
                if (Site.sameDoc(msg.str("url"), host.jamTabUrl())) {
                    host.toPage("jam:click", buildJsonObject {
                        put("color", m.color); put("x", msg.num("x") ?: 0.0); put("y", msg.num("y") ?: 0.0); put("a", msg["a"] ?: JsonNull)
                    })
                }
            }
            "media" -> {
                media = parseMedia(msg)
                applyMedia()
            }
            "chat" -> host.chat(ChatLine(false, msg.str("name") ?: "", msg.str("color") ?: "#888", msg.str("text") ?: "", msg.str("by") == youId))
            "system" -> host.chat(ChatLine(true, "", "", msg.str("text") ?: "", false))
            "reaction" -> host.reaction(msg.str("emoji") ?: return, msg.str("color") ?: "#888")
            "queue" -> {
                queue = parseQueue(msg["queue"])
                host.changed(Change.QUEUE)
            }
            "settings" -> {
                msg.obj("settings")?.let(::applySettings)
                host.changed(Change.SETTINGS)
                applyLock()
            }
            "error" -> host.toast(msg.str("message") ?: "오류가 났어요")
            "join-request" -> {
                val id = msg.str("id") ?: return
                requests[id] = JoinRequest(id, msg.str("name") ?: "익명", msg.str("ip") ?: "", msg.bool("friend"))
                host.changed(Change.REQUESTS)
            }
            "join-cancel" -> {
                requests.remove(msg.str("id"))
                host.changed(Change.REQUESTS)
            }
            "kicked" -> {
                cleanup()
                host.toast("호스트가 Jam에서 내보냈어요")
                host.ended("호스트가 Jam에서 내보냈어요")
            }
            "ended" -> {
                cleanup()
                host.toast("호스트가 Jam을 종료했어요")
                host.ended("호스트가 Jam을 종료했어요")
            }
        }
    }

    // ======================================================================
    // Jam 탭 ↔ 서버
    // ======================================================================
    private fun send(msg: JsonObject) = conn?.send(msg) ?: false

    private fun applyLock() {
        if (!active) return
        host.toPage("jam:policy", buildJsonObject { put("locked", !canCrossSite()) })
        host.changed(Change.STATE)
    }

    private fun presenceObj(): JsonObject {
        val url = host.jamTabUrl()
        val priv = loginWait || (url != null && Site.isWebUrl(url) && Site.isSensitiveUrl(url))
        return buildJsonObject {
            put("type", "presence")
            if (priv || url == null) put("url", JsonNull) else put("url", url)
            put("title", if (priv) "" else host.jamTabTitle())
            put("ad", inAd)
            put("private", priv)
            put("away", !host.jamTabInFront())
        }
    }

    fun sendPresence() {
        if (!active) return
        send(presenceObj())
        host.changed(Change.MEMBERS)
    }

    /** 앱이 앞/뒤로 가거나 Jam 탭 ↔ 개인 탭 전환 */
    fun onJamTabFrontChanged() = sendPresence()

    /** Jam 탭 주소가 바뀌었다 (페이지 이동 시작 · 주소 바뀜) */
    fun onJamTabNavigated(url: String) {
        if (!active) return
        myView = null
        loginTimer?.cancel()
        sendPresence()
        refreshAllViews()
        if (!Site.isShareable(url)) {
            if (Site.isWebUrl(url)) enterLoginWait()
            host.changed(Change.NOW)
            return
        }
        if (loginWait) {
            // 로그인 중에 거쳐가는 일반 페이지는 공유하지 않는다. 로드가 끝나면 checkLoginFinished 가 판단
            if (Site.sameDoc(url, current?.url)) finishLoginWait(true)
            host.changed(Change.NOW)
            return
        }
        if (sameUrl(url, lastSyncedUrl)) return host.changed(Change.NOW)
        // 따라가기를 켜 둔 상태에서, 이동할 수 있는 곳이면 모두 함께 이동
        if (follow && canGo(url)) {
            lastSyncedUrl = url
            val title = host.jamTabTitle()
            current = Current(url, title, youId)
            send(buildJsonObject { put("type", "navigate"); put("url", url); put("title", title) })
        }
        host.changed(Change.NOW)
    }

    fun onJamTabTitle(title: String) {
        if (!active) return
        val url = host.jamTabUrl()
        val cur = current
        if (cur != null && cur.url == url && Site.isShareable(url)) {
            current = cur.copy(title = title)
            send(buildJsonObject { put("type", "title"); put("url", url); put("title", title) })
            host.changed(Change.NOW)
        }
        sendPresence()
    }

    fun onJamTabLoadFinished() {
        if (loginWait) checkLoginFinished()
    }

    /**
     * 웹뷰 탐색 잠금 검사: Jam 탭에서 사용자가 링크 등으로 [target] 으로 가려 할 때 막아야 하면 true
     * (데스크톱 main.js 의 will-navigate 잠금과 같음: 지금 페이지와 다른 사이트 + 권한 없음)
     */
    fun shouldBlockNavigation(target: String): Boolean {
        if (!active || canCrossSite()) return false
        val cur = host.jamTabUrl()
        if (cur == null || !Site.isWebUrl(cur) || Site.sameSite(target, cur)) return false
        return true
    }

    // ---------------- 로그인 대기 ----------------
    private fun enterLoginWait() {
        if (!loginWait) {
            loginWait = true
            host.toast("🔐 로그인이 필요해요. 내 로그인 창은 나만 보여요 — 로그인을 마치면 Jam 화면으로 자동으로 이동해요")
        }
        host.changed(Change.LOGIN)
    }

    private fun checkLoginFinished() {
        loginTimer?.cancel()
        if (!active || !loginWait || !Site.isShareable(host.jamTabUrl())) return
        // 로그인 과정 중간 페이지를 지나가는 경우를 피하려고, 일반 페이지에 잠시 머무는지 확인한다
        loginTimer = host.schedule(1500) {
            if (!active || !loginWait || !Site.isShareable(host.jamTabUrl()) || host.jamTabLoading()) return@schedule
            finishLoginWait(true)
        }
    }

    fun finishLoginWait(auto: Boolean) {
        if (!loginWait) return
        loginWait = false
        host.changed(Change.LOGIN)
        val cur = current
        if (cur != null && follow && !Site.sameDoc(host.jamTabUrl(), cur.url)) {
            lastSyncedUrl = cur.url
            host.loadInJamTab(cur.url)
        }
        applyMedia()
        sendPresence()
        if (auto) host.toast("✅ 로그인 완료! Jam 화면으로 이동했어요")
    }

    /** Jam 페이지로 돌아가기 (따라가기를 켰을 때 · 재접속 뒤) */
    fun catchUp() {
        val cur = current ?: return
        if (!active || loginWait) return
        if (!Site.sameDoc(host.jamTabUrl(), cur.url)) {
            lastSyncedUrl = cur.url
            host.loadInJamTab(cur.url)
        } else applyMedia()
    }

    // ---------------- 페이지 스크립트 → 나 ----------------
    private var viewportTimer: Cancellable? = null
    private var viewportPending: JsonObject? = null
    private var viewportLast = 0L

    private fun publishViewport(v: JsonObject) {
        viewportPending = v
        val wait = 150 - (clock() - viewportLast)
        if (wait <= 0) flushViewport()
        else if (viewportTimer == null) viewportTimer = host.schedule(wait) { viewportTimer = null; flushViewport() }
    }

    private fun flushViewport() {
        val v = viewportPending ?: return
        viewportPending = null
        viewportLast = clock()
        val url = host.jamTabUrl()
        if (!active || !Site.isShareable(url)) return
        send(buildJsonObject {
            put("type", "viewport"); put("url", url)
            for (k in listOf("vx", "vy", "vw", "vh", "top", "bot", "w", "h", "z")) put(k, v[k] ?: JsonNull)
        })
    }

    private var lastScrollSent = 0L
    private var lastCursorSent = 0L

    fun onPageEvent(channel: String, data: JsonElement?) {
        if (!active) return
        val url = host.jamTabUrl()
        val o = data as? JsonObject
        when (channel) {
            "jam:ready" -> {
                applyLock()
                refreshAllViews()
                if (followingId != null) host.schedule(400) { syncToFollowed() }
            }
            "jam:viewport" -> {
                o ?: return
                val user = o.bool("user")
                if (user && followingId != null) stopFollow("직접 스크롤해서 따라가기를 멈췄어요")
                myView = o
                publishViewport(o)
                // 내가 직접 스크롤했고 Jam 페이지에 있으면 → 스크롤 따라가기를 켠 사람들이 따라온다
                if (user && follow && Site.isShareable(url) && Site.sameDoc(url, current?.url) && clock() - lastScrollSent >= 100) {
                    lastScrollSent = clock()
                    send(buildJsonObject {
                        put("type", "scroll"); put("url", url); put("x", o["sx"] ?: JsonPrimitive(0)); put("y", o["sy"] ?: JsonPrimitive(0)); put("top", o["top"] ?: JsonNull)
                    })
                }
            }
            "jam:cursor" -> {
                if (o == null) {
                    send(buildJsonObject { put("type", "cursor"); put("x", JsonNull) })
                } else if (Site.isShareable(url) && host.jamTabInFront() && clock() - lastCursorSent >= 40) {
                    lastCursorSent = clock()
                    send(buildJsonObject {
                        put("type", "cursor"); put("url", url); put("x", o["x"] ?: JsonNull); put("y", o["y"] ?: JsonNull); put("a", o["a"] ?: JsonNull)
                    })
                }
            }
            "jam:click" -> if (o != null && Site.isShareable(url)) {
                send(buildJsonObject { put("type", "click"); put("url", url); put("x", o["x"] ?: JsonNull); put("y", o["y"] ?: JsonNull); put("a", o["a"] ?: JsonNull) })
            }
            "jam:ad" -> {
                inAd = (data as? JsonPrimitive)?.content == "true"
                sendPresence()
                if (inAd) host.toast("📺 광고가 끝나면 Jam 재생 위치로 자동으로 따라가요")
            }
            "jam:media" -> if (o != null && Site.isShareable(url)) publishMedia(url!!, o)
            "jam:media-tick" -> {
                // 마지막으로 재생을 조작한 사람이 4초마다 기준 시간을 알려준다
                val m = media
                if (o != null && m != null && m.by == youId && Site.sameDoc(m.url, url) && System.currentTimeMillis() - lastTickSent > 4000) publishMedia(url!!, o)
            }
            "jam:media-init" -> {
                val m = media
                val cur = current
                if (m != null && Site.sameDoc(m.url, url)) applyMedia()
                else if (o != null && cur != null && cur.by == youId && Site.sameDoc(cur.url, url) && Site.isShareable(url)) {
                    publishMedia(url!!, o) // 내가 모두를 이 영상으로 데려왔으면 내가 기준이 된다
                }
            }
            "jam:media-ready" -> applyMedia()
        }
    }

    private fun publishMedia(url: String, s: JsonObject) {
        val playing = s.bool("playing")
        val time = s.num("time") ?: return
        val rate = s.num("rate") ?: 1.0
        media = Media(url, playing, time, rate, youId, clock())
        lastTickSent = System.currentTimeMillis()
        send(buildJsonObject { put("type", "media"); put("url", url); put("playing", playing); put("time", time); put("rate", rate) })
    }

    private fun applyMedia() {
        val m = media ?: return
        if (!follow || inAd || loginWait || !Site.sameDoc(m.url, host.jamTabUrl())) return
        val elapsed = if (m.playing) (clock() - m.receivedAt) / 1000.0 * m.rate else 0.0
        host.toPage("jam:media-apply", buildJsonObject { put("playing", m.playing); put("time", m.time + elapsed); put("rate", m.rate) })
    }

    // 다른 사람의 화면 사각형을 내 페이지에 그린다 (같은 페이지를 볼 때만)
    private fun refreshView(m: Member) {
        if (m.id == youId) return
        val v = m.view
        if (v != null && Site.sameDoc(v.str("url"), host.jamTabUrl())) {
            host.toPage("jam:view", JsonObject(v + mapOf("id" to JsonPrimitive(m.id), "name" to JsonPrimitive(m.name), "color" to JsonPrimitive(m.color))))
        } else {
            host.toPage("jam:view-remove", JsonPrimitive(m.id))
        }
    }

    fun refreshAllViews() {
        if (!active) return
        members.forEach(::refreshView)
    }

    // ---------------- 사람 따라가기 ----------------
    fun startFollow(id: String) {
        if (id == youId || member(id) == null) return
        followingId = id
        host.toast("${member(id)?.name}님의 화면을 따라가요")
        host.changed(Change.MEMBERS)
        syncToFollowed()
    }

    fun stopFollow(reason: String? = null) {
        if (followingId == null) return
        followingId = null
        reason?.let(host::toast)
        host.changed(Change.MEMBERS)
    }

    private fun syncToFollowed() {
        val m = member(followingId) ?: return
        val url = m.url ?: m.view?.str("url") ?: return
        if (!Site.sameDoc(url, host.jamTabUrl())) {
            if (canGo(url)) {
                lastSyncedUrl = host.jamTabUrl() // 내가 따라간 이동을 다시 퍼뜨리지 않도록
                host.loadInJamTab(url)
            }
            return
        }
        m.view?.let { host.toPage("jam:follow-view", it) }
    }

    /** 다른 사람이 보고 있는 곳으로 내 화면만 옮기기 */
    fun scrollToMember(id: String) {
        val m = member(id) ?: return
        val v = m.view ?: return
        if (Site.sameDoc(v.str("url"), host.jamTabUrl())) host.toPage("jam:scroll-to-view", JsonObject(v + ("id" to JsonPrimitive(id))))
    }

    // ======================================================================
    // 내가 하는 일
    // ======================================================================
    fun sendChat(text: String) {
        val t = text.trim().take(500)
        if (t.isNotEmpty()) send(buildJsonObject { put("type", "chat"); put("text", t) })
    }

    fun sendReaction(emoji: String) = send(buildJsonObject { put("type", "reaction"); put("emoji", emoji) })

    fun queueAdd(url: String, title: String) {
        if (Site.isShareable(url)) send(buildJsonObject { put("type", "queue:add"); put("url", url); put("title", title) })
        else host.toast("이 페이지는 대기열에 넣을 수 없어요 (로그인 · 결제 페이지 등)")
    }

    fun queueRemove(id: String) = send(buildJsonObject { put("type", "queue:remove"); put("id", id) })
    fun queuePlay(id: String) = send(buildJsonObject { put("type", "queue:play"); put("id", id) })
    fun queueNext() = send(buildJsonObject { put("type", "queue:next") })

    // 호스트가 나가면 다음 사람이 호스트가 된다 → 폰도 승인 · 권한 · 내보내기를 할 수 있어야 한다
    fun answerRequest(id: String, ok: Boolean) {
        requests.remove(id)
        send(buildJsonObject { put("type", "approve"); put("id", id); put("ok", ok) })
        host.changed(Change.REQUESTS)
    }

    fun setPermission(id: String, canCross: Boolean) = send(buildJsonObject { put("type", "perm"); put("id", id); put("canCrossSite", canCross) })
    fun kick(id: String) = send(buildJsonObject { put("type", "kick"); put("id", id) })
    fun setAllowAll(allow: Boolean) = send(buildJsonObject { put("type", "settings"); put("allowAllCrossSite", allow) })
    fun setAccess(mode: String) = send(buildJsonObject { put("type", "settings"); put("access", mode) })

    /** 끝의 / 만 다른 주소는 같은 주소 (https://a.com 과 https://a.com/ — 웹뷰가 붙여서 돌려준다) */
    private fun sameUrl(a: String?, b: String?) = a != null && b != null && a.trimEnd('/') == b.trimEnd('/')

    // ---------------- 파싱 ----------------
    private fun applySettings(s: JsonObject) {
        allowAllCrossSite = s.bool("allowAllCrossSite")
        access = s.str("access") ?: if (s.bool("requireApproval")) "approval" else access
    }

    private fun parseQueue(e: JsonElement?): List<QueueItem> = (e as? JsonArray)?.mapNotNull { x ->
        val o = x as? JsonObject ?: return@mapNotNull null
        QueueItem(o.str("id") ?: return@mapNotNull null, o.str("url") ?: "", o.str("title") ?: "", o.str("addedBy") ?: "", o.str("addedByName") ?: "", o.str("color") ?: "#888")
    } ?: emptyList()

    private fun parseCurrent(o: JsonObject?): Current? = o?.str("url")?.let { Current(it, o.str("title") ?: "", o.str("by")) }

    private fun parseMedia(o: JsonObject): Media? {
        val url = o.str("url") ?: return null
        val age = (o.num("age") ?: 0.0).toLong()
        return Media(url, o.bool("playing"), o.num("time") ?: 0.0, o.num("rate") ?: 1.0, o.str("by"), clock() - age)
    }
}
