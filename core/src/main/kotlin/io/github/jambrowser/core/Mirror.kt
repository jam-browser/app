package io.github.jambrowser.core

import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * 화면 미러링 보기 (데스크톱 src/renderer/mirror.js 의 참여자 쪽과 같은 규칙)
 *
 *  호스트가 탭 화면을 WebCodecs 로 인코딩(H.264 annexb "avc1.64xxxx" 또는 "vp8") → 32KB 조각(mv) 으로 보낸다
 *    mv = { sid, f: 프레임 번호, k: 키프레임, i: 조각 번호, n: 조각 수, ts: 찍은 시각(µs), d: base64,
 *           (키프레임의 0번 조각만) w, h, c: 영상 형식 }
 *  여기서는 조각을 모아 프레임을 만들고, 중간이 빠지면 다음 키프레임까지 기다리며 키프레임을 요청(mkey)한다.
 *  지연(ms)은 "도착 시각 - 찍은 시각" 이 최근 15초 중 가장 짧았던 때보다 얼마나 늘었나로 잰다 (두 기기 시계 차이는 빠짐).
 */
class MirrorAssembler(private val clockMs: () -> Double = { System.nanoTime() / 1e6 }) {
    data class Info(val sid: String, val by: String, val title: String, val w: Int, val h: Int, val paused: String?, val secret: Boolean) {
        companion object {
            fun from(o: JsonObject?): Info? {
                o ?: return null
                val sid = o.str("sid") ?: return null
                return Info(sid, o.str("by") ?: "", o.str("title").orEmpty(), (o.num("w") ?: 0.0).toInt(), (o.num("h") ?: 0.0).toInt(), o.str("paused"), o.bool("secret"))
            }
        }
    }

    class Frame(val f: Long, val key: Boolean, val ts: Long, val w: Int, val h: Int, val codec: String, val data: ByteArray) {
        /** 안드로이드 MediaCodec 형식 이름 */
        val mime: String get() = if (codec.startsWith("avc1")) "video/avc" else "video/x-vnd.on2.vp8"
    }

    sealed class Result {
        /** 디코더에 넣을 프레임 */
        class Decode(val frame: Frame) : Result()
        /** 중간이 빠져서 키프레임을 기다림 → mkey 요청 */
        object NeedKey : Result()
    }

    private class Part(val f: Long, val n: Int, val key: Boolean, val ts: Long) {
        var w = 0; var h = 0; var c = "vp8"
        val pieces = arrayOfNulls<ByteArray>(n)
        var got = 0
    }

    var sid: String? = null; private set
    private val parts = HashMap<Long, Part>()
    private var lastF = -1L
    var waitingKey = true; private set
    /** 지금 디코더 설정 (키프레임이 와서 바뀌면 디코더를 새로 만든다) */
    var w = 0; private set
    var h = 0; private set
    var codec = ""; private set
    private val offs = ArrayDeque<Pair<Double, Double>>()
    /** 지난 피드백 뒤로 가장 큰 지연 (ms) */
    var late = 0.0
    var frames = 0L

    fun open(newSid: String) {
        sid = newSid
        parts.clear()
        lastF = -1
        waitingKey = true
        w = 0; h = 0; codec = ""
        offs.clear()
        late = 0.0
        frames = 0
    }

    fun close() {
        sid = null
        parts.clear()
    }

    /** 디코더를 새로 만들었거나 문제가 생김 → 다음 키프레임부터 */
    fun resetToKey() {
        waitingKey = true
        codec = ""
    }

    /** mv 메시지 하나 → 프레임이 완성되면 Decode, 빠진 게 있으면 NeedKey, 아직이면 null */
    fun onChunk(msg: JsonObject): Result? {
        if (sid == null || msg.str("sid") != sid) return null
        val f = msg.num("f")?.toLong() ?: return null
        val n = msg.num("n")?.toInt() ?: return null
        val i = msg.num("i")?.toInt() ?: return null
        if (n < 1 || n > 256 || i < 0 || i >= n || f < 0) return null
        val d = msg.str("d") ?: return null
        var p = parts[f]
        if (p == null) {
            if (f <= lastF) return null // 이미 지나간 프레임
            p = Part(f, n, msg.bool("k"), msg.num("ts")?.toLong() ?: 0L)
            parts[f] = p
        }
        if (p.n != n) return null
        if (msg.bool("k") && i == 0) {
            p.w = (msg.num("w") ?: 0.0).toInt(); p.h = (msg.num("h") ?: 0.0).toInt(); p.c = msg.str("c") ?: "vp8"
        }
        if (p.pieces[i] == null) {
            p.pieces[i] = try { Base64.getDecoder().decode(d) } catch (e: IllegalArgumentException) { return null }
            p.got++
        }
        if (p.got < p.n) return null
        parts.remove(f)
        parts.keys.removeAll { it < f } // 덜 온 옛 프레임은 버림
        // 지연 재기
        val now = clockMs()
        val off = now * 1000 - p.ts
        offs.addLast(now to off)
        while (offs.isNotEmpty() && now - offs.first().first > 15_000) offs.removeFirst()
        val min = offs.minOf { it.second }
        late = maxOf(late, (off - min) / 1000)

        if (p.key) {
            if (p.w < 16 || p.h < 16 || !(p.c == "vp8" || Regex("^avc1\\.[0-9A-Fa-f]{6}$").matches(p.c))) return Result.NeedKey
            w = p.w; h = p.h; codec = p.c
            waitingKey = false
        } else if (waitingKey || codec.isEmpty() || f != lastF + 1) {
            waitingKey = true // 중간이 빠졌으면 다음 키프레임까지
            return Result.NeedKey
        }
        lastF = f
        val total = p.pieces.sumOf { it!!.size }
        val data = ByteArray(total)
        var o = 0
        for (x in p.pieces) { System.arraycopy(x!!, 0, data, o, x.size); o += x.size }
        return Result.Decode(Frame(f, p.key, p.ts, w, h, codec, data))
    }

    companion object {
        /**
         * H.264 annexb 키프레임에서 SPS · PPS 를 찾는다 → MediaCodec 의 csd-0 / csd-1 (시작 코드 포함)
         * 없으면 null (디코더가 프레임 안의 SPS · PPS 를 읽게 둠)
         */
        fun avcConfig(au: ByteArray): Pair<ByteArray, ByteArray>? {
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            for ((s, e) in nalUnits(au)) {
                when (au[s].toInt() and 0x1f) {
                    7 -> if (sps == null) sps = START + au.copyOfRange(s, e)
                    8 -> if (pps == null) pps = START + au.copyOfRange(s, e)
                }
            }
            return if (sps != null && pps != null) sps to pps else null
        }

        private val START = byteArrayOf(0, 0, 0, 1)

        /** annexb 의 NAL 단위 (시작 코드 뒤 ~ 다음 시작 코드 앞) */
        fun nalUnits(b: ByteArray): List<Pair<Int, Int>> {
            val starts = ArrayList<Pair<Int, Int>>() // (시작 코드 위치, NAL 시작)
            var i = 0
            while (i + 3 <= b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                    if (b[i + 2].toInt() == 1) { starts += i to i + 3; i += 3; continue }
                    if (i + 4 <= b.size && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1) { starts += i to i + 4; i += 4; continue }
                }
                i++
            }
            return starts.mapIndexedNotNull { k, (_, s) ->
                var e = if (k + 1 < starts.size) starts[k + 1].first else b.size
                while (e > s && b[e - 1].toInt() == 0) e-- // 다음 시작 코드 앞의 0 (trailing zero)
                if (e > s) s to e else null
            }
        }
    }
}
