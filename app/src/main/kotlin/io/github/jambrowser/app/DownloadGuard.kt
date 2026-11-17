package io.github.jambrowser.app

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.github.jambrowser.core.Site
import java.io.File

/**
 * 다운로드 지킴이 (데스크톱 main.js 의 "다운로드확인전" 보관과 같은 생각)
 *  - 모든 다운로드는 먼저 묻는다 (이름 · 크기 · 사이트)
 *  - 설치 · 실행될 수 있는 파일(apk 등)은 앱 전용 "확인 전 보관함"에 '이름.다운로드확인전' 으로 저장한다
 *      → 다른 앱 · 파일 관리자에서 바로 열리지 않고, 이 앱은 설치 권한이 없어서 절대 설치되지 않는다
 *      → 사용자가 보관함에서 경고를 읽고 직접 "내보내기"를 눌러야 다운로드 폴더로 나간다
 *  - Jam 에서 다른 사람이 이동시킨 페이지가 곧바로 다운로드를 시작하면 한 번 더 경고한다
 */
class DownloadGuard(private val ctx: Context) {
    companion object {
        const val HOLD_EXT = ".다운로드확인전"
        val RISKY_EXT = setOf(
            "apk", "apks", "apkm", "xapk", "aab", "dex", "jar",
            "exe", "msi", "msix", "msixbundle", "appx", "appxbundle", "appinstaller", "bat", "cmd", "com", "scr", "pif",
            "ps1", "psm1", "psd1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta", "cpl", "msc", "msp", "mst", "reg",
            "lnk", "url", "scf", "inf", "dll", "sys", "ocx", "chm", "application", "gadget", "iso", "img", "vhd", "vhdx",
            "dmg", "pkg", "deb", "rpm", "sh", "run", "appimage", "zip", "7z", "rar", "cab", "gz", "tgz",
            "xz", "bz2", "tar", "docm", "xlsm", "pptm", "dotm", "xlam", "ppam",
        )
        private val RISKY_MIME = setOf(
            "application/vnd.android.package-archive", "application/x-msdownload", "application/x-msdos-program",
            "application/x-sh", "application/java-archive", "application/x-apple-diskimage",
        )

        fun extOf(name: String) = name.substringAfterLast('.', "").lowercase()
    }

    private val dm = ctx.getSystemService(DownloadManager::class.java)

    fun quarantineDir(): File = File(ctx.getExternalFilesDir(null), "quarantine").apply { mkdirs() }

    fun isRisky(name: String, mime: String?) = extOf(name) in RISKY_EXT || mime?.lowercase() in RISKY_MIME

    /** WebView 의 다운로드 요청 */
    fun request(pageUrl: String?, url: String, userAgent: String, contentDisposition: String?, mimeType: String?, length: Long, fromRemoteNav: Boolean) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("이 형식의 다운로드(${url.substringBefore(':')})는 아직 지원하지 않아요")
            return
        }
        val name = fileNameOf(url, contentDisposition, mimeType)
        // 서버가 이름 · 종류를 속여도 주소의 확장자가 설치 파일이면 위험한 것으로 본다
        val urlExt = extOf(Uri.parse(url).lastPathSegment.orEmpty())
        val risky = isRisky(name, mimeType) || urlExt in RISKY_EXT
        val size = if (length > 0) Formatter.formatShortFileSize(ctx, length) else "크기 모름"
        val msg = buildString {
            append("파일: $name\n크기: $size\n사이트: ${Site.pretty(pageUrl ?: url)}\n")
            if (risky) {
                append("\n⚠️ 설치 · 실행될 수 있는 파일이에요.\n")
                append("받은 파일은 '확인 전 보관함'에만 저장되고 설치되지 않아요. 직접 확인하고 내보내기 전까지는 다운로드 폴더에도 나타나지 않아요.\n")
                append("\n모르는 사람이 \"앱을 설치하라\", \"인증하라\"고 하면 사기일 가능성이 높아요.")
            }
            if (fromRemoteNav) append("\n\n⚠️ Jam에서 다른 사람이 이동시킨 페이지가 바로 다운로드를 시작했어요. 내가 받으려던 파일이 아니라면 취소하세요.")
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (risky) "설치 파일을 받을까요?" else "파일을 받을까요?")
            .setMessage(msg)
            .setNegativeButton("취소", null)
            .setPositiveButton(if (risky) "보관함에 받기" else "받기") { _, _ -> enqueue(url, userAgent, name, mimeType, risky) }
            .show()
    }

    /**
     * 받을 파일 이름. URLUtil.guessFileName 은 종류가 application/octet-stream 이면
     * 'app.apk' 를 'app.bin' 으로 바꿔 버려서 설치 파일 검사를 피해 갈 수 있다 → 직접 정한다
     *   1) Content-Disposition 의 filename*= / filename=   2) 주소의 마지막 경로   3) 그래도 없으면 guessFileName
     */
    private fun fileNameOf(url: String, contentDisposition: String?, mimeType: String?): String {
        val cd = contentDisposition.orEmpty()
        val star = Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)?''([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
            ?.let { try { java.net.URLDecoder.decode(it.trim('"', ' '), "UTF-8") } catch (_: Exception) { null } }
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)?.trim()
        val fromUrl = Uri.parse(url).lastPathSegment?.takeIf { it.contains('.') }
        val raw = star ?: plain ?: fromUrl ?: URLUtil.guessFileName(url, contentDisposition, mimeType)
        return raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            .trim().trimStart('.').ifEmpty { "download" }.take(150)
    }

    private fun enqueue(url: String, userAgent: String, name: String, mime: String?, risky: Boolean) {
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(if (risky) "$name (확인 전 보관)" else name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .addRequestHeader("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
        if (mime != null && !risky) req.setMimeType(mime)
        if (risky) {
            // 앱 전용 폴더 + 실행되지 않는 이름. MIME 도 일반 바이너리로 (알림을 눌러도 설치 화면이 뜨지 않게)
            req.setMimeType("application/octet-stream")
            req.setDestinationUri(Uri.fromFile(uniqueFile(quarantineDir(), name + HOLD_EXT)))
        } else {
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        }
        try {
            dm.enqueue(req)
            toast(if (risky) "확인 전 보관함에 받는 중이에요 (⋮ → 확인 전 보관함)" else "다운로드를 시작했어요")
        } catch (e: Exception) {
            toast("다운로드를 시작하지 못했어요: ${e.message}")
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        while (f.exists()) f = File(dir, "${name.substringBefore(HOLD_EXT)} ($i)$HOLD_EXT").also { i++ }
        return f
    }

    /** 확인 전 보관함 목록 */
    fun showQuarantine() {
        val files = quarantineDir().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            AlertDialog.Builder(ctx).setTitle("확인 전 보관함").setMessage("보관 중인 파일이 없어요.").setPositiveButton("닫기", null).show()
            return
        }
        val labels = files.map { "${it.name.removeSuffix(HOLD_EXT)}  ·  ${Formatter.formatShortFileSize(ctx, it.length())}" }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("확인 전 보관함")
            .setItems(labels) { _, i -> fileActions(files[i]) }
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun fileActions(f: File) {
        val name = f.name.removeSuffix(HOLD_EXT)
        AlertDialog.Builder(ctx)
            .setTitle(name)
            .setMessage("이 파일은 아직 열리거나 설치되지 않도록 보관 중이에요.\n\n내보내면 다운로드 폴더에 원래 이름으로 저장돼요. 믿을 수 있는 곳에서 직접 받으려던 파일일 때만 내보내세요.")
            .setNegativeButton("삭제") { _, _ -> if (f.delete()) toast("삭제했어요") }
            .setNeutralButton("닫기", null)
            .setPositiveButton("내보내기…") { _, _ -> confirmRelease(f, name) }
            .show()
    }

    private fun confirmRelease(f: File, name: String) {
        AlertDialog.Builder(ctx)
            .setTitle("정말 내보낼까요?")
            .setMessage("⚠️ 설치 파일(APK 등)은 폰의 모든 정보를 빼가거나 폰을 조종할 수 있어요.\n\n누군가 \"이 앱을 설치하세요\", \"보안 인증 앱\" 이라고 했다면 사기예요.\n\n그래도 '$name' 을(를) 다운로드 폴더로 내보낼까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("내보내기") { _, _ -> release(f, name) }
            .show()
    }

    private fun release(f: File, name: String) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(name)) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return toast("내보내지 못했어요")
        try {
            resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            f.delete()
            toast("다운로드 폴더로 내보냈어요: $name")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            toast("내보내지 못했어요: ${e.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
}
