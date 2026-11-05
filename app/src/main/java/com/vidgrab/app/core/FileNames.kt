package com.vidgrab.app.core

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 标题 / 文件名清理：网页标题常带站点名、清晰度等后缀，直接落盘会成为
 * “标题 - 某某视频_高清在线观看.mp4” 这样的文件名。这里统一清洗。
 */
object FileNames {

    private val mediaExtensions = setOf(
        "mp4", "webm", "mkv", "mov", "m4v", "flv", "ts",
        "m4a", "mp3", "aac", "ogg", "opus", "wav"
    )

    private val illegalChars = Regex("""[\\/:*?"<>|]""")
    private val controlChars = Regex("""[\u0000-\u001F\u007F]""")

    /** “标题 - 哔哩哔哩”“标题_抖音” 这类站点后缀。 */
    private val siteSuffix = Regex(
        """[\s\-–—_|·:：]*[（(【\[]?\s*(哔哩哔哩|bilibili|抖音|douyin|快手|kuaishou|西瓜视频|腾讯视频|爱奇艺|iqiyi|优酷|youku|芒果tv|微博|weibo|youtube|搜狐视频|好看视频|acfun|网易云音乐|qq音乐)\s*[）)】\]]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** “高清”“完整版”“1080P” 这类标签后缀。 */
    private val qualitySuffix = Regex(
        """[\s\-–—_|·]*[（(【\[]?\s*(在线观看|免费观看|高清在线观看|在线播放|完整版|高清|超清|蓝光|1080p|720p|4k|hd)\s*[）)】\]]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val edgePunct = Regex("""^[\s\-–—_|·.。、,，]+|[\s\-–—_|·.。、,，]+$""")
    private val whitespace = Regex("""\s+""")

    /** 单个文件名的 UTF-8 字节上限；给目录路径和扩展名留余量，避免 ENAMETOOLONG。 */
    private const val MAX_NAME_BYTES = 80

    private val hexHash = Regex("""^[0-9a-fA-F]{16,}$""")
    private val tokenLike = Regex("""^[A-Za-z0-9_-]{32,}$""")

    fun clean(raw: String?): String {
        var text = controlChars.replace(raw.orEmpty(), " ")
        text = illegalChars.replace(text, "_")
        repeat(3) {
            text = siteSuffix.replace(text, "")
            text = qualitySuffix.replace(text, "")
        }
        text = edgePunct.replace(text, "")
        text = whitespace.replace(text, " ").trim()
        return truncateBytes(text, MAX_NAME_BYTES)
    }

    /**
     * 标题是视频哈希/随机 ID（如 `1ba0545f…`）：没有可读语义，
     * 直接当文件名还会超长，必须换成兜底名字。
     */
    fun looksLikeHash(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return false
        if (hexHash.matches(text)) return true
        if (!tokenLike.matches(text)) return false
        val digits = text.count { it.isDigit() }
        val letters = text.count { it.isLetter() }
        val upper = text.count { it.isUpperCase() }
        return digits >= 6 && letters >= 16 && upper >= 4
    }

    /** 标题只是网址或文件名（yt-dlp 没取到真实标题时的兜底）时返回 true。 */
    fun looksLikeUrl(title: String, url: String? = null): Boolean {
        val text = title.trim()
        if (text.isEmpty()) return true
        if (text.startsWith("http://") || text.startsWith("https://")) return true

        val segment = url?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('/')
        if (!segment.isNullOrBlank()) {
            val stem = segment.substringBeforeLast('.')
            // yt-dlp 的通用解析器常把文件名（sample.mp4 -> sample）当作标题
            listOf(segment, stem).filter { it.isNotBlank() }.forEach { candidate ->
                if (text == candidate || text == decode(candidate)) return true
            }
        }
        if (text.any { it.code > 127 }) return false
        val ext = text.substringAfterLast('.', "").lowercase()
        return ext in mediaExtensions && text.none { it.isWhitespace() }
    }

    /** 优先用清洗后的真实标题；取不到时退回到“页面标题 + 时间”。 */
    fun resolveTitle(parsed: String?, url: String?, hint: String? = null): String {
        val cleaned = clean(parsed)
        val default = clean(hint)
        return when {
            cleaned.isNotBlank() && !looksLikeUrl(cleaned, url) && !looksLikeHash(cleaned) -> cleaned
            default.isNotBlank() && !looksLikeHash(default) -> "$default ${timestamp()}"
            else -> fallbackTitle(url)
        }
    }

    /** 落盘用的最终标题：网址/哈希都不适合当文件名，一律退回「域名 + 时间」。 */
    fun safeTitle(title: String?, url: String?): String {
        val cleaned = clean(title)
        return if (cleaned.isNotBlank() && !looksLikeUrl(cleaned, url) && !looksLikeHash(cleaned)) {
            cleaned
        } else {
            fallbackTitle(url)
        }
    }

    private fun fallbackTitle(url: String?): String {
        val host = runCatching { URI(url.orEmpty()).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { host -> host.isNotBlank() }
        return if (host != null) "$host ${timestamp()}" else "video ${timestamp()}"
    }

    /** 直链下载的文件名：标题可用就用标题，否则退回 URL 最后一段。 */
    fun directFileName(title: String?, url: String?): String {
        val urlName = url
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringAfterLast('/')
            ?.let(::decode)
            .orEmpty()
        val cleanedTitle = clean(title)
        val urlStem = clean(urlName).substringBeforeLast('.')
        val base = when {
            cleanedTitle.isNotBlank() && !looksLikeUrl(cleanedTitle, url) && !looksLikeHash(cleanedTitle) -> cleanedTitle
            urlStem.isNotBlank() && !looksLikeHash(urlStem) -> urlStem
            else -> fallbackTitle(url)
        }.ifBlank { "video" }

        var ext = urlName.substringAfterLast('.', "").lowercase()
        if (ext !in mediaExtensions) {
            ext = clean(title).substringAfterLast('.', "").lowercase()
        }
        if (ext !in mediaExtensions) ext = "mp4"
        return "$base.$ext"
    }

    /** 在 dir 下找一个不冲突的文件名；current 是当前文件本身时直接返回。 */
    fun uniqueFile(dir: File, base: String, ext: String, current: File? = null): File {
        val suffix = if (ext.isBlank()) "" else ".$ext"
        var candidate = File(dir, "$base$suffix")
        if (current != null && candidate.absolutePath == current.absolutePath) return candidate
        var index = 2
        // 候选正好是当前文件时不重命名，避免 (2) 变 (3) 这类连锁改名
        while (candidate.exists() && candidate.absolutePath != current?.absolutePath) {
            candidate = File(dir, "$base ($index)$suffix")
            index++
        }
        return candidate
    }

    fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    /** 按 UTF-8 字节截断，避免中文标题按字符截断后仍然超过文件系统上限。 */
    private fun truncateBytes(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var end = text.length
        while (end > 0 && text.take(end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
        return text.take(end).trim()
    }
}
