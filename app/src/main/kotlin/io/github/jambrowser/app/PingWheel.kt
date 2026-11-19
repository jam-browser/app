package io.github.jambrowser.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.PathParser
import io.github.jambrowser.core.JamController
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 핑 휠 (데스크톱 webview-preload.js 의 openWheel 과 같은 모양 · 방향)
 *   길게 누르면 열리고, 손가락을 끌어서 고른 뒤 놓으면 핑:
 *   위 ⚠️ 조심 · 아래 ❓ 뭐지? · 오른쪽 👀 이거 봐 · 왼쪽 🏃 갈게요 · 움직이지 않고 놓으면 📍 여기
 * 그리기만 한다 (터치를 받지 않음). 길게 누르기 판단은 [PingGesture].
 */
class PingWheel(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private var cx = 0f
    private var cy = 0f
    private var startX = 0f
    private var startY = 0f
    private var dotX = 0f
    private var dotY = 0f
    var kind = "here"; private set
    val isOpen: Boolean get() = visibility == VISIBLE

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.argb(64, 255, 255, 255) }
    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(72, 255, 212, 59) }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textSize = 11 * d
        setShadowLayer(2 * d, 0f, d, Color.argb(200, 0, 0, 0))
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(204, 255, 255, 255) }

    init {
        visibility = INVISIBLE // GONE 이면 크기가 0 이라 가장자리 계산이 틀어짐
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 휠 열기 (x, y = 누른 곳, 이 뷰 좌표). 가장자리에서 눌러도 잘리지 않게 그림만 안쪽으로 */
    fun open(x: Float, y: Float) {
        startX = x; startY = y
        val m = 96 * d
        cx = x.coerceIn(m, maxOf(m, width - m))
        cy = y.coerceIn(m, maxOf(m, height - m))
        dotX = 0f; dotY = 0f
        kind = "here"
        visibility = VISIBLE
        scaleX = 0.6f; scaleY = 0.6f; alpha = 0f
        pivotX = cx; pivotY = cy
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
        invalidate()
    }

    fun move(x: Float, y: Float) {
        val dx = x - startX
        val dy = y - startY
        val dist = hypot(dx, dy)
        kind = kindFor(dx, dy, 22 * d)
        val r = min(70 * d, dist)
        val a = atan2(dy, dx)
        dotX = cos(a) * r
        dotY = sin(a) * r
        invalidate()
    }

    fun close() {
        animate().cancel()
        visibility = INVISIBLE // GONE 이면 크기가 0 이라 가장자리 계산이 틀어짐
    }

    override fun onDraw(c: Canvas) {
        if (!isOpen) return
        val r = 92 * d
        discPaint.shader = RadialGradient(cx, cy, r, intArrayOf(Color.argb(140, 20, 20, 22), Color.argb(140, 20, 20, 22), Color.argb(199, 20, 20, 22), Color.argb(199, 20, 20, 22)), floatArrayOf(0f, 0.3f, 0.31f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, discPaint)
        c.drawCircle(cx, cy, r, ringPaint)
        for ((k, off) in SLICES) {
            val x = cx + off.first * d
            val y = cy + off.second * d
            val on = k == kind
            val s = if (on) 1.18f else 1f
            c.save()
            c.scale(s, s, x, y)
            if (on) c.drawRoundRect(RectF(x - 30 * d, y - 24 * d, x + 30 * d, y + 24 * d), 12 * d, 12 * d, onPaint)
            drawIcon(c, k, x, y - 6 * d, 24 * d)
            c.drawText(JamController.PING_LABEL[k] ?: "", x, y + 18 * d, labelPaint)
            c.restore()
        }
        c.drawCircle(cx + dotX, cy + dotY, 4 * d, dotPaint)
    }

    private fun drawIcon(c: Canvas, k: String, x: Float, y: Float, size: Float) {
        if (k == "what") return drawWhat(c, x - size / 2, y - size / 2, size)
        emojiPaint.textSize = size * 0.86f
        val fm = emojiPaint.fontMetrics
        c.drawText(JamController.PING_ICON[k] ?: "📍", x, y - (fm.ascent + fm.descent) / 2, emojiPaint)
    }

    companion object {
        /** 가운데에서의 위치 (dp) */
        val SLICES = linkedMapOf("here" to (0f to 0f), "warn" to (0f to -60f), "what" to (0f to 60f), "look" to (60f to 0f), "omw" to (-60f to 0f))

        /** 끈 방향 → 핑 종류 (dead 만큼 안 움직였으면 여기) */
        fun kindFor(dx: Float, dy: Float, dead: Float): String = when {
            hypot(dx, dy) < dead -> "here"
            abs(dx) > abs(dy) -> if (dx > 0) "look" else "omw"
            else -> if (dy < 0) "warn" else "what"
        }

        private val Q_PATH: Path by lazy { PathParser.createPathFromPathData("M11.6 12.4c0-2.7 2-4.5 4.5-4.5 2.6 0 4.5 1.7 4.5 3.9 0 3.5-4.3 3.6-4.3 6.9") }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        /** 노란 물음표 (데스크톱 whatIcon 과 같은 그림, 32×32 기준) */
        fun drawWhat(c: Canvas, left: Float, top: Float, size: Float) {
            c.save()
            c.translate(left, top)
            c.scale(size / 32f, size / 32f)
            p.style = Paint.Style.FILL; p.color = Color.parseColor("#ffd43b"); p.alpha = 255
            c.drawCircle(16f, 16f, 14f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f; p.color = Color.parseColor("#1d1d1d")
            c.drawCircle(16f, 16f, 14f, p)
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.alpha = 97
            c.drawOval(RectF(5.5f, 6.3f, 17.5f, 12.7f), p)
            p.alpha = 255
            p.style = Paint.Style.STROKE; p.strokeWidth = 3.3f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.color = Color.parseColor("#1d1d1d")
            c.drawPath(Q_PATH, p)
            p.style = Paint.Style.FILL
            c.drawCircle(16.3f, 23.7f, 2.1f, p)
            c.restore()
        }
    }
}

/**
 * 길게 누르기 → 핑 휠. [target](웹뷰 · 미러링 영상) 의 터치를 먼저 본다.
 *  - 짧게 누르기 · 스크롤 · 두 손가락(확대)은 그대로 target 으로
 *  - 손가락을 움직이지 않고 오래 누르면 휠을 열고, target 에는 "취소"를 보내 글자 선택 · 메뉴가 뜨지 않게 한다
 *  - 놓으면 onPick(보이는 영역 비율 x, y, 종류)
 */
class PingGesture(
    private val target: View,
    private val wheel: PingWheel,
    private val enabled: () -> Boolean,
    private val onPick: (fx: Double, fy: Double, kind: String) -> Unit,
) : View.OnTouchListener {
    private val main = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(target.context).scaledTouchSlop
    // 웹뷰 자체의 길게 누르기(글자 선택)보다 조금 먼저
    private val delay = (ViewConfiguration.getLongPressTimeout() - 80).coerceAtLeast(300).toLong()
    private var downX = 0f
    private var downY = 0f
    private var armed = false
    private var open = false
    private var last: MotionEvent? = null

    private val fire = Runnable {
        armed = false
        val ev = last ?: return@Runnable
        open = true
        // target 의 진행 중인 터치를 취소 (글자 선택 · 링크 길게 누르기 · 스크롤 시작 막기)
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        target.onTouchEvent(cancel)
        cancel.recycle()
        val (wx, wy) = toWheel(downX, downY)
        wheel.open(wx, wy)
    }

    private fun toWheel(x: Float, y: Float): Pair<Float, Float> {
        val a = IntArray(2).also(target::getLocationInWindow)
        val b = IntArray(2).also(wheel::getLocationInWindow)
        return (x + a[0] - b[0]) to (y + a[1] - b[1])
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                if (!enabled()) return false
                downX = ev.x; downY = ev.y
                last?.recycle(); last = MotionEvent.obtain(ev)
                armed = true
                main.postDelayed(fire, delay)
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (open) return true
                reset() // 두 손가락 = 확대
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (open) {
                    val (wx, wy) = toWheel(ev.x, ev.y)
                    wheel.move(wx, wy)
                    return true
                }
                if (armed && hypot(ev.x - downX, ev.y - downY) > slop) reset()
                if (armed) { last?.recycle(); last = MotionEvent.obtain(ev) }
                return false
            }
            MotionEvent.ACTION_UP -> {
                if (open) {
                    val kind = wheel.kind
                    wheel.close()
                    open = false
                    val w = target.width.coerceAtLeast(1)
                    val h = target.height.coerceAtLeast(1)
                    onPick((downX / w).toDouble().coerceIn(0.0, 1.0), (downY / h).toDouble().coerceIn(0.0, 1.0), kind)
                    return true
                }
                reset()
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                val was = open
                reset()
                return was
            }
        }
        return open
    }

    fun reset() {
        main.removeCallbacks(fire)
        armed = false
        if (open) { wheel.close(); open = false }
    }
}
