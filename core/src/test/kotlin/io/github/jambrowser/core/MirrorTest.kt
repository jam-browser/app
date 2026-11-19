package io.github.jambrowser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** 화면 미러링 보기: 조각 모으기 · 빠진 프레임 · 키프레임 요청 · 피드백 · 미러링 핑 (데스크톱 서버로) */
class MirrorTest {
    private val clips = File(System.getProperty("jam.repoDir") ?: ".", "core/src/test/node/mirror-clips")
    private val b64 = Base64.getEncoder()

    private fun mv(sid: String, f: Int, i: Int, n: Int, key: Boolean, data: ByteArray, ts: Long = 1000L * f) = buildJsonObject {
        put("type", "mv"); put("sid", sid); put("f", f); put("k", key); put("i", i); put("n", n); put("ts", ts); put("d", b64.encodeToString(data))
        if (key && i == 0) { put("w", 320); put("h", 180); put("c", "avc1.640028") }
    }

    @Test
    fun assemblesPiecesAndWaitsForKeyAfterGap() {
        val a = MirrorAssembler { 0.0 }
        a.open("abcdef")
        // 키프레임이 오기 전 델타는 버리고 키프레임 요청
        assertTrue(a.onChunk(mv("abcdef", 0, 0, 1, false, byteArrayOf(1))) is MirrorAssembler.Result.NeedKey)
        val a2 = MirrorAssembler { 0.0 }
        a2.open("abcdef")
        // 3조각 키프레임 (순서가 섞이고, 같은 조각이 두 번 와도)
        assertNull(a2.onChunk(mv("abcdef", 1, 2, 3, true, byteArrayOf(7, 8))))
        assertNull(a2.onChunk(mv("abcdef", 1, 0, 3, true, byteArrayOf(1, 2, 3))))
        assertNull(a2.onChunk(mv("abcdef", 1, 0, 3, true, byteArrayOf(1, 2, 3))))
        val done = a2.onChunk(mv("abcdef", 1, 1, 3, true, byteArrayOf(4, 5, 6))) as MirrorAssembler.Result.Decode
        assertEquals((1..8).map { it.toByte() }, done.frame.data.toList())
        assertEquals("video/avc", done.frame.mime)
        assertEquals(320, done.frame.w)
        // 다음 델타는 그대로
        assertTrue(a2.onChunk(mv("abcdef", 2, 0, 1, false, byteArrayOf(9))) is MirrorAssembler.Result.Decode)
        // 3번이 빠지고 4번이 오면 → 키프레임까지 기다림
        assertTrue(a2.onChunk(mv("abcdef", 4, 0, 1, false, byteArrayOf(9))) is MirrorAssembler.Result.NeedKey)
        assertTrue(a2.waitingKey)
        assertTrue(a2.onChunk(mv("abcdef", 5, 0, 1, false, byteArrayOf(9))) is MirrorAssembler.Result.NeedKey)
        assertTrue(a2.onChunk(mv("abcdef", 6, 0, 1, true, byteArrayOf(9))) is MirrorAssembler.Result.Decode)
        // 다른 미러링(sid) 조각은 무시
        assertNull(a2.onChunk(mv("zzzzzz", 7, 0, 1, true, byteArrayOf(9))))
        // 이상한 영상 형식의 키프레임은 쓰지 않음
        val bad = buildJsonObject { put("type", "mv"); put("sid", "abcdef"); put("f", 8); put("k", true); put("i", 0); put("n", 1); put("ts", 0); put("d", "AA=="); put("w", 320); put("h", 180); put("c", "hevc") }
        assertTrue(a2.onChunk(bad) is MirrorAssembler.Result.NeedKey)
    }

    @Test
    fun latencyIsRelativeToBestSeen() {
        var now = 10_000.0
        val a = MirrorAssembler { now }
        a.open("abcdef")
        // 찍은 시각 = 0µs 에 10초(10_000ms) 도착 → 기준
        a.onChunk(mv("abcdef", 0, 0, 1, true, byteArrayOf(1), ts = 0))
        assertEquals(0.0, a.late, 0.001)
        // 다음 프레임: 찍은 시각 +100ms 인데 도착은 +400ms → 300ms 늦음
        now += 400
        a.onChunk(mv("abcdef", 1, 0, 1, false, byteArrayOf(1), ts = 100_000))
        assertEquals(300.0, a.late, 0.001)
    }

    @Test
    fun avcConfigFromRealKeyframe() {
        val lines = File(clips, "avc.jsonl").readLines()
        val key = Json.parseToJsonElement(lines[1]) as JsonObject
        assertTrue(key.bool("k"))
        val au = Base64.getDecoder().decode(key.str("d"))
        val nals = MirrorAssembler.nalUnits(au).map { (s, _) -> au[s].toInt() and 0x1f }
        assertTrue("SPS(7) · PPS(8) · IDR(5) 이 있어야 함: $nals", nals.containsAll(listOf(7, 8, 5)))
        val (sps, pps) = MirrorAssembler.avcConfig(au)!!
        assertEquals(listOf<Byte>(0, 0, 0, 1, 0x67), sps.take(5))
        assertEquals(listOf<Byte>(0, 0, 0, 1, 0x68), pps.take(5))
        assertNull(MirrorAssembler.avcConfig(byteArrayOf(0, 0, 1, 0x65, 1, 2)))
    }

    /** 데스크톱 서버로: 호스트가 미러링(H.264 · VP8) → 폰이 프레임을 받아 디코더로, mkey · mfb · mping */
    @Test
    fun watchesDesktopMirror() = DesktopHarness("open").use { pc ->
        val h = FakeHost()
        val c = h.onMain { JamController(h, { inv, name, resume, l -> JamConnection(JamConnection.defaultClient(), inv, name, resume, l).start() }) }
        h.controller = c
        h.onMain { c.join(Invite.parse(pc.invite)!!, "미러폰") }
        h.await("load https://example.com/start")

        for (clip in listOf("avc", "vp8")) {
            h.frames.clear()
            pc.play(File(clips, "$clip.jsonl").path)
            val started = pc.next("mirror-started")
            h.await("mirror ${started.str("sid")} 시험 화면")
            pc.hostMsg("mkey") // 보기 시작하면 바로 키프레임 요청
            pc.next("mirror-done", 10_000)
            Thread.sleep(300)
            val frames = h.frames.toList()
            assertTrue("$clip: 받은 프레임 ${frames.size}", frames.size >= 28)
            assertTrue(frames.first().key)
            assertEquals(if (clip == "avc") "avc1.640028" else "vp8", frames.first().codec)
            assertEquals(320, frames.first().w)
            assertEquals(frames.indices.map { frames.first().f + it }, frames.map { it.f }) // 빠짐없이 차례대로
            if (clip == "avc") assertNotNull(MirrorAssembler.avcConfig(frames.first().data))

            // 디코더가 그렸다고 알리면 1초마다 지연 피드백(mfb) → 서버가 호스트의 mstat 에 반영
            pc.play(File(clips, "$clip.jsonl").path)
            val again = pc.next("mirror-started")
            h.await("mirror ${again.str("sid")}")
            h.onMain { c.mirrorDecoded(); c.mirrorDecodeFailed(unsupported = true) }
            val st = pc.hostMsg("mstat", 8000) { it.bool("bad") }
            assertEquals(1.0, st.num("viewers")!!, 0.0)
            pc.next("mirror-done", 10_000)
        }

        // 미러링 화면 위 핑 → 미러링하는 사람(호스트)에게만
        assertTrue(h.onMain { c.sendMirrorPing(0.25, 0.75, "look") })
        val mp = pc.hostMsg("mping")
        assertEquals("look", mp.str("k"))
        assertEquals(0.25, mp.num("x")!!, 1e-9)
        assertFalse(h.onMain { c.sendMirrorPing(1.5, 0.5, "here") })

        // 호스트가 미러링을 끝내면 화면도 닫힘
        pc.stopMirror()
        h.await("mirror null")
        h.onMain { c.leave() }
    }
}
