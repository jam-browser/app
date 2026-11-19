package io.github.jambrowser.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** 핑: 데스크톱 서버와 주고받기 · 도배 방지 · 페이지가 마음대로 핑을 보내지 못하게 */
class PingTest {
    private fun msg(type: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject { put("type", type); block() }

    @Test
    fun rateLimitMatchesServer() {
        val now = AtomicLong(1_000_000)
        val c = JamController(FakeHost(), { _, _, _, _ -> error("연결 안 함") }, { now.get() })
        repeat(6) { assertTrue("순간 6개까지", c.takePingToken()) }
        assertFalse(c.takePingToken())
        now.addAndGet(499)
        assertFalse("0.5초가 안 지나면 아직", c.takePingToken())
        now.addAndGet(1)
        assertTrue("초당 2개씩 다시 생김", c.takePingToken())
        assertFalse(c.takePingToken())
        now.addAndGet(10_000)
        repeat(6) { assertTrue(c.takePingToken()) }
        assertFalse("오래 쉬어도 6개까지만 모임", c.takePingToken())
    }

    @Test
    fun pingsRoundTripThroughDesktopServer() = DesktopHarness("open").use { pc ->
        val h = FakeHost()
        val c = h.onMain { JamController(h, { inv, name, resume, l -> JamConnection(JamConnection.defaultClient(), inv, name, resume, l).start() }) }
        h.controller = c
        h.onMain { c.join(Invite.parse(pc.invite)!!, "핑폰") }
        h.await("load https://example.com/start")
        h.tabUrl = "https://example.com/start"
        h.onMain { c.onJamTabNavigated(h.tabUrl!!) }
        h.await("chat 📍 화면을 길게 누르면") // 처음 들어오면 핑 휠 안내
        val hostId = pc.ready.str("hostId")!!

        // 페이지 스크립트가 앱의 부탁 없이 보낸 핑은 무시
        val anchor = buildJsonObject { put("p", "main:nth-of-type(1)"); put("fx", 0.5); put("fy", 0.5) }
        h.onMain { c.onPageEvent("jam:ping", buildJsonObject { put("x", 0.1); put("y", 0.2); put("a", anchor) }) }

        // 핑 휠 → 페이지에 자리 계산 부탁 → 페이지가 답하면 서버로 + 내 화면에도 표시
        assertTrue(h.onMain { c.requestPing(0.5, 0.25, "warn") })
        h.await("page jam:ping-at")
        h.onMain { c.onPageEvent("jam:ping", buildJsonObject { put("x", 0.3); put("y", 0.4); put("a", anchor); put("k", "omw") }) }
        val got = pc.hostMsg("ping")
        assertEquals("warn", got.str("k")) // 종류는 페이지가 아니라 앱(휠)이 정함
        assertEquals("https://example.com/start", got.str("url"))
        assertEquals(0.3, got.num("x")!!, 1e-9)
        assertEquals("main:nth-of-type(1)", got.obj("a")?.str("p"))
        assertEquals(1, h.log.count { it.startsWith("page jam:ping {") && it.contains("\"k\":\"warn\"") && it.contains("\"name\":\"핑폰\"") })
        // 부탁 없이 온 첫 핑은 서버로 가지 않았다
        Thread.sleep(300)
        assertTrue(runCatching { pc.hostMsg("ping", 300) }.isFailure)

        // PC 가 같은 페이지에 찍은 핑 → 내 페이지에 그림 (이름 · 색 · 앵커 · 표시 시간)
        pc.send(msg("ping") { put("url", "https://example.com/start"); put("x", 0.6); put("y", 0.7); put("a", anchor); put("k", "what") })
        h.await("page jam:ping {\"id\":\"$hostId-")
        val shown = h.log.last { it.startsWith("page jam:ping {\"id\":\"$hostId-") }
        assertTrue(shown, shown.contains("\"name\":\"PC호스트\"") && shown.contains("\"k\":\"what\"") && shown.contains("\"sec\":6"))
        // 다른 페이지에 찍은 핑 → 알림
        pc.send(msg("ping") { put("url", "https://example.com/other"); put("x", 0.1); put("y", 0.1); put("k", "look") })
        h.await("toast 👀 PC호스트님이 다른 페이지")
        // 이상한 종류는 서버가 here 로 바꿈
        pc.send(msg("ping") { put("url", "https://example.com/start"); put("x", 0.1); put("y", 0.1); put("k", "bomb") })
        Thread.sleep(500)
        assertTrue(h.log.any { it.startsWith("page jam:ping {\"id\":\"$hostId-") && it.contains("\"k\":\"here\"") })

        // 도배: 6개 넘게 연달아 찍으면 앱이 먼저 막는다 (서버 한도와 같음)
        var sent = 0
        repeat(8) {
            if (h.onMain { c.requestPing(0.5, 0.5, "here") }) {
                h.onMain { c.onPageEvent("jam:ping", buildJsonObject { put("x", 0.5); put("y", 0.5) }) }
            }
        }
        h.await("toast 핑을 너무 자주")
        repeat(10) { runCatching { pc.hostMsg("ping", 800) }.onSuccess { sent++ } }
        assertTrue("순간 6개(첫 핑 포함) + 그동안 다시 생긴 것까지만: $sent", sent in 5..7)

        // 로그인 페이지에서는 핑을 찍지 않는다
        h.tabUrl = "https://example.com/login"
        h.onMain { c.onJamTabNavigated(h.tabUrl!!) }
        assertFalse(h.onMain { c.requestPing(0.5, 0.5, "here") })

        // 끄면 받은 핑도 그리지 않음
        h.tabUrl = "https://example.com/start"
        h.onMain { c.onJamTabNavigated(h.tabUrl!!); c.pingEnabled = false }
        val before = h.log.count { it.startsWith("page jam:ping {") }
        pc.send(msg("ping") { put("url", "https://example.com/start"); put("x", 0.6); put("y", 0.7); put("k", "here") })
        pc.send(msg("chat") { put("text", "끝") })
        h.await("chat 끝")
        assertEquals(before, h.log.count { it.startsWith("page jam:ping {") })
        h.onMain { c.leave() }
    }
}
