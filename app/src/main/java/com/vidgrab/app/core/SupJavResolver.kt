package com.vidgrab.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.text.RegexOption
import java.net.URI

/** SupJav.com 及其镜像站的解析器。 */
class SupJavResolver : SiteResolver {

    override val supportedHosts = setOf(
        "supjav.com",
        "supjav.guru",
        "supjav.co"
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    private val anchor = Regex(
        """<a\s[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** SupJav 视频页面的特征：/video/数字 或 直接是视频详情页。 */
    private val videoPagePattern = Regex("""/(video|movie|watch)/\d+""")

    override fun matches(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (!supportedHosts.any { host.endsWith(it) }) return false
        // 视频详情页
        val path = uri.path.orEmpty()
        return videoPagePattern.containsMatchIn(path)
    }

    override suspend fun resolve(context: Context, url: String): SeriesResolver.Series? = withContext(Dispatchers.IO) {
        val html = runCatching { SiteResolverUtils.httpGet(url, UA) }.getOrElse {
            DiagLog.w("SupJavResolver", "detail failed url=$url msg=${it.message}")
            return@withContext null
        }
        val title = extractTitle(html, url)
        val episodes = findEpisodes(html, url)
        if (episodes.isEmpty()) {
            DiagLog.w("SupJavResolver", "detail url=$url episodes=0 -> not a valid page")
            return@withContext null
        }
        DiagLog.w(
            "SupJavResolver",
            "detail ok title=$title episodes=${episodes.size} first=${episodes.first().pageUrl}"
        )
        SeriesResolver.Series(title, url, episodes)
    }

    /** 从 SupJav 页面提取视频信息。 */
    private fun findEpisodes(html: String, pageUrl: String): List<SeriesResolver.Episode> {
        val results = mutableListOf<SeriesResolver.Episode>()

        // SupJav 通常是单视频页面，但也可能有合集/系列页面
        // 先尝试提取当前页面的视频标题和播放地址作为单集
        val currentTitle = extractVideoTitleFromPage(html)
        if (currentTitle.isNotBlank()) {
            results.add(SeriesResolver.Episode(1, currentTitle, pageUrl))
        }

        // 再尝试找页面内的关联视频/合集链接（如果是系列页面）
        val relatedLink = Regex("""<a\s[^>]*href="(/video/\d+|/movie/\d+)"[^>]*>.*?(?:<img[^>]*alt="([^"]*)"|<span[^>]*>([^<]+)</span>|>([^<]+)</a>)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        relatedLink.findAll(html).forEach { m ->
            val href = SiteResolverUtils.unescape(m.groupValues[1])
            val alt = m.groupValues[2]?.ifBlank { null } ?: m.groupValues[3]?.ifBlank { null } ?: m.groupValues[4]?.ifBlank { null }
            val url = SiteResolverUtils.absolute(pageUrl, href)
            if (!url.startsWith("http")) return@forEach
            if (url == pageUrl) return@forEach // 避免重复
            val label = alt?.let { SiteResolverUtils.cleanText(it) } ?: "关联视频 ${results.size + 1}"
            results.add(SeriesResolver.Episode(results.size + 1, label, url))
        }

        // 更宽泛的匹配：页面内所有 /video/ 数字 链接
        if (results.size <= 1) {
            val videoLink = Regex("""<a\s[^>]*href="([^"]*/video/\d+[^"]*)"[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            videoLink.findAll(html).forEach { m ->
                val href = SiteResolverUtils.unescape(m.groupValues[1])
                val text = SiteResolverUtils.cleanText(m.groupValues[2])
                val url = SiteResolverUtils.absolute(pageUrl, href)
                if (!url.startsWith("http")) return@forEach
                if (results.any { it.pageUrl == url }) return@forEach
                val label = text.ifBlank { "视频 ${results.size + 1}" }
                results.add(SeriesResolver.Episode(results.size + 1, label, url))
            }
        }

        return results.distinctBy { it.pageUrl }.take(50)
    }

    /** 从页面提取视频标题。 */
    private fun extractVideoTitleFromPage(html: String): String {
        // 尝试多种选择器
        val titleSelectors = listOf(
            Regex("""<h1[^>]*class="[^"]*video-title[^"]*"[^>]*>(.*?)</h1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            Regex("""<h1[^>]*class="[^"]*entry-title[^"]*"[^>]*>(.*?)</h1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            Regex("""<div[^>]*class="[^"]*video-info[^"]*"[^>]*>.*?<h1[^>]*>(.*?)</h1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            SiteResolverUtils.h1Title,
            SiteResolverUtils.ogTitle,
            SiteResolverUtils.docTitle
        )
        for (selector in titleSelectors) {
            val raw = selector.find(html)?.groupValues?.get(1) ?: continue
            val text = SiteResolverUtils.cleanText(raw)
            if (text.isNotBlank()) return text
        }
        return ""
    }

    override fun extractTitle(html: String, pageUrl: String): String {
        val pageTitle = extractVideoTitleFromPage(html)
        if (pageTitle.isNotBlank()) return pageTitle
        val text = SiteResolverUtils.extractPageTitle(html)
            .replace(Regex("""[-_|]\s*(SupJav|在线观看|免费观看|高清|完整版).*$"""), "")
            .trim()
        return text.ifBlank {
            runCatching { URI(pageUrl).host }.getOrNull()?.removePrefix("www.") ?: "SupJav 视频"
        }
    }

    override suspend fun resolveStream(context: Context, episode: SeriesResolver.Episode): SeriesResolver.Stream? {
        val html = runCatching { SiteResolverUtils.httpGet(episode.pageUrl, UA) }.getOrNull() ?: return null
        val direct = SiteResolverUtils.findStream(html, episode.pageUrl, 0, UA)
        return if (direct != null) {
            SeriesResolver.Stream(
                episode.number,
                episode.label,
                "",
                direct,
                YtDlpEngine.HLS_SELECTOR
            )
        } else {
            DiagLog.w("SupJavResolver", "no stream ep=${episode.number} -> fallback page url")
            SeriesResolver.Stream(
                episode.number,
                episode.label,
                "",
                episode.pageUrl,
                YtDlpEngine.DEFAULT_SELECTOR
            )
        }
    }
}