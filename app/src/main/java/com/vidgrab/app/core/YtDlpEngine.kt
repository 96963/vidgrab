package com.vidgrab.app.core

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
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
import java.net.URI

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

    /** B 站移动页在 yt-dlp 下容易退回 generic；统一改用桌面规范地址。 */
    private fun normalizeUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase() ?: return url
        val path = uri.path ?: return url
        if ((host == "m.bilibili.com" || host == "bilibili.com") &&
            path.startsWith("/video/")
        ) {
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return "https://www.bilibili.com$path$query"
        }
        return url
    }

    /** 播放列表展开：--flat-playlist 只取条目，不解析每个视频的格式。 */
    suspend fun fetchPlaylistEntries(context: Context, url: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            DiagLog.w("Flat", "ENTER url=$url")
            ensureReady(context)
            DiagLog.w("Flat", "READY url=$url")
            val target = normalizeUrl(url)
            val request = YoutubeDLRequest(target)
                .addOption("--flat-playlist")
                .addOption("--dump-json")
                .addOption("--no-warnings")
                .addOption("--socket-timeout", "15")
                .addOption("--extractor-retries", 10)
                .addOption("--retries", 10)
            request.addDetectedHeaders(target)
            // 弱网下 B 站合集 API 一次执行常只成功一半甚至失败，最多重试 3 次，
            // 直到解析出可用的条目为止。
            val mapper = ObjectMapper()
            fun parse(out: String): List<Pair<String, String>> {
                val roots = out.lineSequence()
                    .mapNotNull { line ->
                        runCatching { mapper.readTree(line) }.getOrNull()
                    }
                    .toList()
                if (roots.isEmpty()) {
                    DiagLog.w("Flat", "parse roots=0")
                    return emptyList()
                }
                // 容器式（YouTube 等）：第一行 _type=playlist，entries 内嵌
                val first = roots.first()
                if (first.path("_type").asText("") == "playlist") {
                    val entries = first.path("entries")
                    if (!entries.isArray) return emptyList()
                    return entries.mapIndexedNotNull { index, entry ->
                        entryToTarget(entry, index)
                    }.take(200)
                }
                // 流式（B 站合集/系列等）：每行一个条目，没有 playlist 容器行。
                // 宽容解析：逐行尝试映射成视频条目，能映射就收，不再要求每行都带
                // playlist_id/n_entries 等元信息（弱网/版本差异下这些字段可能缺失）。
                val mapped = roots.mapIndexedNotNull { index, entry ->
                    entryToTarget(entry, index)
                }
                DiagLog.w(
                    "Flat",
                    "parse roots=${roots.size} mapped=${mapped.size} lens=${roots.map { it.toString().length }}"
                )
                if (mapped.size > 1) return mapped.take(200)
                // 只有一行说明是单个视频（不是播放列表），交给 fetchInfo 拿封面/格式
                return emptyList()
            }
            for (attempt in 1..3) {
                val t0 = System.currentTimeMillis()
                val out = try {
                    val response = YoutubeDL.getInstance().execute(request)
                    response.out ?: ""
                } catch (e: Throwable) {
                    DiagLog.w(
                        "Flat",
                        "EXC attempt=$attempt url=$url cls=${e.javaClass.simpleName} msg=${e.message} cost=${System.currentTimeMillis() - t0}ms"
                    )
                    Thread.sleep(1500L)
                    continue
                }
                DiagLog.w(
                    "Flat",
                    "attempt=$attempt cost=${System.currentTimeMillis() - t0}ms len=${out.length} lines=${out.lines().size} head=${out.take(800).replace("\n", "\\n")}"
                )
                val entries = parse(out)
                if (entries.isNotEmpty()) return@withContext entries
                if (attempt < 3) {
                    android.util.Log.w("VidGrab", "flat-playlist attempt $attempt failed, retrying")
                    Thread.sleep(2000L * attempt)
                }
            }
            emptyList()
        }

    private fun entryToTarget(entry: JsonNode, index: Int): Pair<String, String>? {
        val rawUrl = entry.path("webpage_url").asText("")
            .ifBlank { entry.path("url").asText("") }
        val target = when {
            rawUrl.startsWith("http") -> rawUrl
            else -> entry.path("id").asText("").takeIf { it.isNotBlank() }
                ?.let { "https://www.youtube.com/watch?v=$it" }
        } ?: return null
        val title = entry.path("title").asText("").ifBlank { "视频 ${index + 1}" }
        return target to title
    }

    /** 解析链接，返回标题、缩略图和可选格式列表。 */
    suspend fun fetchInfo(context: Context, url: String): MediaInfo = withContext(Dispatchers.IO) {
        ensureReady(context)
        val target = normalizeUrl(url)
        val request = YoutubeDLRequest(target)
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--socket-timeout", "15")
        request.addDetectedHeaders(target)
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
            webpageUrl = normalizeUrl(info.webpageUrl ?: url),
            formats = buildFormats(info.formats ?: emptyList())
        )
    }

    /** HLS/DASH 分片由 yt-dlp 自己调度，交给 aria2c 反而可能变慢或出错。 */
    private fun canUseAria2c(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return !path.endsWith(".m3u8") && !path.endsWith(".mpd")
    }

    /** 浏览器 Cookie（按域名复用）+ 嗅探到的 Referer/UA，直链和 yt-dlp 都带上。
     *  yt-dlp 不注入 App 的移动 UA，避免 B 站等站点退回 generic 解析。
     */
    private fun YoutubeDLRequest.addDetectedHeaders(url: String): YoutubeDLRequest {
        CookieStore.headersFor(url, includeDefaultUserAgent = false).forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                addOption("--add-header", "$name: $value")
            }
        }
        return this
    }

    /** 默认格式：优先 H.264（绝大多数机型和系统播放器都能硬解；AV1 无硬解时会严重丢帧）。 */
    /** HLS（m3u8）没有分轨可挑，直接取最佳流；剧集整批下载用它。 */
    const val HLS_SELECTOR = "best"

    const val DEFAULT_SELECTOR =
        "bestvideo[vcodec^=avc1]+bestaudio/bestvideo[vcodec^=h264]+bestaudio/bestvideo*+bestaudio/best"

    private fun buildFormats(formats: List<VideoFormat>): List<FormatOption> {
        val result = LinkedHashMap<String, FormatOption>()

        result["best"] = FormatOption(
            id = DEFAULT_SELECTOR,
            label = "最佳画质（自动合并，优先 H.264）",
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
        val target = normalizeUrl(task.url)
        val useAria2c = DownloadSettings.aria2c && aria2cReady && canUseAria2c(target)
        val produced = try {
            runDownload(task, target, dir, before, useAria2c, onProgress, onFile)
        } catch (e: YoutubeDL.CanceledException) {
            throw e
        } catch (e: Throwable) {
            // aria2c 不可用或站点不接受时，退回内置下载器重试一次
            if (!useAria2c) throw e
            runDownload(task, target, dir, before, false, onProgress, onFile)
        }
        val result = renameToTitle(produced, task.title, task.url)
        publishExtras(context, dir, before, result, task)
        return result
    }

    /**
     * 字幕/封面等附加文件单独发布到公共目录；主视频仍由下载管理器发布。
     *
     * 归属判定：只看「本次新增」会把「重试前就已经下好的封面」漏掉（上次发布前失败），
     * 所以再按「与主视频同 basename」和「与任务标题同 basename」认领一次。
     * 幂等：公共目录已有同名文件时不再重复发布，并删掉私有副本。
     */
    private fun publishExtras(
        context: Context,
        dir: File,
        before: Set<String>,
        main: File?,
        task: DownloadTask
    ) {
        val extraExtensions = setOf("srt", "vtt", "ass", "ssa", "jpg", "jpeg", "png", "webp")
        val mainPath = main?.absolutePath
        val mainBase = main?.nameWithoutExtension
        val titleBase = FileNames.safeTitle(task.title, task.url)
            .takeIf { it.isNotBlank() && !FileNames.isPlaceholder(it) }
        dir.listFiles()
            ?.filter { it.isFile && it.absolutePath != mainPath }
            ?.filter { it.extension.lowercase() in extraExtensions }
            ?.filter { extra ->
                extra.name !in before ||
                    (mainBase != null && extra.nameWithoutExtension == mainBase) ||
                    (titleBase != null && extra.nameWithoutExtension == titleBase)
            }
            ?.forEach { extra ->
                if (PublicStorage.hasFileNamed(context, extra.name)) {
                    // 公共目录已有同名附件，删掉私有副本即可，避免重试后封面翻倍
                    runCatching { extra.delete() }
                } else {
                    PublicStorage.publish(context, extra)
                }
            }
    }

    private fun runDownload(
        task: DownloadTask,
        url: String,
        dir: File,
        before: Set<String>,
        useAria2c: Boolean,
        onProgress: (Float, String, String) -> Unit,
        onFile: (String) -> Unit
    ): File? {
        val request = YoutubeDLRequest(url)
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
        request.addDetectedHeaders(url)

        if (task.audioOnly) {
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
        } else {
            request.addOption("--merge-output-format", "mp4")
            // B 站分轨视频（fMP4 视频轨 + m4a 音轨）直接 -c copy 合并后可能带负/偏移
            // 时间戳和 edit list，ExoPlayer 播放会周期性卡顿；这里把时间戳归零并前移 moov。
            request.addOption(
                "--postprocessor-args",
                "Merger:-avoid_negative_ts make_zero -movflags +faststart"
            )
        }

        val response = YoutubeDL.getInstance().execute(request, processId = task.id) { progress, eta, line ->
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
                else -> line?.let { etaRegex.find(it)?.groupValues?.get(1) } ?: ""
            }
            onProgress((progress / 100f).coerceIn(0f, 1f), speed, etaText)
        }
        // 合并（分轨 audio/video）失败时任务仍可能返回成功，留下无音轨的视频，
        // 这里把 yt-dlp 的错误输出记下来用于定位。
        DiagLog.w(
            "Dl",
            "done title=${task.title.take(30)} exit=${response.exitCode} errTail=${response.err?.takeLast(600)?.replace("\n", "\\n")}"
        )
        DiagLog.w(
            "Dl",
            "files=" + (dir.listFiles()?.filter { it.name !in before && !it.name.endsWith(".part") }?.joinToString("|") { "${it.name}:${it.length()}" })
        )

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
        // 列表展开入队时标题是「视频 N」占位名，保留 yt-dlp 生成的真实标题文件名
        if (FileNames.isPlaceholder(title)) return file
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
