package com.vidgrab.app.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * 直链下载兜底：yt-dlp 对某些 .mp4/.m4a 直链会报 Unsupported URL，
 * 这里直接用 HTTP 拉取，支持 Range 断点续传；支持 Range 的大文件走 4 连接分段，
 * 并复用浏览器 Cookie/Referer/UA。
 */
object DirectDownloader {

    const val DIRECT_SELECTOR = "direct"

    private const val MULTI_THRESHOLD = 8L * 1024 * 1024
    private const val MULTI_SEGMENTS = 4

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
        onProgress: (Float, String, String) -> Unit,
        onFile: (String) -> Unit = {}
    ): File {
        cancelled.remove(task.id)
        val dir = YtDlpEngine.outputDir(context)
        val baseName = FileNames.directFileName(task.title, task.url)
        val part = File(dir, "$baseName.part")
        // 取消或删除任务时，据此找到未完成的 .part
        onFile(part.absolutePath)
        var existing = if (part.exists()) part.length() else 0L

        val connection = openConnection(task, if (existing > 0) "bytes=$existing-" else null)

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

            val total = if (code == 206 && existing > 0) {
                existing + connection.contentLengthLong
            } else {
                connection.contentLengthLong
            }

            // 全新下载 + 服务器支持 Range + 大文件：4 连接分段并发
            if (existing == 0L && code == 200 && total >= MULTI_THRESHOLD &&
                connection.getHeaderField("Accept-Ranges")?.contains("bytes", true) == true
            ) {
                connection.disconnect()
                return multiDownload(task, dir, baseName, part, total, onProgress)
            }

            val append = code == 206 && existing > 0
            if (!append && existing > 0) {
                // 服务器不支持 Range，只能从头开始
                existing = 0
                part.delete()
            }

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
                        throttle(startedAt, received)

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

    private fun openConnection(task: DownloadTask, range: String?): HttpURLConnection =
        (URL(task.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", DEFAULT_UA)
            CookieStore.headersFor(task.url).forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) setRequestProperty(name, value)
            }
            if (!range.isNullOrBlank()) setRequestProperty("Range", range)
        }

    /**
     * 分段并发：写同一个 .part 的不同偏移。中断时删除临时文件（分段文件无法安全续传），
     * 下次从头下载；完成前校验总长度，避免把残缺文件当成品。
     */
    private fun multiDownload(
        task: DownloadTask,
        dir: File,
        baseName: String,
        part: File,
        total: Long,
        onProgress: (Float, String, String) -> Unit
    ): File {
        if (part.exists()) part.delete()
        RandomAccessFile(part, "rw").use { it.setLength(total) }

        val chunk = (total + MULTI_SEGMENTS - 1) / MULTI_SEGMENTS
        val pool = Executors.newFixedThreadPool(MULTI_SEGMENTS)
        val received = AtomicLong(0)
        val failure = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(MULTI_SEGMENTS)
        val startedAt = System.currentTimeMillis()
        val lastReport = AtomicLong(0)

        repeat(MULTI_SEGMENTS) { index ->
            pool.execute {
                try {
                    val start = index * chunk
                    val end = min(total - 1, start + chunk - 1)
                    if (start >= total) return@execute
                    val connection = openConnection(task, "bytes=$start-$end")
                    try {
                        if (connection.responseCode !in listOf(200, 206)) {
                            throw IOException("HTTP ${connection.responseCode}")
                        }
                        if (connection.responseCode == 200 && start > 0) {
                            throw IOException("服务器忽略了 Range 请求，改用单连接")
                        }
                        if (connection.responseCode == 206 && start == 0L) {
                            // 第一段正常；继续
                        }
                        RandomAccessFile(part, "rw").use { file ->
                            connection.inputStream.use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var position = start
                                while (position <= end) {
                                    if (cancelled[task.id] == true) {
                                        throw CancellationException("canceled")
                                    }
                                    val read = input.read(buffer, 0, min(buffer.size.toLong(), end - position + 1).toInt())
                                    if (read <= 0) break
                                    file.seek(position)
                                    file.write(buffer, 0, read)
                                    position += read
                                    val nowReceived = received.addAndGet(read.toLong())
                                    throttle(startedAt, nowReceived)
                                    val now = System.currentTimeMillis()
                                    if (now - lastReport.get() >= 300) {
                                        lastReport.set(now)
                                        reportMulti(received.get(), total, startedAt, onProgress)
                                    }
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        pool.shutdownNow()

        failure.get()?.let { error ->
            part.delete()
            throw if (error is CancellationException) error
            else IOException("多线程下载失败：${error.message ?: error.javaClass.simpleName}")
        }
        if (received.get() != total) {
            part.delete()
            throw IOException("多线程下载不完整（${received.get()} / $total）")
        }
        reportMulti(received.get(), total, startedAt, onProgress)
        onProgress(1f, "", "")
        return finalizeFile(part, dir, baseName)
    }

    private fun reportMulti(
        received: Long,
        total: Long,
        startedAt: Long,
        onProgress: (Float, String, String) -> Unit
    ) {
        val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
        val speed = if (seconds > 0) received / seconds else 0.0
        val progress = if (total > 0) received.toFloat() / total else 0f
        val eta = if (total > 0 && speed > 0) ((total - received) / speed).toLong() else 0L
        onProgress(progress.coerceIn(0f, 1f), formatSpeed(speed), formatEta(eta))
    }

    /** 平均限速：按已下载字节应耗时与真实耗时比较，超出时短暂 sleep。 */
    private fun throttle(startedAt: Long, received: Long) {
        val limit = DownloadSettings.speedLimitKbps
        if (limit <= 0) return
        val expectedMs = received * 1000.0 / (limit * 1024.0)
        val elapsedMs = (System.currentTimeMillis() - startedAt).toDouble()
        val sleep = (expectedMs - elapsedMs).toLong()
        if (sleep > 0) {
            runCatching { Thread.sleep(sleep.coerceAtMost(800)) }
        }
    }

    private fun finalizeFile(part: File, dir: File, baseName: String): File {
        val base = baseName.substringBeforeLast('.', baseName)
        val ext = baseName.substringAfterLast('.', "")
        val target = FileNames.uniqueFile(dir, base, ext, part)
        if (part.renameTo(target)) return target
        // 部分文件系统不允许 rename，退回复制 + 删除；都失败就报错，
        // 绝不能把 .part 当成成品返回（否则会被发布到公共下载目录）。
        return runCatching {
            part.copyTo(target, overwrite = false)
            part.delete()
            target
        }.getOrElse { error ->
            throw IOException("无法完成下载文件保存：${error.message ?: "重命名失败"}")
        }
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
