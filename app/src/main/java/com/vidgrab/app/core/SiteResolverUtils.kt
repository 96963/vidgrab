package com.vidgrab.app.core

import kotlin.text.RegexOption
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 站点解析器共享工具函数。
 */
object SiteResolverUtils {

    val tag = Regex("""<[^>]+>""")
    val h1Title = Regex(
        """<h1[^>]*>(.*?)</h1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    val ogTitle = Regex(
        """<meta[^>]+property="og:title"[^>]+content="([^"]+)"""",
        RegexOption.IGNORE_CASE
    )
    val docTitle = Regex(
        """<title[^>]*>(.*?)</title>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    val mediaUrl = Regex(
        """https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )
    val jsonUrl = Regex(
        """["']url["']\s*:\s*["']([^"']+?(?:m3u8|mp4)[^"']*?)["']""",
        RegexOption.IGNORE_CASE
    )
    val iframe = Regex("""<(?:iframe|frame)[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)

    /**
     * 清理 HTML 文本：去标签、解码实体、归一化空白。
     */
    fun cleanText(raw: String): String {
        val text = unescape(tag.replace(raw, " "))
            .replace(' ', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        return text.trim('《', '》', ' ', '　')
    }

    /** 将相对 URL 转为绝对 URL。 */
    fun absolute(base: String, href: String): String {
        if (href.startsWith("http")) return href
        val origin = runCatching {
            val uri = URI(base)
            "${uri.scheme}://${uri.host}" +
                (uri.port.takeIf { it > 0 && it != 80 && it != 443 }?.let { ":$it" } ?: "")
        }.getOrNull().orEmpty()
        return when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> origin + href
            else -> base.substringBeforeLast('/') + "/" + href
        }
    }

    /** HTML 实体解码。 */
    fun unescape(raw: String): String = raw
        .replace("\\/", "/")
        .replace("&", "&")
        .replace(""""""", "\"")
        .replace("'", "'")
        .replace("&apos;", "'")
        .replace("<", "<")
        .replace(">", ">")

    /** 提取页面标题：h1 → og:title → <title>。 */
    fun extractPageTitle(html: String): String {
        val raw = h1Title.find(html)?.groupValues?.get(1)
            ?: ogTitle.find(html)?.groupValues?.get(1)
            ?: docTitle.find(html)?.groupValues?.get(1)
            ?: ""
        return cleanText(raw)
    }

    /**
     * 在页面文本里挖播放地址；iframe 里再试一层（最多两层）。
     */
    fun findStream(html: String, base: String, depth: Int = 0, customUA: String? = null): String? {
        val text = unescape(html)
        mediaUrl.find(text)?.value?.let { return it }
        jsonUrl.find(text)?.groupValues?.get(1)?.let { raw ->
            val url = unescape(raw)
            if (url.startsWith("http")) return url
        }
        if (depth >= 2) return null
        iframe.findAll(text).take(5).forEach { m ->
            val src = absolute(base, unescape(m.groupValues[1]))
            if (!src.startsWith("http")) return@forEach
            if (src == base) return@forEach
            val sub = runCatching { httpGet(src, customUA) }.getOrNull() ?: return@forEach
            findStream(sub, src, depth + 1, customUA)?.let { return it }
        }
        return null
    }

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /** 简单的 HTTP GET 请求。 */
    fun httpGet(url: String, customUA: String? = null): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", customUA ?: DEFAULT_UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} $url")
            }
            return conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}