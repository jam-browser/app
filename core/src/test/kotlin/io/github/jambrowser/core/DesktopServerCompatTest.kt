package io.github.jambrowser.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 폰 앱의 프로토콜 코드(JamConnection)가 데스크톱의 진짜 Jam 서버(server/jam-server.js, node)와 통하는지 확인
 * 호스트 역할은 desktop-host.js 가 데스크톱 앱과 같은 방식으로 한다.
 */
class DesktopServerCompatTest {
    private fun msg(type: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject { put("type", type); block() }

    @Test
    fun phoneJoinsDesktopJamWithApprovalAndSyncs() = DesktopHarness("approval").use { pc ->
        val phone = PhoneGuest(pc.invite, "폰친구")
        val req = pc.hostMsg("join-request")
        assertEquals("폰친구", req.str("name")) // 이름은 p(암호화)로 갔는데 호스트가 제대로 풀었다
        assertEquals("127.0.0.1", req.str("ip"))
        val w = phone.awaitWelcome()
        assertTrue("승인 대기(pending)를 거쳐야 함", phone.pendingSeen)
        val room = w.obj("room")!!
        assertEquals(pc.code, room.str("code"))
        assertEquals("https://example.com/start", room.obj("current")?.str("url"))
        assertEquals(2, (room["members"] as JsonArray).size)
        assertEquals("approval", room.obj("settings")?.str("access"))
        val myId = w.obj("you")!!.str("id")!!
        pc.hostMsg("members")

        // 채팅: 폰 → 모두 (서버가 보낸 사람에게도 돌려준다)
        phone.conn.send(msg("chat") { put("text", "폰에서 안녕 👋") })
        assertEquals("폰에서 안녕 👋", pc.hostMsg("chat").str("text"))
        assertEquals("폰친구", phone.next("chat").str("name"))

        // 호스트 이동 → 폰이 받음
        pc.send(msg("navigate") { put("url", "https://example.com/page2"); put("title", "두 번째") })
        assertEquals("https://example.com/page2", phone.next("navigate").str("url"))

        // 같은 사이트 이동은 폰도 자유롭게
        phone.conn.send(msg("navigate") { put("url", "https://www.example.com/page3"); put("title", "세 번째") })
        val nav = pc.hostMsg("navigate")
        assertEquals("https://www.example.com/page3", nav.str("url"))
        assertEquals(myId, nav.str("by"))

        // 다른 사이트는 권한이 없으면 거절
        phone.conn.send(msg("navigate") { put("url", "https://other-site.org/") })
        assertEquals("no-cross-site", phone.next("error").str("code"))

        // 호스트가 권한을 주면 다른 사이트도 가능
        pc.send(msg("perm") { put("id", myId); put("canCrossSite", true) })
        phone.next("members") { m -> (m["members"] as JsonArray).any { (it as JsonObject).str("id") == myId && it.bool("canCrossSite") } }
        phone.conn.send(msg("navigate") { put("url", "https://other-site.org/"); put("title", "다른 곳") })
        assertEquals("https://other-site.org/", pc.hostMsg("navigate").str("url"))

        // 화면 영역(뷰포트) · 커서 · 클릭 · 상태 · 영상 위치가 호스트에게 전달
        val anchor = buildJsonObject { put("p", "main:nth-of-type(1)>p:nth-of-type(3)"); put("fx", 0.5); put("fy", 0.25); put("off", 4) }
        phone.conn.send(msg("viewport") {
            put("url", "https://other-site.org/"); put("vx", 0); put("vy", 0.3); put("vw", 1); put("vh", 0.2)
            put("top", anchor); put("bot", JsonNull); put("w", 412); put("h", 915); put("z", 2.63)
        })
        val vp = pc.hostMsg("viewport")
        assertEquals(412.0, vp.num("w")!!, 0.0)
        assertEquals(2.63, vp.num("z")!!, 1e-9)
        assertEquals("main:nth-of-type(1)>p:nth-of-type(3)", vp.obj("top")?.str("p"))
        phone.conn.send(msg("cursor") { put("url", "https://other-site.org/"); put("x", 0.4); put("y", 0.6); put("a", anchor) })
        assertEquals(0.4, pc.hostMsg("cursor").num("x")!!, 1e-9)
        phone.conn.send(msg("click") { put("url", "https://other-site.org/"); put("x", 0.4); put("y", 0.6); put("a", JsonNull) })
        pc.hostMsg("click")
        phone.conn.send(msg("presence") { put("url", "https://other-site.org/"); put("title", "다른 곳"); put("ad", true); put("private", false); put("away", false) })
        assertTrue(pc.hostMsg("presence").obj("presence")!!.bool("ad"))
        // 로그인 페이지 주소는 서버가 공유하지 않는다 (private 처리)
        phone.conn.send(msg("presence") { put("url", "https://accounts.google.com/signin"); put("title", "로그인") })
        val priv = pc.hostMsg("presence").obj("presence")!!
        assertTrue(priv.bool("private"))
        assertTrue(priv["url"] is JsonNull)
        phone.conn.send(msg("media") { put("url", "https://other-site.org/"); put("playing", true); put("time", 12.5); put("rate", 1) })
        assertEquals(12.5, pc.hostMsg("media").num("time")!!, 1e-9)

        // 호스트 → 폰: 영상 · 커서 · 스크롤 · 반응 · 대기열
        pc.send(msg("media") { put("url", "https://other-site.org/"); put("playing", false); put("time", 30); put("rate", 1.5) })
        phone.next("media").let { assertEquals(30.0, it.num("time")!!, 0.0); assertFalse(it.bool("playing")) }
        pc.send(msg("scroll") { put("url", "https://other-site.org/"); put("x", 0); put("y", 0.5); put("top", anchor) })
        assertEquals(0.5, phone.next("scroll").num("y")!!, 0.0)
        pc.send(msg("reaction") { put("emoji", "🔥") })
        assertEquals("🔥", phone.next("reaction").str("emoji"))
        phone.conn.send(msg("queue:add") { put("url", "https://example.com/next"); put("title", "다음 영상") })
        val q = phone.next("queue") { (it["queue"] as JsonArray).isNotEmpty() }
        assertEquals("폰친구", ((q["queue"] as JsonArray)[0] as JsonObject).str("addedByName"))

        // 호스트가 종료 → 폰이 ended 를 받음
        pc.send(msg("end"))
        phone.next("ended")
        phone.close()
    }

    @Test
    fun rejectedAndWrongSecretAndFullFlowErrors() = DesktopHarness("approval").use { pc ->
        val rejected = PhoneGuest(pc.invite, "거절될폰")
        rejected.next("__failed", 8000)
        assertEquals("rejected", rejected.failure!!.first)

        // 형식은 맞지만 다른 비밀키 → 이유를 구분하지 않고 bad-invite
        val wrong = PhoneGuest("jam://127.0.0.1:${pc.invite.substringAfter("127.0.0.1:").substringBefore('/')}/${pc.code}/${JamCrypto.newSecret()}", "남의폰")
        wrong.next("__failed", 8000)
        assertEquals("bad-invite", wrong.failure!!.first)
    }

    @Test
    fun openModeJoinsWithoutApproval() = DesktopHarness("open").use { pc ->
        val phone = PhoneGuest(pc.invite, "자유입장폰")
        val w = phone.awaitWelcome()
        assertFalse(phone.pendingSeen)
        assertEquals("open", w.obj("room")!!.obj("settings")!!.str("access"))
        phone.close()
    }

    @Test
    fun friendsModeRefusesPhoneWithoutIdentity() = DesktopHarness("friends").use { pc ->
        // 폰은 아직 브라우저 계정 신원(Ed25519) 서명을 보내지 않는다 → 친구만 Jam 은 들어갈 수 없다는 안내를 받아야 함
        val phone = PhoneGuest(pc.invite, "친구아닌폰")
        phone.next("__failed", 8000)
        val code = phone.failure!!.first
        // 데스크톱 서버 버전에 따라: 친구 모드가 있으면 not-friend, 없으면(예전 서버) 승인 대기로 처리
        assertTrue("예상 밖의 실패 코드: $code", code == "not-friend" || code == "timeout" || code == "rejected")
    }

    @Test
    fun reconnectWithResumeTokenSkipsApproval() = DesktopHarness("approval").use { pc ->
        val phone = PhoneGuest(pc.invite, "와이파이폰")
        val w = phone.awaitWelcome()
        val resume = w.str("resume")!!
        pc.hostMsg("join-request")
        pc.dropGuests()
        phone.next("__closed", 5000)
        pc.hostMsg("left")

        val again = PhoneGuest(pc.invite, "와이파이폰", resume)
        again.awaitWelcome()
        assertFalse("재접속은 승인 없이 들어와야 함", again.pendingSeen)
        // 두 번째 참여 요청은 호스트에게 가지 않았다
        pc.hostMsg("members") { m -> (m["members"] as JsonArray).size == 2 }
        again.close()
    }

    @Test
    fun phoneJoinsThroughRelayAndRelaySeesNoPlaintext() = DesktopHarness("approval", relay = true).use { pc ->
        val inv = pc.relayInvite!!
        assertTrue(inv.startsWith("jamr://"))
        val phone = PhoneGuest(inv, "멀리있는폰")
        val req = pc.hostMsg("join-request", 8000)
        assertEquals("멀리있는폰", req.str("name"))
        assertTrue("중계로 들어온 손님은 relay: 로 표시", req.str("ip")!!.startsWith("relay:"))
        phone.awaitWelcome()
        phone.conn.send(msg("chat") { put("text", "중계 비밀 채팅") })
        pc.hostMsg("chat")
        pc.send(msg("navigate") { put("url", "https://example.com/relay-secret-page"); put("title", "비밀 제목") })
        phone.next("navigate")
        val seen = pc.relaySeen(listOf("멀리있는폰", "중계 비밀 채팅", "relay-secret-page", "비밀 제목", pc.secret))
        assertTrue(seen.num("bytes")!! > 0)
        assertEquals("중계 서버가 평문을 봄: $seen", 0, (seen["found"] as JsonArray).size)
        phone.close()
    }

    @Test
    fun controllerFollowsDesktopHost() = DesktopHarness("open").use { pc ->
        val h = FakeHost()
        val c = h.onMain { JamController(h, { inv, name, resume, l -> JamConnection(JamConnection.defaultClient(), inv, name, resume, l).start() }) }
        h.controller = c
        h.onMain { c.join(Invite.parse(pc.invite)!!, "컨트롤러폰") }
        h.await("load https://example.com/start")
        h.tabUrl = "https://example.com/start"
        h.onMain { c.onJamTabNavigated(h.tabUrl!!) }
        assertTrue(h.onMain { c.active })
        assertNull(h.onMain { if (c.isHost()) "host" else null })

        // PC 호스트가 이동 → 폰 Jam 탭이 따라감
        pc.send(msg("navigate") { put("url", "https://example.com/watch?v=1"); put("title", "영상") })
        h.await("load https://example.com/watch?v=1")
        h.tabUrl = "https://example.com/watch?v=1"
        h.onMain { c.onJamTabNavigated(h.tabUrl!!) }

        // 폰에서 같은 사이트 링크로 이동 → PC 에 navigate 가 감
        h.tabUrl = "https://example.com/watch?v=2"
        h.onMain { c.onJamTabNavigated(h.tabUrl!!) }
        assertEquals("https://example.com/watch?v=2", pc.hostMsg("navigate").str("url"))

        // 다른 사이트는 잠금 (권한 없음)
        assertTrue(h.onMain { c.shouldBlockNavigation("https://other.org/") })
        assertFalse(h.onMain { c.shouldBlockNavigation("https://m.example.com/x") })

        // 로그인 페이지로 가면 로그인 대기 + 주소 비공개, 그동안 PC 이동은 따라가지 않음
        h.tabUrl = "https://example.com/login"
        h.onMain { c.onJamTabNavigated(h.tabUrl!!) }
        assertTrue(h.onMain { c.loginWait })
        assertTrue(pc.hostMsg("presence") { it.obj("presence")!!.bool("private") }.obj("presence")!!["url"] is JsonNull)
        pc.send(msg("navigate") { put("url", "https://example.com/watch?v=3"); put("title", "새 영상") })
        Thread.sleep(500)
        assertFalse("로그인 중에는 이동하면 안 됨", h.log.contains("load https://example.com/watch?v=3"))
        // 로그인 완료 → 일반 페이지에 머물면 Jam 의 최신 페이지로 자동 이동
        h.tabUrl = "https://example.com/home"
        h.onMain { c.onJamTabNavigated(h.tabUrl!!); c.onJamTabLoadFinished() }
        h.await("load https://example.com/watch?v=3", 4000)
        assertFalse(h.onMain { c.loginWait })

        // PC 가 영상 위치를 보내면 페이지 스크립트로 media-apply 가 감 (광고 중이면 안 감)
        h.tabUrl = "https://example.com/watch?v=3"
        h.onMain { c.onJamTabNavigated(h.tabUrl!!) }
        h.onMain { c.onPageEvent("jam:ad", kotlinx.serialization.json.JsonPrimitive(true)) }
        pc.send(msg("media") { put("url", "https://example.com/watch?v=3"); put("playing", true); put("time", 100); put("rate", 1) })
        Thread.sleep(400)
        assertFalse(h.log.any { it.startsWith("page jam:media-apply") })
        h.onMain { c.onPageEvent("jam:ad", kotlinx.serialization.json.JsonPrimitive(false)); c.onPageEvent("jam:media-ready", null) }
        assertTrue(h.log.any { it.startsWith("page jam:media-apply") })

        // 채팅이 화면 쪽으로 전달
        pc.send(msg("chat") { put("text", "PC에서 왔어요") })
        h.await("chat PC에서 왔어요")

        // PC 화면 사각형이 같은 페이지일 때 내 페이지에 그려짐
        pc.send(msg("viewport") {
            put("url", "https://example.com/watch?v=3"); put("vx", 0); put("vy", 0); put("vw", 1); put("vh", 0.3); put("top", JsonNull); put("bot", JsonNull)
            put("w", 1920); put("h", 1080); put("z", 1)
        })
        h.await("page jam:view")
        pc.send(msg("end"))
        h.await("ended 호스트가 Jam을 종료했어요")
    }
}
