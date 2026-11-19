package io.github.jambrowser.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** 자동 업데이트: 버전 비교 · 릴리스(APK · 해시) 고르기 · 내려받기와 SHA-256 확인 (PC 의 작은 HTTP 서버로) */
class UpdateTest {
    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun versionCompare() {
        assertEquals(listOf(0, 3, 1), Update.parseVersion("v0.3.1"))
        assertEquals(listOf(0, 2, 0), Update.parseVersion("0.2.0-debug"))
        assertNull(Update.parseVersion("latest"))
        assertNull(Update.parseVersion(""))
        assertTrue(Update.isNewer("0.3.0", "0.2.9"))
        assertTrue(Update.isNewer("v0.10.0", "0.9.0")) // 글자 비교가 아니라 숫자 비교
        assertTrue(Update.isNewer("1.0", "0.99.99"))
        assertFalse(Update.isNewer("0.3", "0.3.0"))
        assertFalse(Update.isNewer("0.2.0", "0.2.0-debug"))
        assertFalse(Update.isNewer("0.1.9", "0.2.0"))
        assertFalse(Update.isNewer("nightly", "0.2.0")) // 숫자 버전이 아니면 업데이트로 보지 않음
    }

    private fun releaseJson(
        tag: String = "v0.4.0",
        assets: List<Pair<String, String>>,
        body: String = "",
        draft: Boolean = false,
        pre: Boolean = false,
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"html_url":"https://github.com/jam-browser/app/releases/tag/$tag",
        "body":${kotlinx.serialization.json.JsonPrimitive(body)},
        "assets":[${assets.joinToString(",") { (n, u) -> """{"name":"$n","size":1234,"browser_download_url":"$u"}""" }}]}"""

    @Test
    fun pickAssetAndHash() {
        val base = "https://github.com/jam-browser/app/releases/download/v0.4.0"
        val json = releaseJson(
            assets = listOf(
                "jam-browser-0.4.0-debug.apk" to "$base/jam-browser-0.4.0-debug.apk",
                "jam-browser-0.4.0.apk" to "$base/jam-browser-0.4.0.apk",
                "jam-browser-0.4.0.apk.sha256" to "$base/jam-browser-0.4.0.apk.sha256",
            ),
        )
        val r = Update.pickRelease(json, "0.3.0")!!
        assertEquals("0.4.0", r.version)
        assertEquals("jam-browser-0.4.0.apk", r.apkName) // debug APK 는 고르지 않음
        assertEquals("$base/jam-browser-0.4.0.apk.sha256", r.shaUrl)
        assertNull(Update.pickRelease(json, "0.4.0")) // 같은 버전
        assertNull(Update.pickRelease(json, "0.5.0"))
        assertNull(Update.pickRelease(releaseJson(assets = listOf("a.apk" to "$base/a.apk"), draft = true), "0.1.0"))
        assertNull(Update.pickRelease(releaseJson(assets = listOf("a.apk" to "$base/a.apk"), pre = true), "0.1.0"))
        assertNull(Update.pickRelease(releaseJson(assets = listOf("a.zip" to "$base/a.zip")), "0.1.0")) // APK 없음
        assertNull(Update.pickRelease(releaseJson(assets = listOf("a.apk" to "http://evil.example/a.apk")), "0.1.0")) // https 만
        assertNull(Update.pickRelease("not json", "0.1.0"))

        // 해시가 릴리스 설명에만 있는 경우
        val sha = "ab".repeat(32)
        val r2 = Update.pickRelease(releaseJson(assets = listOf("app.apk" to "$base/app.apk"), body = "고친 것\n\nSHA-256: ${sha.uppercase()}\n"), "0.1.0")!!
        assertNull(r2.shaUrl)
        assertEquals(sha, r2.bodySha)
        assertEquals(sha, Update.shaFromBody("$sha  app.apk\n", "app.apk"))
        assertNull(Update.shaFromBody("해시 없음 ${"a".repeat(65)}", "app.apk")) // 65자리는 해시가 아님

        // sha256sum 형식 파일
        assertEquals(sha, Update.shaFromFile("$sha *app.apk\n", "app.apk"))
        assertEquals(sha, Update.shaFromFile("${"cd".repeat(32)}  other.apk\n$sha  app.apk\n", "app.apk"))
        assertEquals(sha, Update.shaFromFile(sha, "app.apk"))
        assertNull(Update.shaFromFile("${"cd".repeat(32)}  other.apk\n${"ef".repeat(32)}  x.apk", "app.apk"))
    }

    /** 내려받기 + 해시 확인: 맞으면 파일이 남고, 다르면 지워지고 오류 */
    @Test
    fun downloadVerifiesSha256AgainstLocalServer() {
        val apk = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val good = hex(apk)
        val hits = AtomicInteger()
        var shaText = "$good  jam-browser-9.9.9.apk\n"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            val path = ex.requestURI.path
            val body: ByteArray? = when (path) {
                "/latest" -> releaseJson(
                    tag = "v9.9.9",
                    assets = listOf(
                        "jam-browser-9.9.9.apk" to "http://127.0.0.1:${server.address.port}/jam-browser-9.9.9.apk",
                        "jam-browser-9.9.9.apk.sha256" to "http://127.0.0.1:${server.address.port}/jam-browser-9.9.9.apk.sha256",
                    ),
                ).toByteArray()
                "/jam-browser-9.9.9.apk" -> apk
                "/jam-browser-9.9.9.apk.sha256" -> shaText.toByteArray()
                else -> null
            }
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        val dir = Files.createTempDirectory("jam-update").toFile()
        try {
            val http = JamConnection.defaultClient()
            val url = "http://127.0.0.1:${server.address.port}/latest"
            // 실제 앱처럼 https 만 허용하면 이 테스트 서버(http)의 릴리스는 고르지 않는다
            assertNull(Update.check(http, "0.2.0", url))
            val rel = Update.check(http, "0.2.0", url, allowHttp = true)
            assertNotNull(rel)
            assertNull(Update.check(http, "9.9.9", url, allowHttp = true))

            var last = 0L
            val out = Update.download(http, rel!!, File(dir, "update.apk"), allowHttp = true) { got, _ -> last = got }
            assertEquals(apk.size.toLong(), last)
            assertEquals(good, Update.sha256Hex(out))
            assertTrue(apk.contentEquals(out.readBytes()))

            // 해시가 다르면 설치 파일을 남기지 않는다
            shaText = "${"0".repeat(64)}  jam-browser-9.9.9.apk\n"
            out.delete()
            try {
                Update.download(http, rel, File(dir, "update.apk"), allowHttp = true)
                fail("해시가 다른데 통과함")
            } catch (e: Update.UpdateException) {
                assertTrue(e.message!!.contains("SHA-256"))
            }
            assertFalse(File(dir, "update.apk").exists())
            assertFalse(File(dir, "update.apk.part").exists())

            // https 가 아니면 (allowHttp 없이) 아예 받지 않는다
            try {
                Update.download(http, rel, File(dir, "update.apk"))
                fail("http 주소인데 받음")
            } catch (e: Update.UpdateException) {
                assertTrue(e.message!!.contains("안전하지 않은"))
            }

            // 해시가 어디에도 없으면 설치하지 않는다
            try {
                Update.download(http, rel.copy(shaUrl = null, bodySha = null), File(dir, "update.apk"), allowHttp = true)
                fail("해시 없이 받음")
            } catch (e: Update.UpdateException) {
                assertTrue(e.message!!.contains("해시가 없어서"))
            }
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}
