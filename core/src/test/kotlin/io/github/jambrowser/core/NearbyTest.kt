package io.github.jambrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 근처 Jam: 알림 형식 · X25519 · 페어링(6자리 확인 코드)을 데스크톱의 진짜 서버 · nearby.js 와 맞춰 본다 */
class NearbyTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun x25519Rfc7748Vectors() {
        // RFC 7748 5.2
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            hexOf(X25519.scalarMult(hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"), hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))),
        )
        // RFC 7748 6.1 (앨리스 · 밥)
        val a = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hexOf(X25519.scalarMult(a, X25519.BASE)))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", hexOf(X25519.scalarMult(b, X25519.BASE)))
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, hexOf(X25519.scalarMult(a, X25519.scalarMult(b, X25519.BASE))))
        assertEquals(shared, hexOf(X25519.scalarMult(b, X25519.scalarMult(a, X25519.BASE))))
        // 약한 키(0)는 거절
        val (priv, _) = X25519.newKeyPair()
        assertNull(Pairing.derive(priv, JamCrypto.b64u(ByteArray(32)), "00".repeat(32)))
    }

    @Test
    fun boxRoundTripAndTamper() {
        val key = ByteArray(32) { it.toByte() }
        val box = Pairing.sealBox(key, """{"secret":"x","ticket":"t"}""")
        assertEquals("t", Pairing.openBox(key, box)!!.str("ticket"))
        val raw = JamCrypto.unb64u(box)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertNull(Pairing.openBox(key, JamCrypto.b64u(raw)))
        assertNull(Pairing.openBox(ByteArray(32), box))
    }

    @Test
    fun beaconParsing() {
        val ok = """{"t":"jam-nearby","v":1,"app":"0.3.0","id":"abcdEFGH1234","name":"민수의\u0007 PC","port":3030,"code":"AB2CD","access":"approval","n":2}""".toByteArray()
        val b = Nearby.parseBeacon(ok, ok.size, "10.20.30.40")!!
        assertEquals("민수의 PC", b.name)
        assertEquals("ws://10.20.30.40:3030", b.server)
        assertNull("인터넷 주소에서 온 알림은 무시", Nearby.parseBeacon(ok, ok.size, "8.8.8.8"))
        for (bad in listOf(
            """{"t":"jam-nearby","v":2,"id":"abcdEFGH1234","port":3030,"code":"AB2CD","access":"approval","n":2}""",
            """{"t":"jam-nearby","v":1,"id":"short","port":3030,"code":"AB2CD","access":"approval","n":2}""",
            """{"t":"jam-nearby","v":1,"id":"abcdEFGH1234","port":70000,"code":"AB2CD","access":"approval","n":2}""",
            """{"t":"jam-nearby","v":1,"id":"abcdEFGH1234","port":3030,"code":"ab2cd","access":"approval","n":2}""",
            """{"t":"jam-nearby","v":1,"id":"abcdEFGH1234","port":3030,"code":"AB2CD","access":"anyone","n":2}""",
            """{"t":"jam-nearby","v":1,"id":"abcdEFGH1234","port":3030,"code":"AB2CD","access":"open","n":1.5}""",
            "not json",
        )) assertNull(bad, Nearby.parseBeacon(bad.toByteArray(), bad.length, "10.0.0.2"))
        assertTrue(Nearby.isPrivateIp("172.20.1.1"))
        assertFalse(Nearby.isPrivateIp("172.32.1.1"))
        assertTrue(Nearby.isPrivateIp("fe80::1"))
        assertFalse(Nearby.isPrivateIp("relay:1.2.3.4"))
    }

    /** 데스크톱 nearby.js 의 알림을 받고 → 6자리 페어링 → 입장권으로 승인 없이 참여 */
    @Test
    fun scanAndPairWithDesktopHost() {
        val udp = DatagramSocket(0).use { it.localPort } // 이 PC 의 진짜 Jam Browser(3032)와 겹치지 않게 빈 포트
        DesktopHarness("approval", extra = listOf("--beacon", udp.toString())).use { pc ->
            val lists = CopyOnWriteArrayList<List<Nearby.Beacon>>()
            val found = CountDownLatch(1)
            val scanner = Nearby.Scanner({ l -> lists += l; if (l.any { it.code == pc.code }) found.countDown() }, port = udp)
            val started = scanner.start()
            assertTrue("UDP $udp 열기 실패: ${scanner.lastError}", started)
            try {
                assertTrue("근처 Jam 알림을 받지 못함", found.await(8, TimeUnit.SECONDS))
            } finally {
                scanner.stop()
            }
            val beacon = lists.last().first { it.code == pc.code }
            assertEquals("PC호스트", beacon.name)
            assertEquals("approval", beacon.access)
            assertTrue(beacon.ip.startsWith("127.") || Nearby.isPrivateIp(beacon.ip))
            // 같은 PC 에서 테스트하므로 127.0.0.1 로 접속 (실제로는 알림을 보낸 PC 의 사설 IP)
            val target = beacon.copy(ip = "127.0.0.1")

            val sas = AtomicReference<String>()
            val result = AtomicReference<Invite>()
            val fail = AtomicReference<String>()
            var pending = false
            val done = CountDownLatch(1)
            NearbyPairing(JamConnection.defaultClient(), target, "근처폰", object : NearbyPairing.Listener {
                override fun onSas(s: String) { sas.set(s) }
                override fun onPending() { pending = true }
                override fun onDone(invite: Invite) { result.set(invite); done.countDown() }
                override fun onFailed(code: String, message: String) { fail.set("$code $message"); done.countDown() }
            }).start()
            // 호스트 화면에는 같은 6자리 + "근처 페어링" 표시가 간다 (desktop-host 가 자동 수락)
            val req = pc.hostMsg("join-request", 8000)
            assertEquals("근처폰", req.str("name"))
            assertTrue(req.bool("pair"))
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertNull(fail.get())
            assertTrue(pending)
            assertEquals(req.str("sas"), sas.get())
            assertTrue(Regex("^\\d{6}$").matches(sas.get()))
            val inv = result.get()!!
            assertEquals(pc.secret, inv.secret) // 호스트의 초대 비밀키를 안전하게 받음
            assertNotNull(inv.ticket)

            // 입장권으로 참여: 승인 요청 없이 바로 welcome
            val phone = PhoneGuest(inv, "근처폰")
            val w = phone.awaitWelcome()
            assertFalse("입장권이 있으면 다시 승인받지 않음", phone.pendingSeen)
            assertEquals(pc.code, w.obj("room")!!.str("code"))
            phone.close()

            // 같은 입장권은 한 번만: 다시 쓰면 평소처럼 승인 대기
            val again = PhoneGuest(inv.copy(), "다시폰")
            pc.hostMsg("join-request", 8000) { it.str("name") == "다시폰" }
            again.awaitWelcome()
            assertTrue(again.pendingSeen)
            again.close()
        }
    }

    @Test
    fun pairingRejectedByHost() = DesktopHarness("approval").use { pc ->
        val done = CountDownLatch(1)
        val fail = AtomicReference<String>()
        val beacon = Nearby.Beacon("testhost0001", "PC호스트", pc.port, pc.code, "approval", 1, "", "127.0.0.1")
        NearbyPairing(JamConnection.defaultClient(), beacon, "거절폰", object : NearbyPairing.Listener {
            override fun onSas(s: String) {}
            override fun onDone(invite: Invite) { done.countDown() }
            override fun onFailed(code: String, message: String) { fail.set(code); done.countDown() }
        }).start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals("rejected", fail.get())
    }
}
