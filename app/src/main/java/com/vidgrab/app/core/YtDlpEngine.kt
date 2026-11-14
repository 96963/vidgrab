package com.vidgrab.app.core

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import com.yausername.aria2c.Aria2c
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
    @Volatile
    private var aria2cReady = false

    val isReady: Boolean get() = initialized

    suspend fun ensureReady(context: Context) {
        if (initialized) return
        withContext(Dispatchers.IO) {
            initLock.withLock {
                if (!initialized) {
                    // 安装包换了新版 yt-dlp 时，先把已解压的旧版替换掉
                    runCatching { YtDlpUpdater.syncBundled(context) }
                    YoutubeDL.getInstance().init(context.applicationContext)
                    FFmpeg.getInstance().init(context.applicationContext)
                    // aria2c 解压失败不影响内置下载器
                    aria2cReady = runCatching {
                        Aria2c.getInstance().init(context.applicationContext)
                        true
                    }.getOrDefault(false)
                    initialized = true
                }
            }
        }
    }

    /** 播放列表展开：--flat-playlist 只取条目，不解析每个视频的格式。 */
    suspend fun fetchPlaylistEntries(context: Context, url: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            ensureReady(context)
            val request = YoutubeDLRequest(url)
                .addOption("--flat-playlist")
                .addOption("--dump-json")
                .addOption("--no-warnings")
                .addOption("--socket-timeout", "15")
            request.addDetectedHeaders(url)
            val response = YoutubeDL.getInstance().execute(request)
            val mapper = ObjectMapper()
            val roots = response.out.lineSequence()
                .mapNotNull { line ->
                    runCatching { mapper.readTree(line) }.getOrNull()
                }
                .toList()
            val first = roots.firstOrNull() ?: return@withContext emptyList()
            if (first.path("_type").asText("") != "playlist") return@withContext emptyList()
            val entries = first.path("entries")
            if (!entries.isArray) return@withContext emptyList()
            entries.mapIndexedNotNull { index, entry ->
                val rawUrl = entry.path("webpage_url").asText("")
                    .ifBlank { entry.path("url").asText("") }
                val target = when {
                    rawUrl.startsWith("http") -> rawUrl
                    else -> entry.path("id").asText("").takeIf { it.isNotBlank() }
                        ?.let { "https://www.youtube.com/watch?v=$it" }
                } ?: return@mapIndexedNotNull null
                val title = entry.path("title").asText("").ifBlank { "视频 ${index + 1}" }
                target to title
            }.take(200)
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
                    title = FileNames.resolveTitle(
                        url.substringBefore('?').substringAfterLast('/'),
                        url
                    ),
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
            // 网页标题常带站点名/清晰度后缀，也可能干脆是 URL，统一清洗
            title = FileNames.resolveTitle(
                info.title ?: info.altTitle ?: info.fulltitle ?: info.track,
                info.webpageUrl ?: url
            ),
            uploader = info.uploader,
            durationSeconds = info.duration,
            thumbnail = info.thumbnail ?: info.thumbnails?.lastOrNull()?.url,
            webpageUrl = info.webpageUrl ?: url,
            formats = buildFormats(info.formats ?: emptyList())
        )
    }

    /** HLS/DASH 分片由 yt-dlp 自己调度，交给 aria2c 反而可能变慢或出错。 */
    private fun canUseAria2c(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return !path.endsWith(".m3u8") && !path.endsWith(".mpd")
    }

    /** 浏览器 Cookie（按域名复用）+ 嗅探到的 Referer/UA，直链和 yt-dlp 都带上。 */
    private fun YoutubeDLRequest.addDetectedHeaders(url: String): YoutubeDLRequest {
        CookieStore.headersFor(url).forEach { (name, value) ->
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

        result["videoOnly"] = FormatOption(
            id = "bestvideo/best",
            label = "仅视频轨（无音频，适合剪辑）",
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
    // aria2c 的汇总行： [#gid 1.2MiB/10MiB(12%) CN:5 DL:1.2MiB ETA:6s]
    private val ariaSpeedRegex = Regex("""DL:([0-9.]+\s*[KMG]?iB)""")
    private val etaRegex = Regex("""ETA\s+([0-9:]+)""")

    /**
     * 执行下载，进度回调 (0..1, 速度文本, 剩余时间文本)。
     * 阻塞操作为主，调用方需放在 IO 线程。
     */
    suspend fun download(
        context: Context,
        task: DownloadTask,
        onProgress: (Float, String, String) -> Unit,
        onFile: (String) -> Unit = {}
    ): File? {
        if (task.selector == DirectDownloader.DIRECT_SELECTOR) {
            return withContext(Dispatchers.IO) {
                DirectDownloader.download(context, task, onProgress, onFile)
            }
        }
        // 重启后恢复的下载可能没有经过解析，必须先确保引擎就绪
        ensureReady(context)
        val dir = outputDir(context)
        val before = dir.listFiles()?.map { it.name }?.toHashSet() ?: HashSet()
        val useAria2c = DownloadSettings.aria2c && aria2cReady && canUseAria2c(task.url)
        val produced = try {
            runDownload(task, dir, before, useAria2c, onProgress, onFile)
        } catch (e: YoutubeDL.CanceledException) {
            throw e
        } catch (e: Throwable) {
            // aria2c 不可用或站点不接受时，退回内置下载器重试一次
            if (!useAria2c) throw e
            runDownload(task, dir, before, false, onProgress, onFile)
        }
        val result = renameToTitle(produced, task.title, task.url)
        publishExtras(context, dir, before, result)
        return result
    }

    /** 字幕/封面等附加文件单独发布到公共目录；主视频仍由下载管理器发布。 */
    private fun publishExtras(context: Context, dir: File, before: Set<String>, main: File?) {
        val extraExtensions = setOf("srt", "vtt", "ass", "ssa", "jpg", "jpeg", "png", "webp")
        val mainPath = main?.absolutePath
        dir.listFiles()
            ?.filter { it.isFile && it.name !in before && it.absolutePath != mainPath }
            ?.filter { it.extension.lowercase() in extraExtensions }
            ?.forEach { extra ->
                PublicStorage.publish(context, extra)
            }
    }

    private fun runDownload(
        task: DownloadTask,
        dir: File,
        before: Set<String>,
        useAria2c: Boolean,
        onProgress: (Float, String, String) -> Unit,
        onFile: (String) -> Unit
    ): File? {
        val request = YoutubeDLRequest(task.url)
            .addOption("--newline")
            .addOption("--no-mtime")
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--socket-timeout", "15")
            .addOption("-P", dir.absolutePath)
            // title 和 id 都要限长：部分站点的 id 是长哈希，不截断会触发 ENAMETOOLONG
            .addOption("-o", "%(title).80B [%(id).24B].%(ext)s")
            .addOption("-f", task.selector)
            .addOption("--continue")
            .addOption("--retries", 10)
            .addOption("--fragment-retries", 10)
            .addOption("--concurrent-fragments", DownloadSettings.fragments)
        if (DownloadSettings.speedLimitKbps > 0) {
            request.addOption("--limit-rate", "${DownloadSettings.speedLimitKbps}K")
        }
        if (task.writeSubs) {
            request.addOption("--write-subs")
            request.addOption("--write-auto-subs")
            request.addOption("--sub-langs", "zh.*,en.*")
            request.addOption("--convert-subs", "srt")
        }
        if (task.writeThumbnail) {
            request.addOption("--write-thumbnail")
            request.addOption("--convert-thumbnails", "jpg")
        }
        if (useAria2c) {
            request.addOption("--downloader", "libaria2c.so")
        }
        request.addDetectedHeaders(task.url)

        if (task.audioOnly) {
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
        } else {
            request.addOption("--merge-output-format", "mp4")
        }

        YoutubeDL.getInstance().execute(request, processId = task.id) { progress, eta, line ->
            // 记下 yt-dlp 实际写入的路径，取消/删除时可据此清掉 .part
            if (!line.isNullOrBlank()) {
                destinationOf(line)?.let(onFile)
            }
            val speed = line?.let {
                speedRegex.find(it)?.groupValues?.get(1)
                    ?: ariaSpeedRegex.find(it)?.groupValues?.get(1)?.let { s -> "$s/s" }
            } ?: ""
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

    /** yt-dlp 输出里出现的目标文件路径。 */
    private val destinationPatterns = listOf(
        Regex("""^\[download\] Destination: (.+)$"""),
        Regex("""^\[download\] (.+) has already been downloaded$"""),
        Regex("""^\[Merger\] Merging formats into "(.+)"$"""),
        Regex("""^\[ExtractAudio\] Destination: (.+)$"""),
        Regex("""^\[FixupM3u8\] .*? to "(.+)"$"""),
        Regex("""^\[FixupM4a\] .*? to "(.+)"$"""),
        Regex("""^\[MoveFiles\] Moving file ".+?" to "(.+)"$""")
    )

    private fun destinationOf(line: String): String? {
        val text = line.trim()
        destinationPatterns.forEach { pattern ->
            pattern.find(text)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** 下载完成后按清洗过的标题重命名，避免把网页标题原样当文件名。 */
    private fun renameToTitle(file: File?, title: String, url: String?): File? {
        if (file == null) return null
        val base = FileNames.safeTitle(title, url)
        if (base.isBlank()) return file
        val parent = file.parentFile ?: return file
        val target = FileNames.uniqueFile(parent, base, file.extension, file)
        if (target == file) return file
        return moveFile(file, target)
    }

    /** renameTo 在部分文件系统上会失败，退回复制 + 删除。 */
    private fun moveFile(file: File, target: File): File {
        if (runCatching { file.renameTo(target) }.getOrDefault(false)) return target
        return runCatching {
            file.copyTo(target, overwrite = false)
            file.delete()
            target
        }.getOrDefault(file)
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
