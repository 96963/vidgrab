package com.vidgrab.app.core

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * yt-dlp 引擎封装：负责初始化 Python/FFmpeg、解析链接信息、执行下载。
 * 底层使用 yausername/youtubedl-android（GPL-3.0）。
 */
object YtDlpEngine {

    private val initLock = Mutex()
    @Volatile
    private var initialized = false

    val isReady: Boolean get() = initialized

    suspend fun ensureReady(context: Context) {
        if (initialized) return
        withContext(Dispatchers.IO) {
            initLock.withLock {
                if (!initialized) {
                    YoutubeDL.getInstance().init(context.applicationContext)
                    FFmpeg.getInstance().init(context.applicationContext)
                    initialized = true
                }
            }
        }
    }

    /** 解析链接，返回标题、缩略图和可选格式列表。 */
    suspend fun fetchInfo(context: Context, url: String): MediaInfo = withContext(Dispatchers.IO) {
        ensureReady(context)
        val request = YoutubeDLRequest(url)
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--socket-timeout", "15")
        request.addDetectedHeaders(url)
        val info = try {
            YoutubeDL.getInstance().getInfo(request)
        } catch (e: YoutubeDLException) {
            // 直链 .mp4 等有时会被 yt-dlp 判为 Unsupported URL，改走原生 HTTP 下载
            if (DirectDownloader.isDirectMediaUrl(url) &&
                e.message?.contains("Unsupported URL", ignoreCase = true) == true
            ) {
                return@withContext MediaInfo(
                    title = url.substringBefore('?').substringAfterLast('/'),
                    uploader = null,
                    durationSeconds = 0,
                    thumbnail = null,
                    webpageUrl = url,
                    formats = listOf(
                        FormatOption(
                            id = DirectDownloader.DIRECT_SELECTOR,
                            label = "直接下载原始文件",
                            ext = url.substringBefore('?').substringAfterLast('.', ""),
                            filesize = 0,
                            audioOnly = false
                        )
                    )
                )
            }
            throw e
        }
        MediaInfo(
            title = info.title ?: info.fulltitle ?: url,
            uploader = info.uploader,
            durationSeconds = info.duration,
            thumbnail = info.thumbnail ?: info.thumbnails?.lastOrNull()?.url,
            webpageUrl = info.webpageUrl ?: url,
            formats = buildFormats(info.formats ?: emptyList())
        )
    }

    /** 浏览器嗅探到的直链，通常需要原页面的 Cookie/Referer/UA 才能访问。 */
    private fun YoutubeDLRequest.addDetectedHeaders(url: String): YoutubeDLRequest {
        MediaSniffer.headersFor(url).forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                addOption("--add-header", "$name: $value")
            }
        }
        return this
    }

    private fun buildFormats(formats: List<VideoFormat>): List<FormatOption> {
        val result = LinkedHashMap<String, FormatOption>()

        result["best"] = FormatOption(
            id = "bestvideo*+bestaudio/best",
            label = "最佳画质（自动合并）",
            ext = "mp4",
            filesize = 0,
            audioOnly = false
        )

        formats
            .filter { f ->
                val hasVideo = f.vcodec != null && f.vcodec != "none"
                hasVideo && f.height > 0 && !f.formatId.isNullOrBlank()
            }
            .sortedByDescending { it.height }
            .forEach { f ->
                val hasAudio = f.acodec != null && f.acodec != "none"
                val key = "${f.height}_${f.ext}"
                if (result.containsKey(key)) return@forEach
                val id = f.formatId!!
                result[key] = FormatOption(
                    id = if (hasAudio) "$id/best" else "$id+bestaudio/$id/best",
                    label = "${f.height}p" + (f.ext?.let { " · $it" } ?: ""),
                    ext = f.ext ?: "",
                    filesize = if (f.fileSize > 0) f.fileSize else f.fileSizeApproximate,
                    audioOnly = false
                )
            }

        result["audio"] = FormatOption(
            id = "bestaudio/best",
            label = "仅音频（转 MP3）",
            ext = "mp3",
            filesize = 0,
            audioOnly = true
        )
        return result.values.toList()
    }

    private val speedRegex = Regex("""at\s+([0-9.]+\s*[KMG]?iB/s)""")
    private val etaRegex = Regex("""ETA\s+([0-9:]+)""")

    /**
     * 执行下载，进度回调 (0..1, 速度文本, 剩余时间文本)。
     * 阻塞操作为主，调用方需放在 IO 线程。
     */
    suspend fun download(
        context: Context,
        task: DownloadTask,
        onProgress: (Float, String, String) -> Unit
    ): File? {
        if (task.selector == DirectDownloader.DIRECT_SELECTOR) {
            return withContext(Dispatchers.IO) {
                DirectDownloader.download(context, task, onProgress)
            }
        }
        // 重启后恢复的下载可能没有经过解析，必须先确保引擎就绪
        ensureReady(context)
        val dir = outputDir(context)
        val before = dir.listFiles()?.map { it.name }?.toHashSet() ?: HashSet()

        val request = YoutubeDLRequest(task.url)
            .addOption("--newline")
            .addOption("--no-mtime")
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--socket-timeout", "15")
            .addOption("-P", dir.absolutePath)
            .addOption("-o", "%(title).180B [%(id)s].%(ext)s")
            .addOption("-f", task.selector)
            .addOption("--continue")
            .addOption("--retries", 10)
            .addOption("--fragment-retries", 10)
            .addOption("--concurrent-fragments", 4)
        request.addDetectedHeaders(task.url)

        if (task.audioOnly) {
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
        } else {
            request.addOption("--merge-output-format", "mp4")
        }

        YoutubeDL.getInstance().execute(request, processId = task.id) { progress, eta, line ->
            val speed = line?.let { speedRegex.find(it)?.groupValues?.get(1) } ?: ""
            val etaText = when {
                eta > 0 -> formatDuration(eta)
                line != null -> etaRegex.find(line)?.groupValues?.get(1) ?: ""
                else -> ""
            }
            onProgress((progress / 100f).coerceIn(0f, 1f), speed, etaText)
        }

        return dir.listFiles()
            ?.filter {
                it.isFile &&
                    !it.name.endsWith(".part") &&
                    !it.name.endsWith(".ytdl") &&
                    it.name !in before
            }
            ?.maxByOrNull { it.length() }
            ?: dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.lastModified() }
    }

    fun outputDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "VidGrab").apply { mkdirs() }
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }
}
