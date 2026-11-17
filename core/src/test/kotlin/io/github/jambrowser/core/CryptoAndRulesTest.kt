package io.github.jambrowser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

class CryptoAndRulesTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun hkdfMatchesRfc5869EmptySalt() {
        // RFC 5869 Test Case 3 (salt · info 없음)
        val okm = JamCrypto.hkdf(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", hex(okm))
    }

    @Test
    fun sealOpenRoundTripAndTamperDetection() {
        val keys = JamCrypto.deriveKeys(JamCrypto.newSecret())
        val frame = JamCrypto.seal(keys, """{"type":"chat","text":"안녕"}""", JamCrypto.C2S)
        assertEquals("""{"type":"chat","text":"안녕"}""", JamCrypto.open(keys, frame, JamCrypto.C2S))
        // 방향(AAD)이 다르면 실패 — 서버가 보낸 메시지를 되돌려 보내는 공격 방지
        try { JamCrypto.open(keys, frame, JamCrypto.S2C); fail("방향이 다른데 열림") } catch (_: Exception) {}
        frame[frame.size - 20] = (frame[frame.size - 20].toInt() xor 1).toByte()
        try { JamCrypto.open(keys, frame, JamCrypto.C2S); fail("위조된 프레임이 열림") } catch (_: Exception) {}
        val other = JamCrypto.deriveKeys(JamCrypto.newSecret())
        try { JamCrypto.open(other, JamCrypto.seal(keys, "{}", JamCrypto.C2S), JamCrypto.C2S); fail("다른 키로 열림") } catch (_: Exception) {}
    }

    @Test
    fun badSecretsRejected() {
        for (s in listOf("short", "!!!!", JamCrypto.b64u(ByteArray(31)))) {
            try { JamCrypto.deriveKeys(s); fail("잘못된 비밀키 $s 를 받아줌") } catch (_: IllegalArgumentException) {}
        }
    }

    /** 데스크톱의 진짜 shared/jam-crypto.js 와 바이트 단위로 같은지 (node 로 실행) */
    @Test
    fun matchesDesktopJamCryptoJs() {
        val (node, desktop) = Env.requireDesktop()
        val secret = JamCrypto.newSecret()
        val keys = JamCrypto.deriveKeys(secret)
        val phoneFrame = JamCrypto.seal(keys, """{"type":"presence","title":"폰에서 ✓","n":7}""", JamCrypto.C2S)
        val p = ProcessBuilder(
            node, File(Env.nodeScripts, "crypto-vectors.js").path, desktop.path, secret, "nonceABC_-", "K7QXZ",
            Base64.getEncoder().encodeToString(phoneFrame),
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        p.waitFor(20, TimeUnit.SECONDS)
        val r = Json.parseToJsonElement(out) as JsonObject
        assertEquals(hex(keys.enc), r.str("enc"))
        assertEquals(hex(keys.auth), r.str("auth"))
        assertEquals(JamCrypto.makeProof(keys, "nonceABC_-", "K7QXZ"), r.str("proof"))
        assertEquals("폰에서 ✓", r.obj("opened")?.str("title"))
        assertTrue(r.bool("wrongDirectionRejected"))
        val fromDesktop = Json.parseToJsonElement(JamCrypto.open(keys, Base64.getDecoder().decode(r.str("forPhone")), JamCrypto.S2C)) as JsonObject
        assertEquals("데스크톱에서 보낸 글 ✓", fromDesktop.str("text"))
        assertTrue(JamCrypto.checkProof(keys, "nonceABC_-", "K7QXZ", r.str("proof")!!))
    }

    @Test
    fun invitesParseLikeDesktop() {
        val s = JamCrypto.newSecret()
        Invite.parse("jam://192.168.0.5:3030/abcde/$s")!!.let {
            assertEquals("ws://192.168.0.5:3030", it.server); assertEquals("ABCDE", it.code); assertEquals(s, it.secret); assertFalse(it.relay)
        }
        assertEquals("ws://10.0.0.2:3030", Invite.parse("10.0.0.2/ABCDE/$s")!!.server)
        Invite.parse("jamr://relay.example.com/abcdef1234/QWERT/$s")!!.let {
            assertEquals("ws://relay.example.com:3031/join/abcdef1234", it.server); assertEquals("QWERT", it.code); assertTrue(it.relay)
        }
        assertEquals("wss://relay.example.com/join/abcdef1234", Invite.parse("jamrs://relay.example.com/abcdef1234/QWERT/$s")!!.server)
        assertEquals("ws://r.example:4000/join/abcdef1234", Invite.parse("jamr://r.example:4000/abcdef1234/QWERT/$s/")!!.server)
        assertNull(Invite.parse("https://example.com/ABCDE/$s"))
        assertNull(Invite.parse("jam://host/ABCDE/tooShortSecret"))
        assertNotNull(Invite.find("이 코드로 들어와!\njam://192.168.0.5:3030/ABCDE/$s 고마워"))
    }

    @Test
    fun siteRulesLikeDesktop() {
        assertEquals("youtube.com", Site.siteOf("https://m.youtube.com/watch?v=1"))
        assertEquals("naver.co.kr", Site.siteOf("https://news.naver.co.kr/a"))
        assertTrue(Site.sameSite("https://www.youtube.com/a", "https://m.youtube.com/b"))
        assertFalse(Site.sameSite("https://youtube.com", "https://google.com"))
        assertTrue(Site.isSensitiveUrl("https://accounts.google.com/x"))
        assertTrue(Site.isSensitiveUrl("https://example.com/login"))
        assertTrue(Site.isSensitiveUrl("https://example.com/cb?code=123"))
        assertTrue(Site.isSensitiveUrl("https://example.com/cb#access_token=abc"))
        assertFalse(Site.isSensitiveUrl("https://example.com/watch?v=abc"))
        assertFalse(Site.isShareable("file:///sdcard/a.html"))
        assertTrue(Site.sameDoc("https://a.com/x/#top", "https://a.com/x"))
        assertEquals("https://example.com", Site.toUrl("example.com"))
        assertEquals("http://192.168.0.2:8080", Site.toUrl("192.168.0.2:8080"))
        assertTrue(Site.toUrl("고양이 영상")!!.startsWith("https://www.google.com/search?q="))
    }
}
