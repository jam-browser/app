package io.github.jambrowser.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/** 같이 보는 파일: 묶음(JFP1) 풀기 · 해시 확인 · 데스크톱 서버에서 받기 */
class FilesTest {
    private val dir = File(System.getProperty("jam.repoDir") ?: ".", "core/src/test/node/files")

    private fun pack(entries: List<Pair<String, ByteArray>>, entry: String): ByteArray {
        var off = 0
        val list = entries.joinToString(",") { (p, b) -> """{"p":"$p","o":$off,"l":${b.size}}""".also { off += b.size } }
        val head = """{"v":1,"entry":"$entry","entries":[$list]}""".toByteArray()
        val len = byteArrayOf((head.size ushr 24).toByte(), (head.size ushr 16).toByte(), (head.size ushr 8).toByte(), head.size.toByte())
        return "JFP1".toByteArray() + len + head + entries.fold(ByteArray(0)) { a, (_, b) -> a + b }
    }

    @Test
    fun unpackRules() {
        val p = pack(listOf("a.pdf" to byteArrayOf(1, 2, 3), "b.txt" to "hi".toByteArray()), "a.pdf")
        val (entry, map) = JamFiles.unpack(p)
        assertEquals("a.pdf", entry)
        assertArrayEquals(byteArrayOf(1, 2, 3), map["a.pdf"])
        for (bad in listOf(
            "JFP2".toByteArray() + p.copyOfRange(4, p.size),
            pack(listOf("../x" to byteArrayOf(1)), "../x"),
            pack(listOf("/abs" to byteArrayOf(1)), "/abs"),
            pack(listOf("a.pdf" to byteArrayOf(1)), "없음.pdf"),
            p.copyOfRange(0, p.size - 2), // 내용이 잘림
        )) {
            try { JamFiles.unpack(bad); fail("이상한 묶음을 받아들임") } catch (_: IllegalArgumentException) {}
        }
    }

    /** 서버가 보낸 조각이 해시와 다르면 열지 않는다 */
    @Test
    fun shaMismatchIsRejected() {
        val sent = ArrayList<JsonObject>()
        val errors = ArrayList<String>()
        val ready = ArrayList<String>()
        val files = JamFiles({ sent += it; true }, object : JamFiles.Listener {
            override fun filesChanged() {}
            override fun fileReady(file: JamFiles.SharedFile, open: Boolean) { ready += file.fid }
            override fun fileError(file: JamFiles.SharedFile?, message: String) { errors += message }
        })
        val body = pack(listOf("x.txt" to ByteArray(40_000) { 7 }), "x.txt")
        val fid = "abcdefgh12345678"
        fun offer(sha: String) = files.handle(buildJsonObject {
            put("type", "file")
            putJsonObject("file") { put("fid", fid); put("name", "x.txt"); put("kind", "text"); put("size", body.size); put("sha", sha); put("entry", "x.txt"); put("count", 1); put("by", "1"); put("ready", true) }
        })
        fun deliver() {
            val n = (body.size + JamFiles.PIECE - 1) / JamFiles.PIECE
            for (i in 0 until n) files.handle(buildJsonObject {
                put("type", "file-down"); put("fid", fid); put("i", i); put("n", n)
                put("d", Base64.getEncoder().encodeToString(body.copyOfRange(i * JamFiles.PIECE, minOf(body.size, (i + 1) * JamFiles.PIECE))))
            })
        }
        offer("0".repeat(64))
        files.download(fid, open = true)
        assertEquals("file-get", sent.last().str("type"))
        deliver()
        assertTrue(errors.single().contains("해시"))
        assertNull(files.local(fid))
        assertTrue(ready.isEmpty())
        // 올바른 해시면 열림
        offer(JamFiles.sha256Hex(body))
        files.download(fid, open = true)
        deliver()
        assertEquals(listOf(fid), ready)
        assertEquals(40_000, files.local(fid)!!.bytes.size)
        // 호스트가 지우면 메모리에서도 지움
        files.handle(buildJsonObject { put("type", "file-removed"); put("fid", fid) })
        assertNull(files.local(fid))
        assertTrue(files.list.isEmpty())
    }

    @Test
    fun receivesSharedFilesFromDesktop() = DesktopHarness("open").use { pc ->
        val h = FakeHost()
        val c = h.onMain { JamController(h, { inv, name, resume, l -> JamConnection(JamConnection.defaultClient(), inv, name, resume, l).start() }) }
        h.controller = c
        h.onMain { c.join(Invite.parse(pc.invite)!!, "파일폰") }
        h.await("load https://example.com/start")

        val pdf = File(dir, "sample.pdf")
        val shared = pc.shareFile(pdf.path, "pdf")
        val fid = shared.str("fid")!!
        // 목록에 뜨고, 다 올라가면 ready
        val deadline = System.currentTimeMillis() + 5000
        while (h.onMain { c.files.get(fid)?.ready } != true) { assertTrue("공유 목록에 ready 로 떠야 함", System.currentTimeMillis() < deadline); Thread.sleep(50) }
        val f = h.onMain { c.files.get(fid)!! }
        assertEquals("sample.pdf", f.name)
        assertEquals("pdf", f.kind)
        assertEquals(shared.str("sha"), f.sha)

        h.onMain { c.files.download(fid, open = true) }
        h.await("fileReady sample.pdf true")
        assertArrayEquals(pdf.readBytes(), h.onMain { c.files.local(fid)!!.bytes })

        // 32KB 넘는 파일 (여러 조각)
        val text = "같이 보는 글 ".repeat(9000)
        val big = pc.shareFile(null, "text", name = "notes.txt", text = text)
        val bigFid = big.str("fid")!!
        while (h.onMain { c.files.get(bigFid)?.ready } != true) Thread.sleep(50)
        h.onMain { c.files.download(bigFid, open = false) }
        h.await("fileReady notes.txt false")
        h.await("toast 📎 받았어요: notes.txt")
        assertEquals(text, String(h.onMain { c.files.local(bigFid)!!.bytes }, Charsets.UTF_8))

        // 호스트가 Jam 탭에서 파일을 열면 (jamfile://) 폰은 그 주소로 따라가려 한다 → 화면 쪽이 받은 파일로 연다
        pc.send(buildJsonObject { put("type", "navigate"); put("url", "jamfile://$fid/sample.pdf"); put("title", "sample.pdf") })
        h.await("load jamfile://$fid/sample.pdf")
        assertEquals(f.fid, h.onMain { c.files.gate("jamfile://$fid/sample.pdf").first?.fid })
        assertNotNull(h.onMain { c.files.local(fid) })

        // 지우면 목록 · 메모리에서 사라짐
        pc.removeFile(fid)
        val d2 = System.currentTimeMillis() + 5000
        while (h.onMain { c.files.get(fid) } != null) { assertTrue(System.currentTimeMillis() < d2); Thread.sleep(50) }
        assertNull(h.onMain { c.files.local(fid) })
        // Jam 을 나가면 받은 파일도 버림
        h.onMain { c.leave() }
        assertNull(h.onMain { c.files.local(bigFid) })
    }
}
