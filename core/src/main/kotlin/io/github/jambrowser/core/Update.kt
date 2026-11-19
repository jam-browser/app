package io.github.jambrowser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 앱 자동 업데이트 (플레이 스토어 밖에서 설치한 앱용)
 *
 *  1. GitHub 의 최신 릴리스 정보(releases/latest)를 읽는다
 *  2. 지금 버전보다 새 버전이고 .apk 파일이 있으면 알린다
 *  3. 앱 전용 폴더에 내려받고, 같은 릴리스의 .sha256 파일(또는 릴리스 설명의 SHA-256 줄)과 해시를 맞춰 본다
 *  4. 설치는 안드로이드 PackageInstaller 가 사용자에게 직접 확인받는다 (다른 키로 서명된 APK 는 안드로이드가 거절)
 *
 * 이 파일은 안드로이드와 무관해서 PC 에서 테스트한다. 설치 · 화면은 app 쪽 AppUpdater.
 */
object Update {
    const val LATEST_URL = "https://api.github.com/repos/jam-browser/app/releases/latest"
    /** 이보다 큰 APK 는 받지 않는다 */
    const val MAX_APK_BYTES = 300L * 1024 * 1024
    private const val MAX_SHA_FILE = 64 * 1024

    data class Release(
        val version: String,
        val tag: String,
        val notes: String,
        val page: String?,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
        /** 같은 릴리스의 .sha256 파일 주소 (없으면 null → [bodySha] 를 쓴다) */
        val shaUrl: String?,
        /** 릴리스 설명에 적힌 SHA-256 (없으면 null) */
        val bodySha: String?,
    )

    // ---------------- 버전 ----------------
    /** "v0.3.1" · "0.3.1-debug" · "0.3" → [0, 3, 1] (숫자가 아니면 null) */
    fun parseVersion(v: String?): List<Int>? {
        val core = v?.trim()?.removePrefix("v")?.removePrefix("V")?.substringBefore('-')?.substringBefore('+') ?: return null
        if (core.isEmpty()) return null
        val parts = core.split('.')
        if (parts.size > 4) return null
        return parts.map { p -> p.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
    }

    /** a 가 b 보다 새 버전이면 양수 (모자란 자리는 0 으로 본다: 0.3 == 0.3.0) */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(remote: String?, local: String?): Boolean {
        val r = parseVersion(remote) ?: return false
        val l = parseVersion(local) ?: return true
        return compare(r, l) > 0
    }

    // ---------------- 릴리스 고르기 ----------------
    private val HEX64 = Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{64})(?![0-9a-fA-F])")

    /**
     * GitHub releases/latest 응답(JSON 글자) → 지금 버전보다 새 릴리스면 Release, 아니면 null
     * 초안(draft) · 미리보기(prerelease)는 건너뛴다. APK 가 여러 개면 debug 가 아닌 것을 고른다.
     */
    fun pickRelease(json: String, currentVersion: String, allowHttp: Boolean = false): Release? {
        val okUrl = { u: String? -> isHttpsUrl(u) || (allowHttp && u != null && u.length < 2048 && u.startsWith("http://")) }
        val o = try { Json.parseToJsonElement(json) as? JsonObject } catch (e: Exception) { null } ?: return null
        if (o.bool("draft") || o.bool("prerelease")) return null
        val tag = o.str("tag_name") ?: return null
        val version = tag.trim().removePrefix("v").removePrefix("V")
        if (!isNewer(version, currentVersion)) return null
        val assets = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val apks = assets.filter { a -> a.str("name")?.endsWith(".apk", ignoreCase = true) == true && okUrl(a.str("browser_download_url")) }
        val apk = apks.firstOrNull { !it.str("name")!!.contains("debug", ignoreCase = true) && !it.str("name")!!.contains("unsigned", ignoreCase = true) }
            ?: return null
        val apkName = apk.str("name")!!
        val shaAsset = assets.firstOrNull { it.str("name").equals("$apkName.sha256", ignoreCase = true) }
            ?: assets.firstOrNull { a -> a.str("name")?.endsWith(".sha256", ignoreCase = true) == true }?.takeIf { _ -> apks.size == 1 }
        val body = o.str("body").orEmpty()
        return Release(
            version = version,
            tag = tag,
            notes = body.take(4000),
            page = o.str("html_url")?.takeIf(okUrl),
            apkName = apkName,
            apkUrl = apk.str("browser_download_url")!!,
            apkSize = apk.num("size")?.toLong() ?: -1L,
            shaUrl = shaAsset?.str("browser_download_url")?.takeIf(okUrl),
            bodySha = shaFromBody(body, apkName),
        )
    }

    /** 릴리스 설명에서 SHA-256 찾기: "SHA-256: <hex>" 줄, 또는 "<hex>  파일이름.apk" 줄 */
    fun shaFromBody(body: String, apkName: String): String? {
        val lines = body.lines()
        lines.firstOrNull { it.contains(apkName, ignoreCase = true) }?.let { l -> HEX64.find(l)?.let { return it.groupValues[1].lowercase() } }
        lines.firstOrNull { Regex("sha-?256", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.let { l ->
            HEX64.find(l)?.let { return it.groupValues[1].lowercase() }
        }
        return null
    }

    /** .sha256 파일 내용 (sha256sum 형식 "<hex>  이름" 여러 줄, 또는 hex 하나) → 이 APK 의 해시 */
    fun shaFromFile(text: String, apkName: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        lines.firstOrNull { it.contains(apkName) }?.let { l -> HEX64.find(l)?.let { return it.groupValues[1].lowercase() } }
        if (lines.size == 1) HEX64.find(lines[0])?.let { return it.groupValues[1].lowercase() }
        return null
    }

    /** 최대 [max] 바이트까지 읽는다 (넘으면 null) */
    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
    }

    fun isHttpsUrl(u: String?): Boolean = u != null && u.length < 2048 && u.startsWith("https://")

    fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 해시 비교 (대소문자 무시, 길이가 다르면 false) */
    fun sameSha(a: String?, b: String?): Boolean =
        a != null && b != null && a.length == 64 && b.length == 64 && MessageDigest.isEqual(a.lowercase().toByteArray(), b.lowercase().toByteArray())

    // ---------------- 내려받기 ----------------
    class UpdateException(message: String) : IOException(message)

    /**
     * 릴리스 정보 받기. 새 버전이 없으면 null.
     * [allowHttp] 는 디버그 빌드에서 PC 의 테스트 서버를 쓸 때만 true.
     */
    fun check(http: OkHttpClient, currentVersion: String, url: String = LATEST_URL, allowHttp: Boolean = false): Release? {
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "jam-browser-app")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null // 아직 릴리스가 없음
            if (!r.isSuccessful) throw UpdateException("업데이트 정보를 받지 못했어요 (${r.code})")
            val text = r.body?.string() ?: return null
            return pickRelease(text, currentVersion, allowHttp)
        }
    }

    /**
     * APK 를 [dest] 에 내려받고 SHA-256 을 확인한다. 맞지 않거나 실패하면 파일을 지우고 UpdateException.
     * [progress] (받은 바이트, 전체 바이트 또는 -1)
     */
    fun download(
        http: OkHttpClient,
        rel: Release,
        dest: File,
        allowHttp: Boolean = false,
        cancelled: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val okScheme = { u: String? -> isHttpsUrl(u) || (allowHttp && u?.startsWith("http://") == true) }
        if (!okScheme(rel.apkUrl)) throw UpdateException("안전하지 않은 주소라 받지 않았어요")
        // 1) 기대하는 해시: .sha256 파일 → 없으면 릴리스 설명
        val expected = rel.shaUrl?.let { su ->
            if (!okScheme(su)) throw UpdateException("안전하지 않은 주소라 받지 않았어요")
            http.newCall(Request.Builder().url(su).header("User-Agent", "jam-browser-app").build()).execute().use { r ->
                if (!r.isSuccessful) throw UpdateException("해시 파일을 받지 못했어요 (${r.code})")
                val body = r.body ?: throw UpdateException("해시 파일이 비어 있어요")
                if (body.contentLength() > MAX_SHA_FILE) throw UpdateException("해시 파일이 이상해요")
                val bytes = body.byteStream().use { readLimited(it, MAX_SHA_FILE) } ?: throw UpdateException("해시 파일이 이상해요")
                shaFromFile(String(bytes, Charsets.UTF_8), rel.apkName)
            }
        } ?: rel.bodySha ?: throw UpdateException("이 릴리스에는 SHA-256 해시가 없어서 설치하지 않아요")

        // 2) APK
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        tmp.delete()
        try {
            val md = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(rel.apkUrl).header("User-Agent", "jam-browser-app").build()).execute().use { r ->
                if (!r.isSuccessful) throw UpdateException("업데이트 파일을 받지 못했어요 (${r.code})")
                val body = r.body ?: throw UpdateException("업데이트 파일이 비어 있어요")
                val total = body.contentLength()
                if (total > MAX_APK_BYTES) throw UpdateException("업데이트 파일이 너무 커요")
                var got = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) throw UpdateException("취소했어요")
                            val n = input.read(buf)
                            if (n < 0) break
                            got += n
                            if (got > MAX_APK_BYTES) throw UpdateException("업데이트 파일이 너무 커요")
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                            progress(got, total)
                        }
                    }
                }
                if (total >= 0 && got != total) throw UpdateException("업데이트 파일을 끝까지 받지 못했어요")
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (!sameSha(actual, expected)) throw UpdateException("받은 파일의 SHA-256 이 릴리스에 적힌 값과 달라요. 설치하지 않았어요")
            dest.delete()
            if (!tmp.renameTo(dest)) throw UpdateException("파일을 저장하지 못했어요")
            return dest
        } catch (e: IOException) {
            tmp.delete()
            if (e is UpdateException) throw e
            throw UpdateException("업데이트 파일을 받다가 끊겼어요")
        } catch (e: RuntimeException) {
            tmp.delete()
            throw UpdateException("업데이트 파일을 받지 못했어요")
        }
    }
}
