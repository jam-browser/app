package io.github.jambrowser.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.github.jambrowser.core.Site
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 클립보드 경고 (데스크톱 src/renderer/safety.js 와 같은 규칙)
 *  - 사이트가 클립보드에 글자를 넣으면 내용을 보여 주고 "명령어라면 절대 실행하지 마세요" 라고 경고한다
 *    (가짜 "인증" 화면이 명령어를 복사시킨 뒤 붙여넣어 실행하게 하는 사기 대비)
 *  - 내가 직접 선택해서 복사한 글자와 같으면 정상 복사로 보고 경고하지 않는다
 */
class ClipboardGuard(private val ctx: Context) {
    private val cm = ctx.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var lastClipboard: String? = null
    private var lastWarnAt = 0L
    private var lastWarnText: String? = null
    private val warnedSites = HashSet<String>()

    private val COMMAND_RE = Regex(
        "(powershell|pwsh|cmd(\\.exe)?\\s*/[ck]|mshta|wscript|cscript|rundll32|regsvr32|certutil|bitsadmin|msiexec|curl\\s+|wget\\s+|\\biwr\\b|\\birm\\b|invoke-webrequest|invoke-restmethod|invoke-expression|\\biex\\b|start-process|-enc(odedcommand)?\\b|frombase64string|\\|\\s*(ba)?sh\\b|\\bsudo\\s|\\bchmod\\s|net\\s+user|reg\\s+add|schtasks|\\b(adb|pm)\\s+install|\\bsu\\s+-c|\\.(ps1|bat|cmd|vbs|hta|exe|msi|apk)\\b|https?://\\S+\\s*\\|)",
        RegexOption.IGNORE_CASE,
    )

    private fun norm(s: String?) = s.orEmpty().replace(Regex("\\s+"), " ").trim()

    private fun read(): String? = try {
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    } catch (_: Exception) {
        null
    }

    /** 앱이 다시 앞으로 오면 (다른 앱에서 복사하고 돌아온 경우는 사이트 탓이 아니므로) 기준을 새로 잡는다 */
    fun refreshBaseline() {
        main.postDelayed({ lastClipboard = read() }, 300)
    }

    fun onPageClipboard(pageUrl: String?, info: JsonObject?) {
        val kind = (info?.get("kind") as? JsonPrimitive)?.content
        val selection = (info?.get("selection") as? JsonPrimitive)?.takeIf { it.isString }?.content
        main.postDelayed({
            val text = read()
            val changed = text != lastClipboard
            lastClipboard = text
            if (text.isNullOrEmpty()) return@postDelayed
            if (kind == "copy-event" && norm(text) == norm(selection)) return@postDelayed // 내가 직접 복사
            if (!changed && kind == "copy-event") return@postDelayed
            if (text == lastWarnText && System.currentTimeMillis() - lastWarnAt < 4000) return@postDelayed
            lastWarnText = text
            lastWarnAt = System.currentTimeMillis()
            warn(pageUrl, text)
        }, 200)
    }

    fun onClipboardCodeOnSite(pageUrl: String?) {
        val site = Site.siteOf(pageUrl) ?: return
        if (!warnedSites.add(site)) return
        Toast.makeText(ctx, "📋 이 사이트에는 클립보드에 글자를 넣는 코드가 있어요. 복사된 명령어는 절대 실행하지 마세요", Toast.LENGTH_LONG).show()
    }

    private fun warn(pageUrl: String?, text: String) {
        val isCmd = COMMAND_RE.containsMatchIn(text) || Regex("^\\s*[$>#]\\s", RegexOption.MULTILINE).containsMatchIn(text)
        val preview = if (text.length > 600) text.take(600) + "…" else text
        val msg = buildString {
            append("${Site.pretty(pageUrl).ifEmpty { "알 수 없는 사이트" }} 가 클립보드에 아래 글자를 넣었어요.\n\n")
            append(preview)
            append("\n\n")
            if (isCmd) append("🚨 명령어처럼 보여요! 터미널 · 실행 창 · 다른 앱 어디에도 붙여넣어 실행하지 마세요.\n")
            append("명령어라면 절대 실행하지 마세요. \"인증\", \"오류 해결\" 을 위해 붙여넣으라는 안내는 사기예요.")
        }
        val dlg = AlertDialog.Builder(ctx)
            .setTitle(if (isCmd) "⚠️ 위험한 클립보드 내용" else "📋 사이트가 클립보드를 바꿨어요")
            .setMessage(msg)
            .setNegativeButton("클립보드 비우기") { _, _ ->
                try { cm.clearPrimaryClip() } catch (_: Exception) { cm.setPrimaryClip(ClipData.newPlainText("", "")) }
                lastClipboard = null
                Toast.makeText(ctx, "클립보드를 비웠어요", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("확인", null)
            .show()
        if (isCmd) dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#E5484D"))
    }
}
