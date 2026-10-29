package com.vidgrab.app.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * 直链下载兜底：yt-dlp 对某些 .mp4/.m4a 直链会报 Unsupported URL，
 * 这里直接用 HTTP 拉取，支持 Range 断点续传，并复用浏览器嗅探到的 Cookie/Referer/UA。
 */
object DirectDownloader {

    const val DIRECT_SELECTOR = "direct"

    private val cancelled = ConcurrentHashMap<String, Boolean>()

    private val mediaExtensions = setOf(
        "mp4", "webm", "mkv", "mov", "m4v", "flv",
        "m4a", "mp3", "aac", "ogg", "opus", "wav"
    )

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    fun isDirectMediaUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return mediaExtensions.any { path.endsWith(".$it") }
    }

    fun cancel(taskId: String) {
        cancelled[taskId] = true
    }

    fun download(
        context: Context,
        task: DownloadTask,
        onProgress: (Float, String, String) -> Unit
    ): File {
        cancelled.remove(task.id)
        val dir = YtDlpEngine.outputDir(context)
        val baseName = safeFileName(task.url, task.title)
        val part = File(dir, "$baseName.part")
        var existing = if (part.exists()) part.length() else 0L

        val connection = (URL(task.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", DEFAULT_UA)
            MediaSniffer.headersFor(task.url).forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
            }
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val code = connection.responseCode
            if (code == 416 && existing > 0) {
                // 服务器认为已经下载完整
                return finalizeFile(part, dir, baseName)
            }
            if (code !in 200..299) throw IOException("HTTP $code")

            val contentType = connection.contentType?.lowercase().orEmpty()
            if (contentType.startsWith("text/html") || contentType.contains("json")) {
                throw IOException("服务器返回的是网页而不是媒体文件（$contentType）")
            }

            val append = code == 206 && existing > 0
            if (!append && existing > 0) {
                // 服务器不支持 Range，只能从头开始
                existing = 0
                part.delete()
            }
            val total = if (append) existing + connection.contentLengthLong else connection.contentLengthLong

            val startedAt = System.currentTimeMillis()
            var received = existing
            var lastUpdate = 0L

            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled[task.id] == true) {
                            // 保留 .part，重试时可续传
                            throw CancellationException("canceled")
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        received += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 300) {
                            lastUpdate = now
                            val seconds = (now - startedAt) / 1000.0
                            val speed = if (seconds > 0) (received - existing) / seconds else 0.0
                            val progress = if (total > 0) received.toFloat() / total else 0f
                            val eta = if (total > 0 && speed > 0) ((total - received) / speed).toLong() else 0L
                            onProgress(progress.coerceIn(0f, 1f), formatSpeed(speed), formatEta(eta))
                        }
                    }
                }
            }
            onProgress(1f, "", "")
            return finalizeFile(part, dir, baseName)
        } finally {
            cancelled.remove(task.id)
            connection.disconnect()
        }
    }

    private fun finalizeFile(part: File, dir: File, baseName: String): File {
        var target = File(dir, baseName)
        var index = 1
        val base = baseName.substringBeforeLast('.', baseName)
        val ext = baseName.substringAfterLast('.', "")
        while (target.exists()) {
            target = File(dir, base + "_cp" + index + if (ext.isNotEmpty()) ".$ext" else "")
            index++
        }
        return if (part.renameTo(target)) target else part
    }

    private fun safeFileName(url: String, title: String): String {
        val raw = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull().orEmpty()
        var name = decoded
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .takeIf { it.isNotBlank() }
            ?: title.replace(Regex("""[\\/:*?"<>|]"""), "_").takeIf { it.isNotBlank() }
            ?: "video"
        var ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in mediaExtensions) {
            ext = "mp4"
            name = if (name.contains('.')) name.substringBeforeLast('.') + ".$ext" else "$name.$ext"
        }
        return name
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSecond
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return String.format("%.1f %s", value, units[index])
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }
}
