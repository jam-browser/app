package io.github.jambrowser.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.jambrowser.core.Update
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 앱 자동 업데이트 (화면 · 설치 쪽). 버전 비교 · 내려받기 · 해시 확인은 core 의 Update.
 *
 *  - 앱을 열 때와 몇 시간마다 GitHub 최신 릴리스를 확인 (설정 "자동 업데이트 확인" 으로 끔)
 *  - 새 버전이 있으면 먼저 묻고, 앱 전용 폴더에 내려받아 SHA-256 을 확인한다
 *  - 설치 전에 한 번 더: 같은 앱(패키지 이름) · 더 높은 버전 · 지금 앱과 같은 서명 키인지 확인
 *  - 설치는 안드로이드 PackageInstaller 가 자기 화면으로 사용자에게 확인받는다
 *  - 앱 설치 권한(REQUEST_INSTALL_PACKAGES)은 이 업데이트에만 쓴다. 웹에서 받은 파일은 여전히 "확인 전 보관함"에 갇힌다
 */
class AppUpdater(private val act: AppCompatActivity, private val http: OkHttpClient) {
    companion object {
        private const val CHECK_EVERY_MS = 6L * 3600 * 1000
        private const val ACTION_INSTALL = "io.github.jambrowser.app.UPDATE_STATUS"
        const val EXTRA_DEBUG_API = "jam_update_api"

        fun updatesDir(ctx: Context) = File(ctx.noBackupFilesDir, "updates")
    }

    private val prefs = act.getSharedPreferences("jam", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jam-update").apply { isDaemon = true } }
    private var busy = false
    private var prompt: AlertDialog? = null
    /** 내려받아 확인까지 끝났고, 설치 허용을 켜러 간 동안 기다리는 파일 */
    private var waitingPermission: File? = null
    @Volatile private var cancelDownload = false

    var enabled: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(v) {
            prefs.edit().putBoolean("autoUpdate", v).apply()
            if (v) maybeCheck()
        }

    // 디버그 빌드에서만: PC 의 테스트 서버 주소로 바꿔서 확인할 수 있다 (http 허용)
    private val apiUrl: String
        get() = if (BuildConfig.DEBUG) prefs.getString("updateApiDebug", null) ?: Update.LATEST_URL else Update.LATEST_URL
    private val allowHttp: Boolean get() = BuildConfig.DEBUG && apiUrl.startsWith("http://")

    private val periodic = object : Runnable {
        override fun run() {
            maybeCheck()
            main.postDelayed(this, CHECK_EVERY_MS)
        }
    }

    /** 앱 시작 */
    fun start() {
        // 지난번에 받아 둔 설치 파일은 지운다 (설치가 끝났거나 취소됨)
        updatesDir(act).listFiles()?.forEach { it.delete() }
        main.postDelayed(periodic, 4000) // 시작 직후 화면을 그리는 동안은 기다렸다가
    }

    fun stop() {
        main.removeCallbacks(periodic)
        cancelDownload = true
    }

    /** 앱이 다시 앞으로 왔을 때: 설치 권한을 켜러 갔다 왔으면 이어서 */
    fun onResume() {
        val f = waitingPermission ?: return
        waitingPermission = null
        if (act.packageManager.canRequestPackageInstalls() && f.exists()) install(f)
        else {
            f.delete()
            toast("앱 설치 허용이 꺼져 있어서 업데이트하지 않았어요")
        }
    }

    /** 디버그용: adb shell am start ... --es jam_update_api http://10.0.2.2:8123/latest */
    fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val u = intent?.getStringExtra(EXTRA_DEBUG_API) ?: return
        prefs.edit().putString("updateApiDebug", u.ifBlank { null }).apply()
        checkNow(manual = true)
    }

    private fun maybeCheck() {
        if (!enabled) return
        val last = prefs.getLong("updateLastCheck", 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS - 60_000) return
        checkNow(manual = false)
    }

    /** 확인 (manual = 메뉴에서 직접 누름 → 결과를 꼭 알려 줌) */
    fun checkNow(manual: Boolean) {
        if (busy) return if (manual) toast("업데이트를 확인하는 중이에요") else Unit
        busy = true
        val current = BuildConfig.VERSION_NAME
        val url = apiUrl
        val http = allowHttp
        worker.execute {
            val result = try {
                Result.success(Update.check(this.http, current, url, http))
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                busy = false
                if (act.isFinishing || act.isDestroyed) return@post
                prefs.edit().putLong("updateLastCheck", System.currentTimeMillis()).apply()
                result.onFailure { if (manual) toast(it.message ?: "업데이트를 확인하지 못했어요") }
                result.onSuccess { rel ->
                    when {
                        rel == null -> if (manual) toast("최신 버전이에요 (${BuildConfig.VERSION_NAME})")
                        !manual && prefs.getString("updateSkip", null) == rel.version -> Unit
                        else -> showPrompt(rel)
                    }
                }
            }
        }
    }

    private fun showPrompt(rel: Update.Release) {
        if (prompt?.isShowing == true) return
        val size = if (rel.apkSize > 0) " · ${Formatter.formatShortFileSize(act, rel.apkSize)}" else ""
        val notes = rel.notes.trim().take(1500)
        prompt = AlertDialog.Builder(act)
            .setTitle("새 버전이 있어요: ${rel.version}")
            .setMessage(
                "지금 버전: ${BuildConfig.VERSION_NAME}$size\n\n" +
                    (if (notes.isNotEmpty()) "$notes\n\n" else "") +
                    "받은 파일은 SHA-256 으로 확인하고, 설치는 안드로이드가 한 번 더 물어봐요. Jam 에 참여 중이면 설치하는 동안 잠깐 나가게 돼요."
            )
            .setPositiveButton("업데이트") { _, _ -> download(rel) }
            .setNegativeButton("나중에", null)
            .setNeutralButton("이 버전 건너뛰기") { _, _ -> prefs.edit().putString("updateSkip", rel.version).apply() }
            .show()
    }

    /** 확인을 마친 파일 설치: 이 앱에 "앱 설치 허용"이 꺼져 있으면 먼저 켜러 보낸다 */
    private fun beginInstall(f: File) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("앱 설치 허용이 필요해요")
                .setMessage("업데이트를 설치하려면 \"이 출처 허용\"을 한 번 켜 주세요. 이 허용은 Jam Browser 자기 자신의 업데이트에만 쓰여요 (웹에서 받은 설치 파일은 계속 확인 전 보관함에 갇혀요).")
                .setPositiveButton("설정 열기") { _, _ ->
                    waitingPermission = f
                    try {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                    } catch (e: Exception) {
                        waitingPermission = null
                        f.delete()
                        toast("설정 화면을 열 수 없어요")
                    }
                }
                .setNegativeButton("취소") { _, _ -> f.delete() }
                .setCancelable(false)
                .show()
            return
        }
        install(f)
    }

    private fun download(rel: Update.Release) {
        if (busy) return
        busy = true
        cancelDownload = false
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = rel.apkSize <= 0 }
        val label = TextView(act).apply { text = "받는 중…"; setPadding(0, 12, 0, 0) }
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(bar); addView(label) }
        val dlg = AlertDialog.Builder(act)
            .setTitle("업데이트 받는 중 (${rel.version})")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("취소") { _, _ -> cancelDownload = true }
            .show()
        val dest = File(updatesDir(act), "jam-update.apk")
        var lastUi = 0L
        worker.execute {
            val result = try {
                val f = Update.download(http, rel, dest, allowHttp, { cancelDownload }) { got, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUi < 150) return@download
                    lastUi = now
                    main.post {
                        if (total > 0) { bar.isIndeterminate = false; bar.progress = (got * 1000 / total).toInt() }
                        label.text = Formatter.formatShortFileSize(act, got) + if (total > 0) " / ${Formatter.formatShortFileSize(act, total)}" else ""
                    }
                }
                checkApk(f)
                Result.success(f)
            } catch (e: Exception) {
                dest.delete()
                Result.failure(e)
            }
            main.post {
                busy = false
                dlg.dismiss()
                if (act.isFinishing || act.isDestroyed) return@post
                result.onFailure { if (!cancelDownload) error(it.message ?: "업데이트를 받지 못했어요") }
                result.onSuccess { beginInstall(it) }
            }
        }
    }

    /** 설치 전 확인: 같은 앱 · 더 높은 버전 · 지금 앱과 같은 서명 (안드로이드도 서명이 다르면 거절하지만, 미리 알려 준다) */
    private fun checkApk(f: File) {
        val pm = act.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val apk: PackageInfo = pm.getPackageArchiveInfo(f.path, flags)
            ?: throw Update.UpdateException("받은 파일이 올바른 앱 설치 파일이 아니에요")
        if (apk.packageName != act.packageName) {
            throw Update.UpdateException("받은 파일이 이 앱(${act.packageName})의 업데이트가 아니에요 (${apk.packageName})")
        }
        val me = pm.getPackageInfo(act.packageName, flags)
        if (apk.longVersionCode <= me.longVersionCode) throw Update.UpdateException("받은 파일이 지금 버전보다 새 버전이 아니에요")
        val mine = signers(me)
        val theirs = signers(apk)
        // 서명 키를 교체(rotation)한 새 버전이면 새 APK 의 서명 이력에 지금 키가 들어 있다
        if (mine.isEmpty() || theirs.isEmpty() || !theirs.containsAll(mine)) {
            throw Update.UpdateException("받은 파일의 서명이 지금 앱과 달라요. 안전을 위해 설치하지 않았어요")
        }
    }

    private fun signers(p: PackageInfo): Set<String> {
        val info = p.signingInfo ?: return emptySet()
        val sigs = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        return sigs?.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }?.toSet() ?: emptySet()
    }

    private fun install(f: File) {
        try {
            val installer = act.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(act.packageName)
                setSize(f.length())
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                s.openWrite("base.apk", 0, f.length()).use { out ->
                    f.inputStream().use { it.copyTo(out, 64 * 1024) }
                    s.fsync(out)
                }
                val intent = Intent(act, UpdateStatusReceiver::class.java).setAction(ACTION_INSTALL).setPackage(act.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(act, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            f.delete()
            error("설치를 시작하지 못했어요 (${e.message})")
        }
    }

    private fun error(text: String) {
        AlertDialog.Builder(act).setTitle("업데이트하지 못했어요").setMessage(text).setPositiveButton("닫기", null).show()
    }

    private fun toast(t: String) = Toast.makeText(act, t, Toast.LENGTH_SHORT).show()
}

/** PackageInstaller 결과: 사용자 확인이 필요하면 안드로이드의 설치 확인 화면을 띄운다 */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(confirm) } catch (_: Exception) {}
                }
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdater.updatesDir(context).listFiles()?.forEach { it.delete() }
            else -> {
                AppUpdater.updatesDir(context).listFiles()?.forEach { it.delete() }
                val msg = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "업데이트 설치를 취소했어요"
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "업데이트를 설치할 수 없어요 (서명이 다르거나 기기와 맞지 않아요)"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "저장 공간이 부족해서 업데이트하지 못했어요"
                    else -> "업데이트를 설치하지 못했어요" + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { " ($it)" } ?: "")
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
