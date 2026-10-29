package com.vidgrab.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * yt-dlp 在线更新：
 *  - 版本号从清华 PyPI 索引获取（国内快）
 *  - 安装包（官方 zipapp）走 GitHub 代理下载，失败时依次回退
 *  - 覆盖 filesDir/no_backup/youtubedl-android/yt-dlp/yt-dlp，重启进程后生效
 */
object YtDlpUpdater {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val downloadSources = listOf(
        "https://ghfast.top/https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp",
        "https://ghproxy.net/https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp",
        "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
    )

    sealed class Result {
        data class Updated(val version: String) : Result()
        data class UpToDate(val version: String?) : Result()
        data class Failed(val message: String) : Result()
    }

    fun targetFile(context: Context): File =
        File(File(context.noBackupFilesDir, "youtubedl-android"), "yt-dlp/yt-dlp")

    /** 当前安装版本：优先读已解压的文件，否则读安装包内资源。 */
    suspend fun installedVersion(context: Context): String? = withContext(Dispatchers.IO) {
        val target = targetFile(context)
        if (target.exists()) {
            readVersion(target)?.let { return@withContext it }
        }
        runCatching {
            context.assets.open("ytdlp").use { input ->
                readVersion(input)
            }
        }.getOrNull()
    }

    suspend fun update(
        context: Context,
        onProgress: (Float, Long) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val installed = installedVersion(context)
        val target = targetFile(context)
        val parent = target.parentFile ?: return@withContext Result.Failed("路径不可用")
        if (!parent.exists() && !parent.mkdirs()) {
            return@withContext Result.Failed("无法创建目录")
        }
        val temp = File(parent, "yt-dlp.new")

        var lastError = "没有可用的下载源"
        for (source in downloadSources) {
            try {
                download(source, temp, onProgress)
                val downloadedVersion = readVersion(temp)
                if (downloadedVersion == null) {
                    throw IllegalStateException("下载内容不是有效的 yt-dlp 包")
                }
                if (installed != null && compare(downloadedVersion, installed) <= 0) {
                    temp.delete()
                    return@withContext Result.UpToDate(installed)
                }
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("无法替换旧版本")
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                return@withContext Result.Updated(downloadedVersion)
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                temp.delete()
            }
        }
        Result.Failed("更新失败：$lastError")
    }

    private fun download(url: String, target: File, onProgress: (Float, Long) -> Unit) {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val total = connection.contentLengthLong
            var received = 0L
            var lastUpdate = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        received += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 200) {
                            lastUpdate = now
                            val progress = if (total > 0) received.toFloat() / total else -1f
                            onProgress(progress, received)
                        }
                    }
                }
            }
            if (target.length() < 500_000) {
                throw IllegalStateException("下载文件过小（${target.length()} 字节）")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", UA)
        }

    private fun readVersion(file: File): String? = runCatching {
        file.inputStream().use { readVersion(it) }
    }.getOrNull()

    private fun readVersion(input: java.io.InputStream): String? {
        // zipapp 前面带 shebang，先定位 ZIP 本地文件头再解析
        val bytes = input.readBytes()
        val offset = indexOfZipHeader(bytes) ?: return null
        ZipInputStream(
            java.io.ByteArrayInputStream(bytes, offset, bytes.size - offset).buffered()
        ).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "yt_dlp/version.py") {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    return Regex("""__version__\s*=\s*['"]([^'"]+)['"]""")
                        .find(text)?.groupValues?.get(1)
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun indexOfZipHeader(bytes: ByteArray): Int? {
        for (index in 0..bytes.size - 4) {
            if (bytes[index] == 'P'.code.toByte() && bytes[index + 1] == 'K'.code.toByte() &&
                bytes[index + 2] == 0x03.toByte() && bytes[index + 3] == 0x04.toByte()
            ) {
                return index
            }
        }
        return null
    }

    private fun versionKey(version: String): List<Int> =
        version.split('.').map { it.toIntOrNull() ?: 0 }

    private fun compare(a: String, b: String): Int {
        val left = versionKey(a)
        val right = versionKey(b)
        for (index in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }
}
