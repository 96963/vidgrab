package com.vidgrab.app.core

import java.io.File
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

    fun clean(raw: String?): String {
        var text = controlChars.replace(raw.orEmpty(), " ")
        text = illegalChars.replace(text, "_")
        repeat(3) {
            text = siteSuffix.replace(text, "")
            text = qualitySuffix.replace(text, "")
        }
        text = edgePunct.replace(text, "")
        text = whitespace.replace(text, " ").trim()
        return text.take(120)
    }

    /** 标题只是网址或文件名（yt-dlp 没取到真实标题时的兜底）时返回 true。 */
    fun looksLikeUrl(title: String, url: String? = null): Boolean {
        val text = title.trim()
        if (text.isEmpty()) return true
        if (text.startsWith("http://") || text.startsWith("https://")) return true

        val segment = url?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('/')
        if (!segment.isNullOrBlank()) {
            if (text == segment) return true
            if (text == decode(segment)) return true
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
            cleaned.isNotBlank() && !looksLikeUrl(cleaned, url) -> cleaned
            default.isNotBlank() -> "$default ${timestamp()}"
            cleaned.isNotBlank() -> cleaned
            else -> "未命名视频"
        }
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
        val base = when {
            cleanedTitle.isNotBlank() && !looksLikeUrl(cleanedTitle, url) -> cleanedTitle
            urlName.isNotBlank() -> clean(urlName).substringBeforeLast('.')
            else -> ""
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
        while (candidate.exists()) {
            candidate = File(dir, "$base ($index)$suffix")
            index++
        }
        return candidate
    }

    fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
