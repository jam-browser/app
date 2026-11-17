package io.github.jambrowser.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.jambrowser.core.Site
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 브라우저 탭 하나 (WebView 하나)
 *  - 모든 프레임에 소리 끄기 스크립트(jam-mute.js), 맨 위 문서에 Jam 스크립트(jam-page.js)를 문서 시작 시점에 넣는다
 *  - 페이지 스크립트와는 jamBridge(WebMessageListener)로만 주고받는다. 페이지가 보낸 값은 믿지 않고 검사한다
 */
@SuppressLint("SetJavaScriptEnabled")
class Tab(
    val id: Int,
    private val host: TabHost,
    val webView: WebView,
) {
    interface TabHost {
        fun onTabUrl(tab: Tab, url: String)
        fun onTabTitle(tab: Tab, title: String)
        fun onTabProgress(tab: Tab, progress: Int, loading: Boolean)
        fun onTabLoadFinished(tab: Tab)
        fun onPageMessage(tab: Tab, channel: String, data: JsonElement?, mainFrame: Boolean, origin: String)
        /** true 면 탐색을 막는다 (Jam 탭 다른 사이트 잠금 · 앱 밖 주소) */
        fun shouldBlock(tab: Tab, url: String, userGesture: Boolean): Boolean
        fun openInNewTab(url: String?, transport: WebView.WebViewTransport?, resultMsg: Message?)
        fun onDownload(tab: Tab, url: String, userAgent: String, contentDisposition: String?, mimeType: String?, length: Long)
        fun showFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?)
        fun pickFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    }

    var url: String = "about:blank"; private set
    var title: String = ""; private set
    var loading = false; private set
    var isJam = false
    private var proxy: JavaScriptReplyProxy? = null
    private var injectedFallback = false

    init {
        configure()
    }

    private fun configure() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // 영상은 소리 없이(jam-mute.js) 재생되므로, Jam 동기화가 스크립트로 재생을 시작할 수 있게 한다
        s.mediaPlaybackRequiresUserGesture = false
        s.setGeolocationEnabled(false)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) WebSettingsCompat.setSafeBrowsingEnabled(s, true)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true) // 로그인 창(구글 등)이 동작하도록
        webView.isSoundEffectsEnabled = false
        webView.isHapticFeedbackEnabled = false

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "jamBridge", setOf("*")) { _, message: WebMessageCompat, sourceOrigin, isMainFrame, replyProxy ->
                onBridgeMessage(message.data, isMainFrame, sourceOrigin, replyProxy)
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, Scripts.mute(webView.context), setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(webView, Scripts.page(webView.context), setOf("*"))
        } else {
            injectedFallback = true
        }

        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { u, ua, cd, mime, len -> host.onDownload(this, u, ua, cd, mime, len) }
    }

    /** 페이지 스크립트(jam-page.js)로 보낸다 */
    fun toPage(channel: String, data: JsonElement) {
        val p = proxy ?: return
        try {
            p.postMessage(buildJsonObject { put("ch", channel); put("d", data) }.toString())
        } catch (_: Exception) {
        }
    }

    private var rateWindow = 0L
    private var rateCount = 0

    private fun onBridgeMessage(raw: String?, isMainFrame: Boolean, origin: Uri, replyProxy: JavaScriptReplyProxy) {
        if (raw == null || raw.length > 16_000) return
        // 페이지가 다리를 마구 두드리지 못하게 (초당 120개)
        val now = System.currentTimeMillis()
        if (now - rateWindow > 1000) { rateWindow = now; rateCount = 0 }
        if (++rateCount > 120) return
        val o = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null } ?: return
        val ch = (o["ch"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        if (ch !in ALLOWED) return
        if (isMainFrame && replyProxy !== proxy) {
            // 새 문서의 스크립트가 처음 말을 걸었다 → 이 통로로 Jam 상태(사각형 · 잠금 · 영상)를 다시 보낸다
            proxy = replyProxy
            if (ch != "jam:ready") host.onPageMessage(this, "jam:ready", null, true, origin.toString())
        }
        host.onPageMessage(this, ch, o["d"], isMainFrame, origin.toString())
    }

    private fun injectFallback(view: WebView) {
        if (!injectedFallback) return
        view.evaluateJavascript(Scripts.mute(view.context), null)
        view.evaluateJavascript(Scripts.page(view.context), null)
    }

    fun load(u: String) {
        url = u
        webView.loadUrl(u)
    }

    fun destroy() {
        proxy = null
        webView.stopLoading()
        webView.webChromeClient = null
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val target = request.url.toString()
            // 서버 리다이렉트는 데스크톱과 같이 막지 않는다 (로그인 흐름 등)
            if (request.isRedirect && Site.isWebUrl(target)) return false
            return host.shouldBlock(this@Tab, target, request.hasGesture())
        }

        override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
            loading = true
            if (u != url) { url = u; host.onTabUrl(this@Tab, u) }
            host.onTabProgress(this@Tab, 5, true)
        }

        override fun onPageCommitVisible(view: WebView, u: String) = injectFallback(view)

        override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) {
            // 페이지 이동 + 유튜브 같은 한 페이지 앱의 주소 변경(pushState)도 여기로 온다
            if (u != url) { url = u; host.onTabUrl(this@Tab, u) }
        }

        override fun onPageFinished(view: WebView, u: String) {
            loading = false
            if (u != url && u != "about:blank") { url = u; host.onTabUrl(this@Tab, u) }
            injectFallback(view)
            host.onTabProgress(this@Tab, 100, false)
            host.onTabLoadFinished(this@Tab)
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, t: String?) {
            title = t.orEmpty()
            host.onTabTitle(this@Tab, title)
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) = host.onTabProgress(this@Tab, newProgress, newProgress < 100)

        // 새 창(target=_blank, window.open)은 새 탭(개인 탭)으로
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false // 팝업 광고 차단
            host.openInNewTab(null, resultMsg.obj as? WebView.WebViewTransport, resultMsg)
            return true
        }

        // 카메라 · 마이크 · 위치는 주지 않는다 (소리 없음 + 개인 정보)
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) = host.showFullscreen(view, callback)
        override fun onHideCustomView() = host.showFullscreen(null, null)

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean =
            host.pickFiles(filePathCallback, fileChooserParams)
    }

    companion object {
        val ALLOWED = setOf(
            "jam:ready", "jam:viewport", "jam:cursor", "jam:click", "jam:ad", "jam:media", "jam:media-tick",
            "jam:media-init", "jam:media-ready", "jam:blocked", "jam:clipboard", "jam:clipboard-site",
        )
    }
}

/** assets 의 스크립트를 한 번만 읽어 둔다 */
object Scripts {
    private var muteJs: String? = null
    private var pageJs: String? = null
    fun mute(ctx: android.content.Context) = muteJs ?: ctx.assets.open("jam-mute.js").bufferedReader().readText().also { muteJs = it }
    fun page(ctx: android.content.Context) = pageJs ?: ctx.assets.open("jam-page.js").bufferedReader().readText().also { pageJs = it }
}
