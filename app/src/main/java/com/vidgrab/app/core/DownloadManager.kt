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
import java.io.File
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

    private suspend fun runTask(queued: DownloadTask) {
        // 排队期间可能改过标题，用最新状态开跑
        val task = _tasks.value.firstOrNull { it.id == queued.id } ?: queued
        update(task.id) { it.copy(state = TaskState.DOWNLOADING, error = null) }
        Notifications.showTask(appContext, task.copy(state = TaskState.DOWNLOADING))
        try {
            val produced = YtDlpEngine.download(
                appContext,
                task,
                onProgress = { progress, speed, eta ->
                    update(task.id) { it.copy(progress = progress, speed = speed, eta = eta) }
                },
                onFile = { path ->
                    // 下载过程中就记下路径，取消后删除任务也能清掉 .part
                    update(task.id) { it.copy(filePath = path) }
                }
            )
            // 下载中可能改过名，落盘时以最新标题为准
            val current = _tasks.value.firstOrNull { it.id == task.id }
            val file = renameFinished(produced, current?.title ?: task.title)
            update(task.id) {
                it.copy(state = TaskState.DONE, progress = 1f, filePath = file?.absolutePath)
            }
            Notifications.showDone(appContext, current?.title ?: task.title)
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

    /** 删除任务；deleteFile 为 true 时连同下载的文件与 .part 续传文件一起删除。 */
    fun remove(id: String, deleteFile: Boolean = false) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.state == TaskState.QUEUED || task.state == TaskState.DOWNLOADING) {
            cancel(id)
        }
        jobs.remove(id)
        if (deleteFile) deleteTaskFiles(task)
        _tasks.update { list -> list.filterNot { it.id == id } }
        persist()
        maybeStopService()
    }

    /** 批量删除选中的任务；deleteFile 为 true 时连同文件一起删除。 */
    fun removeAll(ids: Collection<String>, deleteFile: Boolean = false) {
        if (ids.isEmpty()) return
        val targets = _tasks.value.filter { it.id in ids }
        targets.forEach { task ->
            if (task.state == TaskState.QUEUED || task.state == TaskState.DOWNLOADING) {
                cancel(task.id)
            }
            jobs.remove(task.id)
            if (deleteFile) deleteTaskFiles(task)
        }
        _tasks.update { list -> list.filterNot { it.id in ids } }
        persist()
        maybeStopService()
    }

    /** 清除已结束的任务；deleteFiles 为 true 时连带删除它们留下的文件。 */
    fun clearFinished(deleteFiles: Boolean = false) {
        val finished = _tasks.value.filter {
            it.state == TaskState.DONE || it.state == TaskState.ERROR || it.state == TaskState.CANCELED
        }
        if (deleteFiles) finished.forEach(::deleteTaskFiles)
        _tasks.update { list ->
            list.filter { it.state == TaskState.QUEUED || it.state == TaskState.DOWNLOADING }
        }
        persist()
    }

    /**
     * 重命名任务：已完成的任务立即改文件名；
     * 排队/下载中的任务先改标题，下载完成后按新标题落盘。
     */
    fun rename(id: String, newTitle: String): Boolean {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return false
        val base = FileNames.clean(newTitle).ifBlank { return false }
        if (task.state != TaskState.DONE) {
            _tasks.update { list ->
                list.map { if (it.id == id) it.copy(title = base) else it }
            }
            persist()
            return true
        }
        val file = task.filePath?.let(::File) ?: return false
        if (!file.exists()) return false
        val dir = file.parentFile ?: return false
        val target = FileNames.uniqueFile(dir, base, file.extension, file)
        val moved = target == file || runCatching { file.renameTo(target) }.getOrDefault(false)
        if (!moved) return false
        _tasks.update { list ->
            list.map {
                if (it.id == id) it.copy(title = base, filePath = target.absolutePath) else it
            }
        }
        persist()
        return true
    }

    /** 下载完成时按任务的最新标题落盘（下载中改过名也能生效）。 */
    private fun renameFinished(file: File?, title: String): File? {
        if (file == null || !file.exists()) return file
        val base = FileNames.clean(title)
        if (base.isBlank() || file.nameWithoutExtension == base) return file
        val dir = file.parentFile ?: return file
        val target = FileNames.uniqueFile(dir, base, file.extension, file)
        return if (runCatching { file.renameTo(target) }.getOrDefault(false)) target else file
    }

    /** 任务在磁盘上可能存在的文件：成品、.part 续传文件、.ytdl 元数据。 */
    fun filesOf(task: DownloadTask): List<File> {
        val path = task.filePath ?: return emptyList()
        val dir = runCatching { YtDlpEngine.outputDir(appContext).canonicalFile }.getOrNull()
            ?: return emptyList()
        val candidates = linkedSetOf(
            File(path),
            File("$path.part"),
            File(path.removeSuffix(".part") + ".ytdl")
        )
        return candidates.filter { file ->
            file.exists() &&
                runCatching { file.canonicalFile.parentFile == dir }.getOrDefault(false)
        }
    }

    private fun deleteTaskFiles(task: DownloadTask) {
        filesOf(task).forEach { runCatching { it.delete() } }
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
