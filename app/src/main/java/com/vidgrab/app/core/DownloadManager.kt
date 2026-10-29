package com.vidgrab.app.core

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载队列：2 路并发，任务持久化，应用重启后可恢复并续传。
 */
object DownloadManager {

    private const val MAX_CONCURRENT = 2
    private const val SAVE_INTERVAL_MS = 2000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(MAX_CONCURRENT)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private lateinit var appContext: Context
    private val lastSave = AtomicLong(0)

    fun init(context: Context) {
        appContext = context.applicationContext
        DownloadStore.init(appContext)
        val restored = DownloadStore.load()
        if (restored.isNotEmpty()) {
            _tasks.value = restored
        }
    }

    /** 应用启动后调用：把上次中断的任务重新排入队列（yt-dlp/直链会尝试续传）。 */
    fun resumePending() {
        if (!::appContext.isInitialized) return
        val queued = _tasks.value.filter { it.state == TaskState.QUEUED }
        if (queued.isEmpty()) return
        DownloadService.start(appContext)
        queued.forEach { schedule(it) }
    }

    fun enqueue(
        url: String,
        title: String,
        thumbnail: String?,
        selector: String,
        audioOnly: Boolean
    ) {
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title,
            thumbnail = thumbnail,
            selector = selector,
            audioOnly = audioOnly
        )
        _tasks.update { it + task }
        persist()
        DownloadService.start(appContext)
        schedule(task)
    }

    fun retry(id: String) {
        val current = _tasks.value.firstOrNull { it.id == id } ?: return
        if (current.state != TaskState.ERROR && current.state != TaskState.CANCELED) return
        val reset = current.copy(
            state = TaskState.QUEUED,
            progress = 0f,
            speed = "",
            eta = "",
            error = null
        )
        _tasks.update { list -> list.map { if (it.id == id) reset else it } }
        persist()
        DownloadService.start(appContext)
        schedule(reset)
    }

    private fun schedule(task: DownloadTask) {
        if (jobs.containsKey(task.id)) return
        val job = scope.launch {
            gate.withPermit {
                runTask(task)
            }
            maybeStopService()
        }
        jobs[task.id] = job
    }

    private suspend fun runTask(task: DownloadTask) {
        update(task.id) { it.copy(state = TaskState.DOWNLOADING, error = null) }
        Notifications.showTask(appContext, task.copy(state = TaskState.DOWNLOADING))
        try {
            val file = YtDlpEngine.download(appContext, task) { progress, speed, eta ->
                update(task.id) { it.copy(progress = progress, speed = speed, eta = eta) }
            }
            update(task.id) {
                it.copy(state = TaskState.DONE, progress = 1f, filePath = file?.absolutePath)
            }
            Notifications.showDone(appContext, task.title)
        } catch (e: YoutubeDL.CanceledException) {
            update(task.id) { it.copy(state = TaskState.CANCELED) }
        } catch (e: Throwable) {
            if (stateOf(task.id) == TaskState.CANCELED) return
            update(task.id) {
                it.copy(state = TaskState.ERROR, error = e.message ?: e.javaClass.simpleName)
            }
            Notifications.showError(appContext, task.title)
        } finally {
            jobs.remove(task.id)
        }
    }

    fun cancel(id: String) {
        update(id) { it.copy(state = TaskState.CANCELED) }
        jobs.remove(id)?.cancel()
        DirectDownloader.cancel(id)
        scope.launch {
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        }
    }

    fun remove(id: String) {
        jobs.remove(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun clearFinished() {
        _tasks.update { list ->
            list.filter { it.state == TaskState.QUEUED || it.state == TaskState.DOWNLOADING }
        }
        persist()
    }

    private fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        val stateChanged = AtomicBoolean(false)
        _tasks.update { list ->
            list.map { old ->
                if (old.id != id) return@map old
                val updated = transform(old)
                if (updated.state != old.state) stateChanged.set(true)
                updated
            }
        }
        val now = System.currentTimeMillis()
        if (stateChanged.get() || now - lastSave.get() >= SAVE_INTERVAL_MS) {
            lastSave.set(now)
            DownloadStore.save(_tasks.value)
        }
    }

    private fun persist() {
        lastSave.set(System.currentTimeMillis())
        DownloadStore.save(_tasks.value)
    }

    private fun stateOf(id: String): TaskState? =
        _tasks.value.firstOrNull { it.id == id }?.state

    private fun maybeStopService() {
        val active = _tasks.value.any {
            it.state == TaskState.QUEUED || it.state == TaskState.DOWNLOADING
        }
        if (!active) DownloadService.stop(appContext)
    }
}
