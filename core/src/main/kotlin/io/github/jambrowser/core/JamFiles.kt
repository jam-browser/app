package io.github.jambrowser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64

/**
 * 같이 보는 파일 — 참여자 쪽 (데스크톱 src/renderer/files.js · src/jamfiles.js 와 같은 규칙)
 *
 *  - 호스트가 공유하면 목록(file)에 뜬다. "받아서 열기" → file-get → 서버가 32KB 조각(file-down)으로 내려보냄
 *  - 다 받으면 크기 · SHA-256 을 확인하고 묶음(pack)을 푼다:  'JFP1' + 머리 길이(4바이트, 빅엔디언) + 머리 JSON + 내용들
 *  - 받은 파일은 메모리에만 둔다 (다운로드 폴더 X). Jam 을 나가거나 호스트가 지우면 버린다
 *  - Jam 이 jamfile://<파일ID>/ 로 가면 받은 파일을 연다 (안 받았으면 받을지 묻는다)
 */
class JamFiles(private val send: (JsonObject) -> Boolean, private val listener: Listener) {
    interface Listener {
        fun filesChanged()
        /** 다 받고 확인까지 끝남 (open = 받은 뒤 바로 열기) */
        fun fileReady(file: SharedFile, open: Boolean)
        fun fileError(file: SharedFile?, message: String)
    }

    data class SharedFile(
        val fid: String,
        val name: String,
        val kind: String,
        val size: Long,
        val sha: String,
        val entry: String,
        val count: Int,
        val by: String,
        val ready: Boolean,
        val net: Boolean,
    ) {
        val icon: String get() = KIND_ICON[kind] ?: "📎"
        /** 폰에서 열 수 있는 종류 */
        val viewable: Boolean get() = kind == "pdf" || kind == "image" || kind == "text"
    }

    /** 받은 파일: 묶음 안의 시작 파일 이름 · 내용 */
    class Local(val entryName: String, val bytes: ByteArray)

    private class Download(val n: Int, var open: Boolean) {
        val parts = arrayOfNulls<ByteArray>(n)
        var got = 0
    }

    private val files = LinkedHashMap<String, SharedFile>()
    private val local = HashMap<String, Local>()
    private val downloads = HashMap<String, Download>()
    private var askedFor: String? = null

    val list: List<SharedFile> get() = files.values.toList()
    fun get(fid: String?) = files[fid]
    fun local(fid: String?): Local? = local[fid]
    /** 받는 중이면 0~1 */
    fun progress(fid: String): Double? = downloads[fid]?.let { it.got.toDouble() / it.n }

    fun onWelcome(list: JsonArray?) {
        files.clear()
        list?.forEach { (it as? JsonObject)?.let(::parse)?.let { f -> files[f.fid] = f } }
        local.keys.retainAll(files.keys)
        listener.filesChanged()
    }

    fun reset() {
        files.clear(); local.clear(); downloads.clear(); askedFor = null
        listener.filesChanged()
    }

    /** 서버 메시지 (file · file-removed · file-down) */
    fun handle(msg: JsonObject) {
        when (msg.str("type")) {
            "file" -> {
                val f = msg.obj("file")?.let(::parse) ?: return
                files[f.fid] = f
                listener.filesChanged()
            }
            "file-removed" -> {
                val fid = msg.str("fid") ?: return
                files.remove(fid); local.remove(fid); downloads.remove(fid)
                listener.filesChanged()
            }
            "file-down" -> onDown(msg)
        }
    }

    /** 받기 시작 (이미 받았으면 바로 fileReady) */
    fun download(fid: String, open: Boolean) {
        val f = files[fid] ?: return
        if (!f.ready) return
        if (local[fid] != null) return listener.fileReady(f, open)
        downloads[fid]?.let { it.open = it.open || open; return }
        if (f.size > MAX_BYTES) return listener.fileError(f, "폰에서는 ${MAX_BYTES / 1024 / 1024}MB 넘는 파일을 받을 수 없어요")
        downloads[fid] = Download(((f.size + PIECE - 1) / PIECE).toInt(), open)
        send(buildJsonObject { put("type", "file-get"); put("fid", fid) })
        listener.filesChanged()
    }

    private fun onDown(msg: JsonObject) {
        val fid = msg.str("fid") ?: return
        val f = files[fid] ?: return
        val d = downloads[fid] ?: return
        val i = msg.num("i")?.toInt() ?: return
        if (msg.num("n")?.toInt() != d.n || i < 0 || i >= d.n) return
        if (d.parts[i] == null) {
            d.parts[i] = try { Base64.getDecoder().decode(msg.str("d") ?: return) } catch (e: IllegalArgumentException) { return }
            d.got++
            if (d.got % 16 == 0 || d.got == d.n) listener.filesChanged()
        }
        if (d.got < d.n) return
        downloads.remove(fid)
        val total = d.parts.sumOf { it!!.size.toLong() }
        if (total != f.size) return listener.fileError(f, "받은 파일 크기가 맞지 않아요")
        val all = ByteArray(total.toInt())
        var o = 0
        for (p in d.parts) { System.arraycopy(p!!, 0, all, o, p.size); o += p.size }
        if (!sha256Hex(all).equals(f.sha, ignoreCase = true)) {
            listener.filesChanged()
            return listener.fileError(f, "받은 파일이 손상됐어요 (해시 불일치). 다시 받아 주세요")
        }
        val un = try { unpack(all) } catch (e: IllegalArgumentException) {
            listener.filesChanged()
            return listener.fileError(f, "파일 묶음 형식이 올바르지 않아요")
        }
        val entry = un.second[un.first] ?: return listener.fileError(f, "파일 묶음에 시작 파일이 없어요")
        local[fid] = Local(un.first, entry)
        listener.filesChanged()
        listener.fileReady(f, d.open)
    }

    /**
     * Jam 이 이 주소(jamfile://…)로 가려 할 때: 받은 파일이면 그 파일, 아니면 null (처음 한 번은 받을지 묻도록 askFirst = true)
     */
    fun gate(url: String?): Pair<SharedFile?, Boolean> {
        val fid = fidOf(url) ?: return null to false
        val f = files[fid]
        if (f != null && local[fid] != null) return f to false
        val ask = askedFor != fid
        askedFor = fid
        return f to ask
    }

    private fun parse(o: JsonObject): SharedFile? {
        val fid = o.str("fid")?.takeIf { FID_RE.matches(it) } ?: return null
        return SharedFile(
            fid = fid,
            name = o.str("name").orEmpty().ifEmpty { "file" }.take(120),
            kind = o.str("kind") ?: "",
            size = o.num("size")?.toLong() ?: 0,
            sha = o.str("sha")?.lowercase()?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) } ?: return null,
            entry = o.str("entry").orEmpty(),
            count = o.num("count")?.toInt() ?: 1,
            by = o.str("by").orEmpty(),
            ready = o.bool("ready"),
            net = o.bool("net"),
        )
    }

    companion object {
        const val PIECE = 32 * 1024
        /** 폰 메모리에 올려 둘 수 있는 크기 */
        const val MAX_BYTES = 200L * 1024 * 1024
        val FID_RE = Regex("^[a-z0-9]{16}$")
        val KIND_ICON = mapOf("pdf" to "📄", "html" to "🌐", "image" to "🖼", "text" to "📝", "swf" to "🎮")

        fun fidOf(url: String?): String? = url?.let { Regex("^jamfile://([a-z0-9]{16})(/|$)").find(it)?.groupValues?.get(1) }

        fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        private fun validPath(p: String) = p.isNotEmpty() && p.length <= 300 && !p.startsWith("/") && !p.contains('\\') &&
            p.split('/').all { s -> s.isNotEmpty() && s != "." && s != ".." && s.none { it.code < 0x20 } }

        /** 묶음 풀기 → (시작 파일 이름, 이름 → 내용). 형식이 이상하면 IllegalArgumentException */
        fun unpack(buf: ByteArray): Pair<String, Map<String, ByteArray>> {
            require(buf.size >= 8 && String(buf, 0, 4, Charsets.ISO_8859_1) == "JFP1") { "bad pack" }
            val hl = ((buf[4].toLong() and 0xff) shl 24) or ((buf[5].toLong() and 0xff) shl 16) or ((buf[6].toLong() and 0xff) shl 8) or (buf[7].toLong() and 0xff)
            require(hl <= 512 * 1024 && 8 + hl <= buf.size) { "bad head" }
            val head = try { Json.parseToJsonElement(String(buf, 8, hl.toInt(), Charsets.UTF_8)) as? JsonObject } catch (e: Exception) { null }
            require(head != null && head.num("v") == 1.0) { "bad head" }
            val entries = head["entries"] as? JsonArray
            require(entries != null && entries.isNotEmpty() && entries.size <= 5000) { "bad head" }
            val base = 8 + hl
            val out = LinkedHashMap<String, ByteArray>()
            for (e in entries) {
                val o = e as? JsonObject ?: throw IllegalArgumentException("bad entry")
                val p = o.str("p") ?: throw IllegalArgumentException("bad entry")
                val off = o.num("o") ?: -1.0
                val len = o.num("l") ?: -1.0
                require(validPath(p) && off >= 0 && len >= 0 && off == Math.floor(off) && len == Math.floor(len) && base + off + len <= buf.size) { "bad entry" }
                val s = (base + off.toLong()).toInt()
                out[p] = buf.copyOfRange(s, s + len.toInt())
            }
            val entry = head.str("entry")
            require(entry != null && out.containsKey(entry)) { "no entry" }
            return entry to out
        }
    }
}
