package io.github.jambrowser.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jambrowser.core.JamController
import io.github.jambrowser.core.MirrorAssembler
import java.nio.ByteBuffer

/**
 * 화면 미러링 보기 (데스크톱 mirror.js 의 참여자 화면)
 *  - 조각 모으기 · 키프레임 요청 · 지연 피드백은 core 의 JamController / MirrorAssembler 가 하고, 여기서는 MediaCodec 으로 풀어 그린다
 *  - H.264(annexb, avc1.*) · VP8 → SurfaceView (영상 비율 유지, 소리 없음)
 *  - 영상을 길게 누르면 핑 휠 → 미러링하는 사람의 탭 위에 핑이 그려져 영상으로 모두에게 보인다
 *  - "숨기기" 를 누르면 영상을 받지 않은 것으로 치고(피드백 안 보냄), 다시 열면 키프레임부터
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class MirrorView(context: Context, private val jam: JamController, private val onHide: () -> Unit) : FrameLayout(context), SurfaceHolder.Callback {
    private val d = resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val videoBox = FrameLayout(context)
    private val surfaceView = SurfaceView(context)
    private val wheel = PingWheel(context)
    private val title = TextView(context)
    private val stats = TextView(context)
    private val notice = TextView(context)
    private var surface: Surface? = null
    private var decoder: Decoder? = null
    private var videoW = 16
    private var videoH = 9
    private var rendered = 0
    private var fpsCount = 0
    private var fpsAt = System.currentTimeMillis()
    private var waiting = true
    private var paused: String? = null

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true // 뒤의 웹페이지로 터치가 가지 않게
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
            setBackgroundColor(Color.argb(200, 20, 20, 22))
        }
        title.apply { setTextColor(Color.WHITE); textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        stats.apply { setTextColor(Color.argb(170, 255, 255, 255)); textSize = 11f; setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0) }
        val hide = TextView(context).apply {
            text = "숨기기 ✕"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            isSoundEffectsEnabled = false
            setOnClickListener { onHide() }
        }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(stats)
        top.addView(hide)
        notice.apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
        }
        videoBox.addView(surfaceView, LayoutParams(1, 1, Gravity.CENTER))
        addView(videoBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(notice, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        addView(wheel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        surfaceView.holder.addCallback(this)
        surfaceView.isSoundEffectsEnabled = false
        surfaceView.isClickable = true // 누른 뒤의 움직임 · 뗌도 받으려면 (아니면 첫 DOWN 만 옴)
        surfaceView.setOnTouchListener(PingGesture(surfaceView, wheel, { jam.pingEnabled && !waiting }) { fx, fy, k ->
            if (jam.sendMirrorPing(fx, fy, k)) toastIn("${JamController.PING_ICON[k]} 핑을 보냈어요 — 호스트 화면에 그려져요")
        })
        videoBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        visibility = GONE
    }

    // ---------------- 컨트롤러 → 화면 ----------------
    fun open(info: MirrorAssembler.Info) {
        val who = jam.member(info.by)?.name ?: "호스트"
        title.text = "🖥 ${who}님의 화면" + if (info.title.isNotEmpty()) " · ${info.title}" else ""
        rendered = 0
        waiting = true
        paused = info.paused
        if (info.w >= 16 && info.h >= 16) { videoW = info.w; videoH = info.h }
        releaseDecoder()
        renderNotice()
        fitVideo()
    }

    fun close() {
        releaseDecoder()
        visibility = GONE
    }

    fun setPaused(p: String?) {
        paused = p
        renderNotice()
    }

    fun frame(f: MirrorAssembler.Frame) {
        val s = surface ?: return // 숨겨져 있음 → 다시 보이면 키프레임부터
        var dec = decoder
        if (f.key && (dec == null || dec.mime != f.mime || dec.w != f.w || dec.h != f.h)) {
            releaseDecoder()
            videoW = f.w; videoH = f.h
            fitVideo()
            dec = try {
                Decoder(s, f, ::onDecoderEvent)
            } catch (e: Exception) {
                jam.mirrorDecodeFailed(unsupported = true)
                notice.text = "이 폰에서는 이 영상 형식(${if (f.mime == "video/avc") "H.264" else "VP8"})을 풀 수 없어요. 호스트에게 알렸어요"
                notice.visibility = VISIBLE
                null
            }
            decoder = dec
        }
        if (dec == null) return
        dec.submit(f)
    }

    private fun onDecoderEvent(e: String) = main.post {
        when (e) {
            "rendered" -> {
                jam.mirrorDecoded()
                rendered++
                fpsCount++
                if (waiting) { waiting = false; renderNotice() }
                val now = System.currentTimeMillis()
                if (now - fpsAt >= 1000) {
                    stats.text = "${videoW}×${videoH} · ${fpsCount * 1000 / (now - fpsAt)}fps"
                    fpsCount = 0
                    fpsAt = now
                }
            }
            "behind" -> jam.mirrorBehind()
            "error" -> { releaseDecoder(); jam.mirrorDecodeFailed(unsupported = false) }
        }
    }

    private fun renderNotice() {
        val text = when {
            paused != null -> PAUSE_TEXT[paused] ?: "호스트가 화면을 잠시 멈췄어요"
            waiting -> "화면을 받는 중…"
            else -> null
        }
        notice.text = text
        notice.visibility = if (text == null) GONE else VISIBLE
    }

    private fun toastIn(t: String) = android.widget.Toast.makeText(context, t, android.widget.Toast.LENGTH_SHORT).show()

    /** 영상 비율을 지키며 가운데에 */
    private fun fitVideo() {
        val bw = videoBox.width
        val bh = videoBox.height
        if (bw <= 0 || bh <= 0) return
        val scale = minOf(bw.toFloat() / videoW, bh.toFloat() / videoH)
        val w = (videoW * scale).toInt().coerceAtLeast(1)
        val h = (videoH * scale).toInt().coerceAtLeast(1)
        val lp = surfaceView.layoutParams as LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h
            surfaceView.layoutParams = lp
        }
    }

    private fun releaseDecoder() {
        decoder?.release()
        decoder = null
    }

    // ---------------- 화면(Surface) ----------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        waiting = true
        renderNotice()
        jam.mirrorRestart() // 새 화면 → 키프레임부터
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseDecoder()
        surface = null
    }

    /**
     * MediaCodec 디코더 하나 (자기 스레드에서 비동기). 밀리면(대기 6개 넘음) 버리고 "behind" 를 알린다.
     */
    private class Decoder(surface: Surface, first: MirrorAssembler.Frame, private val events: (String) -> Unit) {
        val mime = first.mime
        val w = first.w
        val h = first.h
        private val thread = HandlerThread("jam-mirror").apply { start() }
        private val handler = Handler(thread.looper)
        private val codec: MediaCodec
        private val pending = ArrayDeque<MirrorAssembler.Frame>()
        private val freeInputs = ArrayDeque<Int>()
        @Volatile private var released = false

        init {
            val fmt = MediaFormat.createVideoFormat(mime, w, h)
            if (mime == "video/avc") MirrorAssembler.avcConfig(first.data)?.let { (sps, pps) ->
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            }
            if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(fmt)
                ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(MediaFormat.createVideoFormat(mime, w, h))
                ?: throw IllegalStateException("no decoder")
            codec = MediaCodec.createByCodecName(name)
            try {
                codec.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(c: MediaCodec, index: Int) { freeInputs.addLast(index); pump() }
                    override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        if (released) return
                        try { c.releaseOutputBuffer(index, true) } catch (_: Exception) { return }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) events("rendered")
                    }
                    override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { if (!released) events("error") }
                    override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
                }, handler)
                codec.configure(fmt, surface, null, 0)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                thread.quitSafely()
                throw e
            }
        }

        fun submit(f: MirrorAssembler.Frame) = handler.post {
            if (released) return@post
            if (pending.size >= 6) {
                // 이 폰이 못 따라감 → 쌓인 것은 버리고 다음 키프레임부터 (호스트에게 화질을 낮춰 달라고)
                pending.clear()
                events("behind")
                return@post
            }
            pending.addLast(f)
            pump()
        }

        private fun pump() {
            while (!released && pending.isNotEmpty() && freeInputs.isNotEmpty()) {
                val f = pending.removeFirst()
                val idx = freeInputs.removeFirst()
                try {
                    val buf = codec.getInputBuffer(idx) ?: continue
                    if (f.data.size > buf.capacity()) { events("error"); return }
                    buf.clear()
                    buf.put(f.data)
                    codec.queueInputBuffer(idx, 0, f.data.size, f.ts, if (f.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                } catch (e: Exception) {
                    events("error")
                    return
                }
            }
        }

        fun release() {
            released = true
            handler.post {
                try { codec.stop() } catch (_: Exception) {}
                try { codec.release() } catch (_: Exception) {}
                thread.quitSafely()
            }
        }
    }

    companion object {
        val PAUSE_TEXT = mapOf(
            "typing" to "⌨ 호스트가 입력 중이라 화면을 잠시 멈췄어요",
            "secret" to "🔒 호스트가 비밀 입력 칸에 있어서 화면을 멈췄어요",
            "page" to "🔒 로그인 · 결제 화면이라 화면을 멈췄어요",
        )
    }
}
