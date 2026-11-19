package io.github.jambrowser.app

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import io.github.jambrowser.core.JamFiles
import java.io.File
import java.util.concurrent.Executors

/**
 * 같이 보는 파일 보기 (받은 파일은 메모리에만 있고, PDF 만 그리는 동안 앱 전용 캐시 폴더에 잠깐 둔다)
 *  - PDF: 안드로이드 PdfRenderer 로 쪽마다 그림 (PDF 안의 스크립트는 실행되지 않음)
 *  - 이미지 · 텍스트: 그대로 보여 줌
 *  - HTML · SWF: 폰에서는 아직 열 수 없음
 * 닫으면 캐시 파일을 지운다. Jam 을 나가면 [clearCache] 로 모두 지운다.
 */
class FileViewer(private val ctx: Context, private val file: JamFiles.SharedFile, private val local: JamFiles.Local) {
    companion object {
        fun cacheDir(ctx: Context) = File(ctx.cacheDir, "jamfiles")
        fun clearCache(ctx: Context) { cacheDir(ctx).listFiles()?.forEach { it.delete() } }
        private const val MAX_TEXT = 1024 * 1024
    }

    private val d = ctx.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val dialog = Dialog(ctx, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pdfFile: File? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jam-pdf").apply { isDaemon = true } }
    private val cache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    fun show() {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#2B2D31")) }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#16181D"))
            setPadding((14 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
        }
        bar.addView(TextView(ctx).apply {
            text = "${file.icon} ${file.name}"
            setTextColor(Color.WHITE); textSize = 15f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(ctx).apply {
            text = "닫기 ✕"
            setTextColor(Color.WHITE); textSize = 15f
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            isSoundEffectsEnabled = false
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(bar)
        root.addView(body(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // 안드로이드 15 는 화면 끝까지 그리므로 상태 표시줄 · 내비게이션 바만큼 비운다
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { release() }
        dialog.show()
    }

    private fun message(t: String) = TextView(ctx).apply {
        text = t; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
    }

    private fun body(): View = try {
        when (file.kind) {
            "pdf" -> pdfView()
            "image" -> imageView()
            "text" -> ScrollView(ctx).apply {
                setBackgroundColor(Color.WHITE)
                addView(TextView(ctx).apply {
                    val n = minOf(local.bytes.size, MAX_TEXT)
                    text = String(local.bytes, 0, n, Charsets.UTF_8) + if (local.bytes.size > MAX_TEXT) "\n\n… (앞부분 1MB 만 보여요)" else ""
                    setTextColor(Color.parseColor("#16181D")); textSize = 14f
                    setTextIsSelectable(true)
                    setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
                })
            }
            else -> message("${file.icon} 이 형식(${file.kind})은 폰에서 아직 열 수 없어요.\nPC의 Jam Browser에서 같이 볼 수 있어요.")
        }
    } catch (e: Exception) {
        message("파일을 열 수 없어요 (${e.message})")
    }

    private fun imageView(): View {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(local.bytes, 0, local.bytes.size, opts)
        var sample = 1
        while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(local.bytes, 0, local.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return message("이미지를 열 수 없어요")
        return ImageView(ctx).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
    }

    private fun pdfView(): View {
        val dir = cacheDir(ctx).apply { mkdirs() }
        val f = File(dir, "${file.fid}.pdf")
        f.writeBytes(local.bytes)
        pdfFile = f
        val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = fd
        val r = PdfRenderer(fd)
        renderer = r
        val count = r.pageCount
        // 쪽 크기(비율)는 미리 읽어 둔다
        val sizes = (0 until count).map { i -> r.openPage(i).use { it.width to it.height } }
        val list = ListView(ctx).apply {
            divider = null
            dividerHeight = (8 * d).toInt()
            setBackgroundColor(Color.parseColor("#2B2D31"))
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            clipToPadding = false
        }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = count
            override fun getItem(position: Int) = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val width = parent.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
                val (pw, ph) = sizes[position]
                val height = (width.toLong() * ph / pw.coerceAtLeast(1)).toInt()
                val box = (convertView as? FrameLayout) ?: FrameLayout(ctx).apply {
                    addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(Color.WHITE) },
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    addView(TextView(ctx).apply { setTextColor(Color.GRAY); textSize = 11f; setPadding((6 * d).toInt(), (2 * d).toInt(), 0, 0) },
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
                }
                box.layoutParams = AbsListView.LayoutParams(width, height)
                val img = box.getChildAt(0) as ImageView
                (box.getChildAt(1) as TextView).text = "${position + 1} / $count"
                img.tag = position
                val cached = cache.get(position)
                img.setImageBitmap(cached)
                if (cached == null) render(position, width, height) { bmp -> if (img.tag == position) img.setImageBitmap(bmp) }
                return box
            }
        }
        return list
    }

    private fun render(page: Int, w: Int, h: Int, done: (Bitmap) -> Unit) {
        worker.execute {
            val r = renderer ?: return@execute
            val bmp = try {
                synchronized(r) {
                    val scale = minOf(1f, 2048f / maxOf(w, h)) // 너무 큰 그림은 줄여서
                    val b = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    r.openPage(page).use { it.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    b
                }
            } catch (e: Exception) {
                return@execute
            }
            main.post {
                cache.put(page, bmp)
                done(bmp)
            }
        }
    }

    private fun release() {
        worker.execute {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            renderer = null
            pfd = null
            pdfFile?.delete()
        }
        worker.shutdown()
        cache.evictAll()
    }
}
