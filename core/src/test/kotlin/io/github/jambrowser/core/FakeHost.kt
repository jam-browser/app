package io.github.jambrowser.core

import kotlinx.serialization.json.JsonElement
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 안드로이드 화면 대신 쓰는 가짜 Host: 하는 일을 한 줄씩 기록한다. 컨트롤러는 "main" 스레드 하나에서만 돈다 */
class FakeHost : JamController.Host {
    private val main = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fake-main").apply { isDaemon = true } }
    val log: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile var tabUrl: String? = "about:blank"
    @Volatile var title = "제목"
    lateinit var controller: JamController

    fun <T> onMain(block: () -> T): T = main.submit(Callable { block() }).get(5, TimeUnit.SECONDS)

    fun await(entry: String, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(log) { if (log.any { it.startsWith(entry) }) return }
            Thread.sleep(30)
        }
        throw AssertionError("'$entry' 가 일어나지 않음. 기록: ${synchronized(log) { log.toList() }}")
    }

    override fun runOnMain(block: () -> Unit) { main.execute(block) }
    override fun schedule(ms: Long, block: () -> Unit): JamController.Cancellable {
        val f = main.schedule(block, ms, TimeUnit.MILLISECONDS)
        return JamController.Cancellable { f.cancel(false) }
    }
    override fun jamTabUrl() = tabUrl
    override fun jamTabTitle() = title
    override fun jamTabLoading() = false
    override fun jamTabInFront() = true
    override fun loadInJamTab(url: String) { log += "load $url" }
    override fun toPage(channel: String, data: JsonElement) { log += "page $channel $data" }
    override fun toast(text: String) { log += "toast $text" }
    override fun changed(what: JamController.Change) {}
    override fun chat(line: JamController.ChatLine) { log += "chat ${line.text}" }
    override fun ended(reason: String?) { log += "ended $reason" }
    override fun mirror(info: MirrorAssembler.Info?) { log += "mirror ${info?.sid} ${info?.title}" }
    override fun mirrorFrame(frame: MirrorAssembler.Frame) { log += "frame ${frame.f} ${if (frame.key) "key" else "delta"} ${frame.codec} ${frame.w}x${frame.h} ${frame.data.size}"; frames += frame }
    override fun mirrorPaused(paused: String?, secret: Boolean) { log += "mpaused $paused $secret" }
    override fun fileReady(file: JamFiles.SharedFile, open: Boolean) { log += "fileReady ${file.name} $open" }
    val frames: MutableList<MirrorAssembler.Frame> = Collections.synchronizedList(ArrayList())
}
