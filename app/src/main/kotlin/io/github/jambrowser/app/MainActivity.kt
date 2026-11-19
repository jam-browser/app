package io.github.jambrowser.app

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import io.github.jambrowser.core.Invite
import io.github.jambrowser.core.JamConnection
import io.github.jambrowser.core.JamController
import io.github.jambrowser.core.JamController.Change
import io.github.jambrowser.core.Site
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Jam Browser 폰 앱 화면
 *  - 탭이 있는 WebView 브라우저 + Jam 패널(참여 · 사람 · 채팅 · 대기열)
 *  - Jam 규칙은 core 의 JamController 가 데스크톱과 똑같이 처리하고, 여기서는 화면과 웹뷰만 다룬다
 */
class MainActivity : AppCompatActivity(), Tab.TabHost, JamController.Host {
    private val main = Handler(Looper.getMainLooper())
    private val http by lazy { JamConnection.defaultClient() }
    private lateinit var jam: JamController
    private lateinit var downloads: DownloadGuard
    private lateinit var clipboard: ClipboardGuard
    private lateinit var updater: AppUpdater

    private val tabs = ArrayList<Tab>()
    private var active: Tab? = null
    private var jamTab: Tab? = null
    private var tabSeq = 0
    private var lastRemoteNavAt = 0L
    private var appInFront = true

    // 화면 요소
    private lateinit var urlBox: EditText
    private lateinit var tabsButton: TextView
    private lateinit var jamFlag: TextView
    private lateinit var lockFlag: TextView
    private lateinit var progress: ProgressBar
    private lateinit var webHolder: FrameLayout
    private lateinit var reactionLayer: FrameLayout
    private lateinit var pingWheel: PingWheel
    private lateinit var panel: View
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerAction: Button
    private lateinit var jamButton: TextView
    private lateinit var panelStatus: TextView
    private lateinit var nameBox: EditText
    private lateinit var inviteBox: EditText
    private lateinit var joinButton: Button
    private lateinit var joinStatus: TextView
    private lateinit var peopleBox: LinearLayout
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatInput: EditText
    private lateinit var queueBox: LinearLayout
    private lateinit var nowPlaying: TextView
    private var page = "join"
    private var unread = 0
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var requestDialog: AlertDialog? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
    }

    private val prefs by lazy { getSharedPreferences("jam", MODE_PRIVATE) }
    private val homeUrl = "https://www.google.com/"

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        root.isSoundEffectsEnabled = false
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        downloads = DownloadGuard(this)
        clipboard = ClipboardGuard(this)
        jam = JamController(this, { inv, name, resume, l -> JamConnection(http, inv, name, resume, l).start() })
        jam.pingEnabled = prefs.getBoolean("pingEnabled", true)
        bindViews()
        newTab(homeUrl)
        handleIntent(intent)
        updater = AppUpdater(this, http)
        updater.start()
        updater.handleDebugIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> showFullscreen(null, null)
                    panel.isVisible -> showPanel(false)
                    active?.webView?.canGoBack() == true -> active?.webView?.goBack()
                    tabs.size > 1 && active != jamTab -> closeTab(active!!)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        updater.handleDebugIntent(intent)
    }

    /** 초대 링크 · 공유받은 글: 참여 화면에 채우기만 하고, 참여는 사용자가 직접 누른다 */
    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        val inv = Invite.find(text)
        if (inv != null) {
            if (jam.status != JamController.Status.IDLE) return toast("이미 Jam에 참여 중이에요. 나간 뒤에 다시 시도하세요")
            inviteBox.setText(Invite.find(text)?.let { text.trim().lines().firstOrNull { l -> Invite.find(l) != null }?.trim() } ?: text)
            showPanel(true)
            showPage("join")
            toast("초대 코드를 채웠어요. 이름을 확인하고 \"참여하기\"를 누르세요")
        } else {
            Regex("https?://\\S+").find(text)?.let { newTab(it.value) }
        }
    }

    private fun bindViews() {
        urlBox = findViewById(R.id.url)
        tabsButton = findViewById(R.id.tabs_button)
        jamFlag = findViewById(R.id.jam_flag)
        lockFlag = findViewById(R.id.lock_flag)
        progress = findViewById(R.id.progress)
        webHolder = findViewById(R.id.web_holder)
        reactionLayer = findViewById(R.id.reaction_layer)
        // 핑 휠: 웹 화면 · 반응 위, Jam 패널 아래
        pingWheel = PingWheel(this)
        findViewById<FrameLayout>(R.id.content).addView(
            pingWheel, 2, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        panel = findViewById(R.id.panel)
        banner = findViewById(R.id.banner)
        bannerText = findViewById(R.id.banner_text)
        bannerAction = findViewById(R.id.banner_action)
        jamButton = findViewById(R.id.jam_button)
        panelStatus = findViewById(R.id.panel_status)
        nameBox = findViewById(R.id.name)
        inviteBox = findViewById(R.id.invite)
        joinButton = findViewById(R.id.join)
        joinStatus = findViewById(R.id.join_status)
        peopleBox = findViewById(R.id.people)
        chatBox = findViewById(R.id.chat)
        chatScroll = findViewById(R.id.chat_scroll)
        chatInput = findViewById(R.id.chat_input)
        queueBox = findViewById(R.id.queue)
        nowPlaying = findViewById(R.id.now_playing)

        nameBox.setText(prefs.getString("name", ""))

        urlBox.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                go(urlBox.text.toString())
                true
            } else false
        }
        tabsButton.setOnClickListener { showTabList() }
        findViewById<View>(R.id.menu_button).setOnClickListener(::showMenu)
        findViewById<View>(R.id.back).setOnClickListener { active?.webView?.takeIf { it.canGoBack() }?.goBack() }
        findViewById<View>(R.id.forward).setOnClickListener { active?.webView?.takeIf { it.canGoForward() }?.goForward() }
        findViewById<View>(R.id.reload).setOnClickListener { active?.webView?.reload() }
        jamButton.setOnClickListener { showPanel(!panel.isVisible) }
        findViewById<View>(R.id.panel_close).setOnClickListener { showPanel(false) }
        findViewById<View>(R.id.seg_people).setOnClickListener { showPage(if (jam.active) "people" else "join") }
        findViewById<View>(R.id.seg_chat).setOnClickListener { if (jam.active) showPage("chat") else toast("먼저 Jam에 참여하세요") }
        findViewById<View>(R.id.seg_queue).setOnClickListener { if (jam.active) showPage("queue") else toast("먼저 Jam에 참여하세요") }
        findViewById<View>(R.id.paste_invite).setOnClickListener {
            val t = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            val inv = Invite.find(t)
            if (inv == null) toast("클립보드에 초대 코드가 없어요") else inviteBox.setText(t.lines().firstOrNull { Invite.find(it) != null }?.trim() ?: t.trim())
        }
        joinButton.setOnClickListener { joinFromForm() }
        findViewById<View>(R.id.chat_send).setOnClickListener { sendChat() }
        chatInput.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendChat(); true } else false }
        findViewById<View>(R.id.queue_add_current).setOnClickListener {
            val t = active ?: return@setOnClickListener
            jam.queueAdd(t.url, t.title.ifEmpty { Site.pretty(t.url) })
        }
        findViewById<View>(R.id.queue_next).setOnClickListener { jam.queueNext() }
        bannerAction.setOnClickListener { onBannerAction() }

        val reactions = findViewById<LinearLayout>(R.id.reactions)
        for (e in listOf("👍", "😂", "😮", "❤️", "🔥", "👏")) {
            reactions.addView(TextView(this).apply {
                text = e
                textSize = 24f
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { jam.sendReaction(e) }
            })
        }
        renderAll()
    }

    // ======================================================================
    // 탭
    // ======================================================================
    private fun newTab(url: String?, activate: Boolean = true): Tab {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val tab = Tab(++tabSeq, this, wv)
        tabs += tab
        // Jam 탭에서 길게 누르기 → 핑 휠 (개인 탭 · 로그인 페이지에서는 평소처럼 글자 선택)
        wv.setOnTouchListener(PingGesture(wv, pingWheel, { tab == jamTab && jam.active && jam.pingEnabled && Site.isShareable(tab.url) }) { fx, fy, k ->
            jam.requestPing(fx, fy, k)
        })
        if (url != null) tab.load(url)
        if (activate) activate(tab)
        renderTabs()
        return tab
    }

    private fun activate(tab: Tab) {
        val before = active
        active = tab
        webHolder.removeAllViews()
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        webHolder.addView(tab.webView)
        tab.webView.onResume()
        if (before != null && before != tab) before.webView.onPause().also { if (before == jamTab) before.webView.onResume() } // Jam 탭은 뒤에서도 동기화를 계속
        updateToolbar()
        if (before == jamTab || tab == jamTab) jam.onJamTabFrontChanged()
        renderBanner()
    }

    private fun closeTab(tab: Tab) {
        if (tab == jamTab && jam.active) {
            AlertDialog.Builder(this).setTitle("Jam 탭을 닫을까요?").setMessage("Jam에서 나가게 돼요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("나가기") { _, _ -> jam.leave(); closeTab(tab) }.show()
            return
        }
        val i = tabs.indexOf(tab)
        tabs.remove(tab)
        if (tab == jamTab) { jamTab?.isJam = false; jamTab = null }
        tab.destroy()
        if (tabs.isEmpty()) newTab(homeUrl)
        else if (active == tab) activate(tabs[(i - 1).coerceAtLeast(0)])
        renderTabs()
    }

    private fun renderTabs() {
        tabsButton.text = if (tabs.size > 99) "∞" else tabs.size.toString()
    }

    private fun showTabList() {
        val labels = tabs.map { t ->
            (if (t == jamTab) "🎵 JAM · " else "") + (t.title.ifEmpty { Site.pretty(t.url) }).take(60) + if (t == active) "  ✓" else ""
        }.toMutableList()
        labels += "＋ 새 탭"
        AlertDialog.Builder(this)
            .setTitle("탭 ${tabs.size}개")
            .setItems(labels.toTypedArray()) { _, i -> if (i == tabs.size) newTab(homeUrl) else activate(tabs[i]) }
            .setNeutralButton("지금 탭 닫기") { _, _ -> active?.let(::closeTab) }
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun go(input: String) {
        val url = Site.toUrl(input) ?: return
        val tab = active ?: return
        hideKeyboard()
        if (tab == jamTab && jam.active && jam.follow && !jam.canGo(url)) return blockedNav(url)
        tab.load(url)
        tab.webView.requestFocus()
    }

    private fun updateToolbar() {
        val t = active ?: return
        if (!urlBox.hasFocus()) urlBox.setText(if (t.url == "about:blank") "" else t.url)
        jamFlag.isVisible = t == jamTab && jam.active
        lockFlag.isVisible = t == jamTab && jam.active && !jam.canCrossSite()
        findViewById<View>(R.id.back).alpha = if (t.webView.canGoBack()) 1f else 0.35f
        findViewById<View>(R.id.forward).alpha = if (t.webView.canGoForward()) 1f else 0.35f
    }

    private fun blockedNav(url: String) {
        val items = mutableListOf("개인 탭에서 열기")
        if (Site.isShareable(url)) items += "Jam 대기열에 추가"
        AlertDialog.Builder(this)
            .setTitle("🔒 다른 사이트로 이동하려면 호스트의 권한이 필요해요")
            .setItems(items.toTypedArray()) { _, i ->
                if (i == 0) newTab(url) else jam.queueAdd(url, Site.pretty(url))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------- Tab.TabHost ----------
    override fun onTabUrl(tab: Tab, url: String) {
        if (tab == active) updateToolbar()
        if (tab == jamTab) jam.onJamTabNavigated(url)
    }

    override fun onTabTitle(tab: Tab, title: String) {
        if (tab == jamTab) jam.onJamTabTitle(title)
    }

    override fun onTabProgress(tab: Tab, progress: Int, loading: Boolean) {
        if (tab != active) return
        this.progress.progress = progress
        this.progress.visibility = if (loading && progress < 100) View.VISIBLE else View.INVISIBLE
        updateToolbar()
    }

    override fun onTabLoadFinished(tab: Tab) {
        if (tab == jamTab) jam.onJamTabLoadFinished()
        if (tab == active) updateToolbar()
    }

    override fun onPageMessage(tab: Tab, channel: String, data: JsonElement?, mainFrame: Boolean, origin: String) {
        when (channel) {
            "jam:clipboard" -> clipboard.onPageClipboard(tab.url, data as? JsonObject)
            "jam:clipboard-site" -> if (mainFrame) clipboard.onClipboardCodeOnSite(tab.url)
            "jam:blocked" -> if (mainFrame && tab == jamTab) (data as? JsonPrimitive)?.content?.takeIf { Site.isWebUrl(it) }?.let(::blockedNav)
            else -> if (mainFrame && tab == jamTab) jam.onPageEvent(channel, data)
        }
    }

    override fun shouldBlock(tab: Tab, url: String, userGesture: Boolean): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme in setOf("jam", "jamr", "jamrs")) {
            handleIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            return true
        }
        if (scheme != "http" && scheme != "https") {
            // intent: · market: · tel: 등 앱 밖으로 나가는 주소는 열지 않는다 (사기 앱 설치 유도 방지)
            if (scheme != "about" && scheme != "data" && scheme != "blob" && scheme != "javascript") toast("앱 밖으로 나가는 주소($scheme:)는 열지 않아요")
            return scheme != "about"
        }
        if (tab == jamTab && jam.shouldBlockNavigation(url)) {
            blockedNav(url)
            return true
        }
        return false
    }

    override fun openInNewTab(url: String?, transport: WebView.WebViewTransport?, resultMsg: Message?) {
        val tab = newTab(url)
        if (transport != null && resultMsg != null) {
            transport.webView = tab.webView
            resultMsg.sendToTarget()
        }
    }

    override fun onDownload(tab: Tab, url: String, userAgent: String, contentDisposition: String?, mimeType: String?, length: Long) {
        val remote = tab == jamTab && System.currentTimeMillis() - lastRemoteNavAt < 10_000
        downloads.request(tab.url, url, userAgent, contentDisposition, mimeType, length, remote)
    }

    override fun showFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        val decor = window.decorView as FrameLayout
        val ctrl = WindowInsetsControllerCompat(window, decor)
        fullscreenView?.let { decor.removeView(it) }
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView = null
        fullscreenCallback = null
        if (view == null) {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        view.setBackgroundColor(Color.BLACK)
        decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun pickFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try {
            filePicker.launch(params.createIntent())
            true
        } catch (e: Exception) {
            fileCallback = null
            false
        }
    }

    // ======================================================================
    // Jam
    // ======================================================================
    private fun joinFromForm() {
        val name = nameBox.text.toString().trim()
        if (name.isEmpty()) {
            nameBox.requestFocus()
            return toast("먼저 이름을 입력해 주세요")
        }
        val inv = Invite.find(inviteBox.text.toString())
            ?: return toast("초대 코드 형식이 올바르지 않아요. 호스트가 보낸 코드 전체(jam://주소/코드/비밀키)를 붙여 넣으세요")
        prefs.edit().putString("name", name).apply()
        hideKeyboard()
        // Jam 탭: 지금 탭을 쓴다 (Jam 페이지로 이동하게 됨)
        val tab = active ?: newTab(null)
        jamTab?.isJam = false
        jamTab = tab
        tab.isJam = true
        chatBox.removeAllViews()
        unread = 0
        jam.join(inv, name)
    }

    private fun sendChat() {
        val t = chatInput.text.toString()
        if (t.isBlank()) return
        jam.sendChat(t)
        chatInput.setText("")
    }

    private fun onBannerAction() {
        when {
            jam.loginWait -> jam.finishLoginWait(false)
            jam.isDrifted() -> {
                jamTab?.let { if (active != it) activate(it) }
                if (!jam.follow) jam.follow = true else jam.catchUp()
            }
        }
    }

    // ---------- JamController.Host ----------
    override fun runOnMain(block: () -> Unit) { main.post(block) }

    override fun schedule(ms: Long, block: () -> Unit): JamController.Cancellable {
        val r = Runnable(block)
        main.postDelayed(r, ms)
        return JamController.Cancellable { main.removeCallbacks(r) }
    }

    override fun jamTabUrl(): String? = jamTab?.url
    override fun jamTabTitle(): String = jamTab?.title.orEmpty()
    override fun jamTabLoading(): Boolean = jamTab?.loading == true
    override fun jamTabInFront(): Boolean = appInFront && active == jamTab && jamTab != null

    override fun loadInJamTab(url: String) {
        val t = jamTab ?: return
        lastRemoteNavAt = System.currentTimeMillis()
        t.load(url)
    }

    override fun toPage(channel: String, data: JsonElement) {
        jamTab?.toPage(channel, data)
    }

    override fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun changed(what: Change) {
        when (what) {
            Change.STATE, Change.SETTINGS -> renderAll()
            Change.MEMBERS -> { renderPeople(); renderJamButton() }
            Change.NOW, Change.LOGIN -> { renderNow(); renderBanner() }
            Change.QUEUE -> renderQueue()
            Change.REQUESTS -> renderRequests()
        }
    }

    override fun chat(line: JamController.ChatLine) {
        val tv = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.text))
            setPadding(0, dp(3), 0, dp(3))
            text = if (line.system) {
                SpannableStringBuilder(line.text).apply { setSpan(ForegroundColorSpan(getColor(R.color.muted)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            } else {
                SpannableStringBuilder().apply {
                    append(line.name)
                    setSpan(ForegroundColorSpan(parseColor(line.color)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append("  ")
                    append(line.text)
                }
            }
        }
        chatBox.addView(tv)
        while (chatBox.childCount > 300) chatBox.removeViewAt(0)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        if (!(panel.isVisible && page == "chat") && !line.system) {
            unread++
            renderJamButton()
            if (!line.mine) toast("💬 ${line.name}: ${line.text.take(60)}")
        }
    }

    override fun reaction(emoji: String, color: String) {
        val tv = TextView(this).apply {
            text = emoji
            textSize = 34f
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
        lp.leftMargin = (reactionLayer.width * (0.15 + Math.random() * 0.7)).toInt()
        lp.bottomMargin = dp(40)
        reactionLayer.addView(tv, lp)
        tv.animate().translationY(-reactionLayer.height * 0.6f).alpha(0f).setDuration(1800).withEndAction { reactionLayer.removeView(tv) }.start()
    }

    override fun ended(reason: String?) {
        requestDialog?.dismiss()
        jamTab?.isJam = false
        jamTab = null
        renderAll()
        if (reason != null) {
            joinStatus.text = reason
        }
    }

    // ======================================================================
    // 화면 그리기
    // ======================================================================
    private fun renderAll() {
        joinButton.isEnabled = jam.status == JamController.Status.IDLE
        joinButton.text = when (jam.status) {
            JamController.Status.CONNECTING -> "연결 중…"
            JamController.Status.PENDING -> "호스트의 승인을 기다리는 중…"
            else -> "참여하기"
        }
        if (jam.status == JamController.Status.PENDING) joinStatus.text = "참여 요청을 보냈어요. 호스트가 수락하면 들어가요 (최대 2분)"
        else if (jam.status == JamController.Status.CONNECTING) joinStatus.text = ""
        if (!jam.active && page != "join") showPage("join")
        if (jam.active && page == "join") showPage("people")
        panelStatus.text = when (jam.status) {
            JamController.Status.ACTIVE -> "${jam.hostName}님의 Jam · ${jam.members.size}명 · ${jam.code}"
            JamController.Status.RECONNECTING -> "다시 연결 중… (${jam.reconnectAttempt}/5)"
            else -> ""
        }
        updateToolbar()
        renderJamButton()
        renderPeople()
        renderQueue()
        renderNow()
        renderBanner()
        renderRequests()
    }

    private fun renderJamButton() {
        jamButton.text = when (jam.status) {
            JamController.Status.IDLE -> "Jam 참여"
            JamController.Status.CONNECTING, JamController.Status.PENDING -> "연결 중…"
            JamController.Status.RECONNECTING -> "다시 연결 중…"
            JamController.Status.ACTIVE -> "🎵 Jam ${jam.members.size}명" + if (unread > 0) " · 💬$unread" else ""
        }
    }

    private fun renderBanner() {
        val inJamTab = active == jamTab && jamTab != null
        val cur = jam.current
        when {
            jam.status == JamController.Status.RECONNECTING -> {
                bannerText.text = "📶 Jam 연결이 끊겨서 다시 연결하는 중이에요… (${jam.reconnectAttempt}/5)"
                bannerAction.isVisible = false
                banner.isVisible = true
            }
            jam.active && jam.loginWait && inJamTab -> {
                bannerText.text = "🔐 로그인 중이에요. 내 로그인 창은 나만 보여요 — 로그인을 마치면 \"${cur?.title?.ifEmpty { null } ?: Site.pretty(cur?.url)}\" 로 자동으로 이동해요"
                bannerAction.text = "지금 Jam으로"
                bannerAction.isVisible = true
                banner.isVisible = true
            }
            jam.active && inJamTab && jam.isDrifted() -> {
                bannerText.text = "Jam은 지금 \"${cur?.title?.ifEmpty { null } ?: Site.pretty(cur?.url)}\" 을(를) 보고 있어요" + if (!jam.follow) " (따라가기 꺼짐)" else ""
                bannerAction.text = "Jam 페이지로"
                bannerAction.isVisible = true
                banner.isVisible = true
            }
            jam.active && !inJamTab -> {
                bannerText.text = "개인 탭이에요. 여기서 보는 페이지는 다른 사람에게 공유되지 않아요"
                bannerAction.text = "Jam 탭으로"
                bannerAction.isVisible = true
                bannerAction.setOnClickListener { jamTab?.let(::activate) }
                banner.isVisible = true
                return
            }
            else -> banner.isVisible = false
        }
        bannerAction.setOnClickListener { onBannerAction() }
    }

    private fun renderNow() {
        val cur = jam.current
        nowPlaying.text = if (cur == null) "Jam 페이지 없음" else "지금 Jam: ${cur.title.ifEmpty { Site.pretty(cur.url) }}"
    }

    private fun statusOf(m: JamController.Member): String = when {
        m.isPrivate -> "🔐 로그인 중"
        m.inAd -> "📺 광고 보는 중"
        m.away -> "다른 탭에 있어요"
        m.url == null -> "…"
        else -> m.title.ifEmpty { Site.pretty(m.url) }
    }

    private fun renderPeople() {
        peopleBox.removeAllViews()
        if (!jam.active) return
        // 맨 위: 따라가기 설정
        peopleBox.addView(switchRow("페이지 따라가기", "Jam 페이지가 바뀌면 같이 이동하고, 내가 이동하면 모두 같이 와요", jam.follow) { jam.follow = it })
        peopleBox.addView(switchRow("스크롤 따라가기", "다른 사람이 스크롤하면 내 화면도 같이 움직여요", jam.followScroll) { jam.followScroll = it })
        if (jam.isHost()) {
            peopleBox.addView(switchRow("모두에게 다른 사이트 허용", "끄면 권한을 준 사람만 다른 사이트로 이동할 수 있어요", jam.allowAllCrossSite) { jam.setAllowAll(it) })
        }
        peopleBox.addView(sectionTitle("참여자 ${jam.members.size}명"))
        for (m in jam.members) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(parseColor(m.color)) }
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) }
            })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            val badges = buildList {
                if (m.id == jam.youId) add("나")
                if (m.id == jam.hostId) add("호스트")
                if (m.canCrossSite && m.id != jam.hostId) add("다른 사이트 권한")
                if (m.verified) add("✔ 계정 확인")
                if (m.friend) add("친구")
                if (m.id == jam.followingId) add("따라가는 중")
            }
            col.addView(TextView(this).apply {
                text = m.name + if (badges.isNotEmpty()) "  · ${badges.joinToString(" · ")}" else ""
                setTextColor(getColor(R.color.text)); textSize = 15f
            })
            val res = m.view?.let { v -> " · ${v["w"]?.toString()?.substringBefore('.')}×${v["h"]?.toString()?.substringBefore('.')}" }.orEmpty()
            col.addView(TextView(this).apply {
                text = statusOf(m) + res
                setTextColor(getColor(R.color.muted)); textSize = 12f
                maxLines = 1
            })
            row.addView(col)
            if (m.id != jam.youId) row.setOnClickListener { memberMenu(m) }
            peopleBox.addView(row)
        }
        peopleBox.addView(Button(this).apply {
            text = "Jam 나가기"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Jam에서 나갈까요?")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("나가기") { _, _ -> jam.leave() }.show()
            }
        })
    }

    private fun memberMenu(m: JamController.Member) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "이 사람이 보는 곳으로 이동 (나만)" to { jamTab?.let(::activate); jam.scrollToMember(m.id) }
        actions += if (jam.followingId == m.id) "따라가기 멈추기" to { jam.stopFollow() } else "이 사람 화면 따라가기" to { jamTab?.let(::activate); jam.startFollow(m.id) }
        if (jam.isHost() && m.id != jam.hostId) {
            actions += (if (m.canCrossSite) "다른 사이트 권한 거두기" else "다른 사이트 권한 주기") to { jam.setPermission(m.id, !m.canCrossSite) }
            actions += "Jam에서 내보내기" to { jam.kick(m.id) }
        }
        AlertDialog.Builder(this).setTitle(m.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .setNegativeButton("닫기", null).show()
    }

    private fun renderQueue() {
        queueBox.removeAllViews()
        if (!jam.active) return
        if (jam.queue.isEmpty()) {
            queueBox.addView(TextView(this).apply {
                text = "대기열이 비어 있어요. 보고 싶은 페이지를 \"지금 페이지 추가\" 로 넣어 두세요."
                setTextColor(getColor(R.color.muted)); textSize = 13f
            })
        }
        for (q in jam.queue) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            row.addView(TextView(this).apply {
                text = "${q.title.ifEmpty { Site.pretty(q.url) }}\n${q.addedByName}님이 추가"
                setTextColor(getColor(R.color.text)); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "열기"; setOnClickListener { jam.queuePlay(q.id) }
            })
            if (jam.isHost() || q.addedBy == jam.youId) {
                row.addView(Button(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = "✕"; setOnClickListener { jam.queueRemove(q.id) }
                })
            }
            queueBox.addView(row)
        }
    }

    private fun renderRequests() {
        // 호스트가 나가면 폰이 호스트가 될 수 있다 → 참여 요청을 받는다
        val r = jam.requests.values.firstOrNull()
        if (r == null) { requestDialog?.dismiss(); requestDialog = null; return }
        if (requestDialog?.isShowing == true) return
        val where = if (r.ip.startsWith("relay:")) "인터넷(중계 서버)으로 접속 · ${r.ip.removePrefix("relay:")}" else "같은 네트워크에서 접속 · ${r.ip}"
        requestDialog = AlertDialog.Builder(this)
            .setTitle("참여 요청: ${r.name}")
            .setMessage(where + if (r.friend) "\n✔ 친구 목록에 있는 계정이에요" else "")
            .setCancelable(false)
            .setNegativeButton("거절") { _, _ -> requestDialog = null; jam.answerRequest(r.id, false) }
            .setPositiveButton("수락") { _, _ -> requestDialog = null; jam.answerRequest(r.id, true) }
            .show()
    }

    private fun showPanel(show: Boolean) {
        panel.isVisible = show
        if (show && page == "chat") { unread = 0; renderJamButton() }
        if (!show) hideKeyboard()
    }

    private fun showPage(p: String) {
        page = p
        findViewById<View>(R.id.page_join).isVisible = p == "join"
        findViewById<View>(R.id.page_people).isVisible = p == "people"
        findViewById<View>(R.id.page_chat).isVisible = p == "chat"
        findViewById<View>(R.id.page_queue).isVisible = p == "queue"
        val sel = mapOf("people" to R.id.seg_people, "join" to R.id.seg_people, "chat" to R.id.seg_chat, "queue" to R.id.seg_queue)
        for ((k, id) in sel) findViewById<TextView>(id).alpha = if (k == p || (p == "join" && k == "people")) 1f else 0.45f
        findViewById<TextView>(R.id.seg_people).text = if (jam.active) "사람" else "참여"
        if (p == "chat") { unread = 0; renderJamButton() }
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "새 탭")
        pm.menu.add(0, 2, 0, "확인 전 보관함 (다운로드)")
        if (jam.active) pm.menu.add(0, 3, 0, "지금 페이지를 Jam 대기열에")
        pm.menu.add(0, 4, 0, "이 탭을 Jam 탭으로 쓰기").isEnabled = jam.active && active != jamTab
        pm.menu.add(0, 6, 0, "설정")
        pm.menu.add(0, 7, 0, "업데이트 확인")
        pm.menu.add(0, 5, 0, "정보 · 보안 안내")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> newTab(homeUrl)
                2 -> downloads.showQuarantine()
                3 -> active?.let { t -> jam.queueAdd(t.url, t.title) }
                4 -> active?.let { t ->
                    jamTab?.isJam = false
                    toPage("jam:clear", kotlinx.serialization.json.JsonNull)
                    jamTab = t; t.isJam = true
                    jam.catchUp(); jam.sendPresence(); jam.refreshAllViews()
                    renderAll()
                }
                5 -> showAbout()
                6 -> showSettings()
                7 -> updater.checkNow(manual = true)
            }
            true
        }
        pm.show()
    }

    private fun showSettings() {
        val items = arrayOf("자동 업데이트 확인", "핑 찍기 · 보기 (Jam 탭을 길게 누르기)")
        val checked = booleanArrayOf(updater.enabled, jam.pingEnabled)
        AlertDialog.Builder(this)
            .setTitle("설정")
            .setMultiChoiceItems(items, checked) { _, i, v ->
                when (i) {
                    0 -> updater.enabled = v
                    1 -> { jam.pingEnabled = v; prefs.edit().putBoolean("pingEnabled", v).apply() }
                }
            }
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Jam Browser ${BuildConfig.VERSION_NAME}")
            .setMessage(
                "친구와 같은 웹페이지를 함께 보는 브라우저 (안드로이드)\n\n" +
                    "• 이 앱은 소리를 내지 않아요. 영상도 소리 없이 재생돼요.\n" +
                    "• 초대 코드의 비밀키는 서버로 보내지 않고, Jam 메시지는 모두 그 키로 암호화돼요 (AES-256-GCM). 서버 · 중계 서버는 전달만 해요.\n" +
                    "• 로그인 · 결제 페이지 주소는 다른 사람에게 공유되지 않아요. 로그인은 각자 자기 폰에서 해요.\n" +
                    "• 설치 파일(APK 등)은 '확인 전 보관함'에 갇혀 있다가, 직접 확인하고 내보내야만 나가요.\n" +
                    "• 앱 설치 권한은 이 앱 자신의 업데이트에만 써요 (GitHub 릴리스 · SHA-256 · 같은 서명 확인, 설치는 안드로이드가 다시 물어봐요).\n" +
                    "• 사이트가 클립보드에 글자를 넣으면 알려 드려요. 명령어라면 절대 실행하지 마세요.\n\n" +
                    "소스: github.com/jam-browser/app"
            )
            .setPositiveButton("닫기", null)
            .show()
    }

    // ---------- 작은 도우미 ----------
    private fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = title
            isChecked = checked
            textSize = 15f
            setTextColor(getColor(R.color.text))
            isSoundEffectsEnabled = false
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(6))
            addView(sw)
            addView(TextView(this@MainActivity).apply { text = desc; textSize = 12f; setTextColor(getColor(R.color.muted)) })
        }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(getColor(R.color.muted))
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun parseColor(c: String) = try { Color.parseColor(c) } catch (_: Exception) { Color.GRAY }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        currentFocus?.clearFocus()
    }

    // ---------- 생명주기 ----------
    override fun onResume() {
        super.onResume()
        appInFront = true
        clipboard.refreshBaseline()
        updater.onResume()
        active?.webView?.onResume()
        if (jam.active) jam.onJamTabFrontChanged()
    }

    override fun onPause() {
        super.onPause()
        appInFront = false
        if (jam.active) jam.onJamTabFrontChanged()
        // Jam 탭이 아니면 멈춘다 (배터리). Jam 탭은 연결이 살아 있는 동안 계속 동기화
        active?.takeIf { it != jamTab }?.webView?.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) jam.leave()
        updater.stop()
        tabs.forEach { it.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
