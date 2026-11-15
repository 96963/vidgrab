package com.vidgrab.app.core

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * 下载队列：并发数可调（默认 2），任务持久化，应用重启后可恢复并续传。
 */
object DownloadManager {

    private const val SAVE_INTERVAL_MS = 2000L
    private const val WIFI_WAIT = "仅 Wi-Fi 下载已开启，等待 Wi-Fi"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var gate = Semaphore(DownloadSettings.concurrent)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val autoRetryCount = ConcurrentHashMap<String, Int>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private lateinit var appContext: Context
    private val lastSave = AtomicLong(0)
    private val lastNotify = ConcurrentHashMap<String, Long>()
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        gate = Semaphore(DownloadSettings.concurrent)
        DownloadStore.init(appContext)
        val restored = DownloadStore.load()
        if (restored.isNotEmpty()) {
            _tasks.value = restored
        }
        if (networkCallback == null) {
            networkCallback = NetworkPolicy.observe(appContext) { wifiLike ->
                if (wifiLike) {
                    resumeWaitingForWifi()
                } else if (DownloadSettings.wifiOnly) {
                    suspendForWifi()
                }
            }
        }
    }

    /** 下载途中切到移动数据：立即暂停并保留 .part，回 Wi‑Fi 自动续传。 */
    private fun suspendForWifi() {
        if (!::appContext.isInitialized) return
        val active = _tasks.value.filter {
            it.state == TaskState.DOWNLOADING || it.state == TaskState.QUEUED
        }
        if (active.isEmpty()) return
        active.forEach { task ->
            update(task.id) {
                it.copy(state = TaskState.PAUSED, warning = WIFI_WAIT, speed = "", eta = "")
            }
            jobs.remove(task.id)?.cancel()
            DirectDownloader.cancel(task.id)
            runCatching { YoutubeDL.getInstance().destroyProcessById(task.id) }
            Notifications.showTask(appContext, _tasks.value.firstOrNull { it.id == task.id } ?: task)
        }
        maybeStopService()
    }

    /** 设置页修改并发数：只影响之后开始的任务，运行中的任务不打断。 */
    fun setConcurrent(value: Int) {
        DownloadSettings.concurrent = value
        gate = Semaphore(DownloadSettings.concurrent)
    }

    /** 应用启动后调用：把上次中断的任务重新排入队列（yt-dlp/直链会尝试续传）。 */
    fun resumePending() {
        if (!::appContext.isInitialized) return
        // PROCESSING 是「成品复制到公共目录」时被杀的状态，同样需要重新入队
        val queued = _tasks.value.filter {
            it.state == TaskState.QUEUED || it.state == TaskState.PROCESSING
        }
        if (queued.isEmpty()) return
        DownloadService.start(appContext)
        queued.forEach { task ->
            val ref = task.filePath
            if (task.state == TaskState.PROCESSING &&
                ref != null && PublicStorage.isContentRef(ref) && PublicStorage.exists(appContext, ref)
            ) {
                // 成品其实已经导出成功，只是没来得及写完成状态
                update(task.id) { it.copy(state = TaskState.DONE, progress = 1f) }
                return@forEach
            }
            if (task.state == TaskState.PROCESSING) {
                update(task.id) { it.copy(state = TaskState.QUEUED) }
            }
            schedule(task)
        }
    }

    fun enqueue(
        url: String,
        title: String,
        thumbnail: String?,
        selector: String,
        audioOnly: Boolean,
        writeSubs: Boolean = false,
        writeThumbnail: Boolean = false
    ) {
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title,
            thumbnail = thumbnail,
            selector = selector,
            audioOnly = audioOnly,
            writeSubs = writeSubs,
            writeThumbnail = writeThumbnail
        )
        _tasks.update { it + task }
        persist()
        DownloadService.start(appContext)
        schedule(task)
    }

    fun retry(id: String) {
        val current = _tasks.value.firstOrNull { it.id == id } ?: return
        if (current.state != TaskState.ERROR && current.state != TaskState.CANCELED) return
        autoRetryCount.remove(id)
        val reset = current.copy(
            state = TaskState.QUEUED,
            progress = 0f,
            speed = "",
            eta = "",
            error = null,
            warning = null
        )
        _tasks.update { list -> list.map { if (it.id == id) reset else it } }
        persist()
        DownloadService.start(appContext)
        schedule(reset)
    }

    /** 暂停任务：保留 .part / .ytdl，恢复时由 yt-dlp --continue 或直链 Range 续传。 */
    fun pause(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.state != TaskState.QUEUED && task.state != TaskState.DOWNLOADING) return
        update(id) {
            it.copy(state = TaskState.PAUSED, speed = "", eta = "", warning = "已暂停")
        }
        jobs.remove(id)?.cancel()
        DirectDownloader.cancel(id)
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        Notifications.showTask(appContext, _tasks.value.firstOrNull { it.id == id } ?: task)
        maybeStopService()
    }

    fun resume(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.state != TaskState.PAUSED) return
        val reset = task.copy(
            state = TaskState.QUEUED,
            speed = "",
            eta = "",
            error = null,
            warning = null
        )
        _tasks.update { list -> list.map { if (it.id == id) reset else it } }
        persist()
        DownloadService.start(appContext)
        schedule(reset)
    }

    /** 网络从流量切回 Wi‑Fi 时，把“等待 Wi‑Fi”的任务重新排队。 */
    fun resumeWaitingForWifi() {
        if (!::appContext.isInitialized) return
        val waiting = _tasks.value.filter {
            it.state == TaskState.PAUSED && it.warning == WIFI_WAIT
        }
        if (waiting.isEmpty()) return
        DownloadService.start(appContext)
        waiting.forEach { task ->
            val reset = task.copy(state = TaskState.QUEUED, warning = null)
            _tasks.update { list -> list.map { if (it.id == task.id) reset else it } }
            persist()
            schedule(reset)
        }
    }

    private fun schedule(task: DownloadTask) {
        if (jobs.containsKey(task.id)) return
        val job = scope.launch {
            if (!passNetworkGate(task)) {
                jobs.remove(task.id)
                maybeStopService()
                return@launch
            }
            gate.withPermit {
                runTask(task)
            }
            maybeStopService()
        }
        jobs[task.id] = job
    }

    /**
     * 仅 Wi‑Fi 开关在排队前判断：没有 Wi‑Fi 时置 PAUSED 并留提示，
     * 等 NetworkCallback 回到 Wi‑Fi 再恢复，不占用并发槽。
     */
    private fun passNetworkGate(task: DownloadTask): Boolean {
        if (stateOf(task.id) != TaskState.QUEUED) return false
        if (!DownloadSettings.wifiOnly) return true
        if (NetworkPolicy.isWifiLike(appContext)) return true
        update(task.id) {
            it.copy(state = TaskState.PAUSED, warning = WIFI_WAIT, speed = "", eta = "")
        }
        notifyProgress(task.id, force = true)
        return false
    }

    private suspend fun runTask(queued: DownloadTask) {
        // 排队期间可能改过标题，用最新状态开跑
        val task = _tasks.value.firstOrNull { it.id == queued.id } ?: return
        if (task.state == TaskState.PAUSED || task.state == TaskState.CANCELED) {
            jobs.remove(task.id)
            return
        }
        update(task.id) {
            it.copy(
                state = TaskState.DOWNLOADING,
                error = null,
                warning = null,
                speed = "",
                eta = ""
            )
        }
        notifyProgress(task.id, force = true)
        // 续传时进度从已有值继续，不能归零（暂停→继续、自动重试都走这里）
        var shownProgress = task.progress
        try {
            val produced = YtDlpEngine.download(
                appContext,
                task,
                onProgress = { progress, speed, eta ->
                    // 续传时 yt-dlp 偶尔会先回调一个 0%，用本次运行的最大值挡住回退
                    if (progress > shownProgress + 0.02f) shownProgress = progress
                    update(task.id) { it.copy(progress = shownProgress, speed = speed, eta = eta) }
                    notifyProgress(task.id)
                },
                onFile = { path ->
                    // 下载过程中就记下路径，取消后删除任务也能清掉 .part
                    update(task.id) { it.copy(filePath = path) }
                }
            )
            // 下载中可能改过名，落盘时以最新标题为准
            val current = _tasks.value.firstOrNull { it.id == task.id }
            val desiredTitle = current?.title ?: task.title
            val file = renameFinished(produced, desiredTitle, task.url)
            // 列表展开入队时标题是「视频 N」占位名：用 yt-dlp 生成文件名里的真实标题回填任务
            if (FileNames.isPlaceholder(desiredTitle)) {
                val realTitle = produced?.let { FileNames.titleFromGeneratedName(it.name) }
                if (!realTitle.isNullOrBlank()) {
                    update(task.id) { it.copy(title = realTitle) }
                }
            }
            // 成品导出到公共下载目录；大文件复制期间保持「处理中」并刷新进度
            var ref: String? = null
            var warning: String? = null
            if (file != null && file.exists()) {
                update(task.id) {
                    it.copy(state = TaskState.PROCESSING, progress = 0f, speed = "", eta = "")
                }
                notifyProgress(task.id, force = true)
                val result = PublicStorage.publish(appContext, file) { copied ->
                    update(task.id) { it.copy(progress = copied) }
                    notifyProgress(task.id)
                }
                if (_tasks.value.none { it.id == task.id }) {
                    // 复制期间任务被删除，公共目录里的成品也一并清掉
                    (result as? PublishResult.Success)?.let {
                        PublicStorage.delete(appContext, it.ref)
                    }
                    return
                }
                when (result) {
                    is PublishResult.Success -> ref = result.ref
                    is PublishResult.Failure -> {
                        // 公共目录写不进去时保留私有文件，但不能悄悄回退
                        ref = file.absolutePath
                        warning = result.message
                    }
                }
            } else {
                ref = file?.absolutePath
                warning = "下载完成但没有找到输出文件，可能已被移动或删除"
            }
            val finishedTitle = current?.title ?: task.title
            update(task.id) {
                it.copy(
                    state = TaskState.DONE,
                    progress = 1f,
                    filePath = ref,
                    warning = warning,
                    speed = "",
                    eta = ""
                )
            }
            Notifications.showDone(appContext, task.id, finishedTitle, warning)
            autoRetryCount.remove(task.id)
        } catch (e: YoutubeDL.CanceledException) {
            if (stateOf(task.id) == TaskState.PAUSED) {
                Notifications.showTask(appContext, _tasks.value.firstOrNull { it.id == task.id } ?: task)
                return
            }
            update(task.id) { it.copy(state = TaskState.CANCELED, speed = "", eta = "") }
            Notifications.cancelTask(appContext, task.id)
        } catch (e: Throwable) {
            val state = stateOf(task.id)
            if (state == TaskState.CANCELED) {
                Notifications.cancelTask(appContext, task.id)
                return
            }
            // 用户暂停：保留 .part，不改 ERROR
            if (state == TaskState.PAUSED) {
                Notifications.showTask(appContext, _tasks.value.firstOrNull { it.id == task.id } ?: task)
                return
            }
            // 前台服务超时等主动中止场景已写好错误信息，不要被 "Job was cancelled" 覆盖
            if (state == TaskState.ERROR && e is java.util.concurrent.CancellationException) return
            val friendly = ErrorMessages.describe(e.message)
            // 网络类中断（超时/连接重置/5xx）自动退避重试，避免 B 站尾段限速还得用户手动点
            val attempt = autoRetryCount[task.id] ?: 0
            if (state == TaskState.DOWNLOADING &&
                attempt < RetryPolicy.MAX_AUTO_RETRY &&
                RetryPolicy.isRetryableNetworkError(e.message)
            ) {
                autoRetryCount[task.id] = attempt + 1
                val waitMs = RetryPolicy.backoffFor(attempt)
                update(task.id) {
                    it.copy(
                        state = TaskState.QUEUED,
                        warning = "网络中断，自动重试 ${attempt + 1}/${RetryPolicy.MAX_AUTO_RETRY}",
                        error = null,
                        speed = "",
                        eta = ""
                    )
                }
                persist()
                DiagLog.w(
                    "Download",
                    "auto retry ${attempt + 1}/${RetryPolicy.MAX_AUTO_RETRY} in ${waitMs}ms: ${e.message?.take(120)}"
                )
                scope.launch {
                    delay(waitMs)
                    val now = _tasks.value.firstOrNull { it.id == task.id }
                    if (now != null && now.state == TaskState.QUEUED) {
                        DownloadService.start(appContext)
                        schedule(now)
                    }
                }
                return
            }
            update(task.id) {
                it.copy(
                    state = TaskState.ERROR,
                    error = friendly.message,
                    speed = "",
                    eta = ""
                )
            }
            Notifications.showError(appContext, task.id, task.title)
        } finally {
            jobs.remove(task.id)
            lastNotify.remove(task.id)
        }
    }

    /** 进度通知节流：最多约 1.2 秒刷新一次，避免高频 notify 拖慢下载。 */
    private fun notifyProgress(id: String, force: Boolean = false) {
        if (!::appContext.isInitialized) return
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val last = lastNotify[id] ?: 0L
        if (!force && now - last < 1200) return
        lastNotify[id] = now
        Notifications.showTask(appContext, task)
    }

    /**
     * Android 15 起 dataSync 前台服务有约 6 小时/24 小时上限，系统会回调 Service.onTimeout。
     * 这里把进行中的任务停下并保留 .part，用户点“重试”可续传。
     */
    fun onServiceTimeout() {
        if (!::appContext.isInitialized) return
        val active = _tasks.value.filter {
            it.state == TaskState.QUEUED ||
                it.state == TaskState.DOWNLOADING ||
                it.state == TaskState.PROCESSING
        }
        active.forEach { task ->
            update(task.id) {
                it.copy(
                    state = TaskState.ERROR,
                    error = "系统限制前台下载时长（约 6 小时），已停止；点“重试”可继续下载",
                    speed = "",
                    eta = ""
                )
            }
            jobs.remove(task.id)?.cancel()
            DirectDownloader.cancel(task.id)
            runCatching { YoutubeDL.getInstance().destroyProcessById(task.id) }
            Notifications.showError(appContext, task.id, task.title)
        }
    }

    fun cancel(id: String) {
        update(id) { it.copy(state = TaskState.CANCELED) }
        jobs.remove(id)?.cancel()
        DirectDownloader.cancel(id)
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        Notifications.cancelTask(appContext, id)
    }

    /** 删除任务；deleteFile 为 true 时连同下载的文件与 .part 续传文件一起删除。 */
    fun remove(id: String, deleteFile: Boolean = false) {
        autoRetryCount.remove(id)
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.state == TaskState.QUEUED || task.state == TaskState.DOWNLOADING) {
            cancel(id)
        }
        jobs.remove(id)
        if (deleteFile) deleteTaskFiles(task)
        _tasks.update { list -> list.filterNot { it.id == id } }
        persist()
        Notifications.cancelTask(appContext, id)
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
        targets.forEach { Notifications.cancelTask(appContext, it.id) }
        maybeStopService()
    }

    /** 清除已结束的任务；deleteFiles 为 true 时连带删除它们留下的文件。 */
    fun clearFinished(deleteFiles: Boolean = false) {
        val finished = _tasks.value.filter {
            it.state == TaskState.DONE || it.state == TaskState.ERROR || it.state == TaskState.CANCELED
        }
        if (deleteFiles) finished.forEach(::deleteTaskFiles)
        finished.forEach { Notifications.cancelTask(appContext, it.id) }
        _tasks.update { list ->
            list.filter {
                it.state == TaskState.QUEUED ||
                    it.state == TaskState.DOWNLOADING ||
                    it.state == TaskState.PAUSED
            }
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
        val ref = task.filePath ?: return false
        if (PublicStorage.isContentRef(ref)) {
            val newRef = PublicStorage.rename(appContext, ref, base) ?: return false
            _tasks.update { list ->
                list.map {
                    if (it.id == id) it.copy(title = base, filePath = newRef) else it
                }
            }
            persist()
            return true
        }
        val file = File(ref)
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
    private fun renameFinished(file: File?, title: String, url: String?): File? {
        if (file == null || !file.exists()) return file
        // 占位标题（列表展开的「视频 N」）不要覆盖 yt-dlp 写好的真实标题文件名
        if (FileNames.isPlaceholder(title)) return file
        val base = FileNames.safeTitle(title, url)
        if (base.isBlank() || file.nameWithoutExtension == base) return file
        val dir = file.parentFile ?: return file
        val target = FileNames.uniqueFile(dir, base, file.extension, file)
        if (runCatching { file.renameTo(target) }.getOrDefault(false)) return target
        // renameTo 失败时退回复制 + 删除
        return runCatching {
            file.copyTo(target, overwrite = false)
            file.delete()
            target
        }.getOrDefault(file)
    }

    /** 任务是否还有可操作的文件：公共目录引用或私有目录里的成品/.part。 */
    fun hasFile(task: DownloadTask): Boolean {
        val ref = task.filePath ?: return false
        if (!::appContext.isInitialized) return false
        if (PublicStorage.isContentRef(ref)) return PublicStorage.exists(appContext, ref)
        return filesOf(task).isNotEmpty()
    }

    /** 任务在私有目录里可能存在的文件：成品、.part 续传文件、.ytdl 元数据。 */
    fun filesOf(task: DownloadTask): List<File> {
        val path = task.filePath ?: return emptyList()
        if (PublicStorage.isContentRef(path)) return emptyList()
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
        val ref = task.filePath
        if (ref != null && PublicStorage.isContentRef(ref)) {
            PublicStorage.delete(appContext, ref)
            return
        }
        // Android 9 及以下可能直接落在公共 Download/VidGrab
        PublicStorage.deletePublicPath(ref)
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
