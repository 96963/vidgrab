package com.vidgrab.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.text.RegexOption
import java.net.URI

/** Mrds.com 及其镜像站的解析器（每日大赛 / MRDS 视频站）。 */
class MrdsResolver : SiteResolver {

    override val supportedHosts = setOf(
        "mrds.com",
        "mrds66.com",
        "mrdsw14.com",
        "mrdsw15.com",
        "mrdsg9.com",
        "mrdsy4.com"
    )

    private val anchor = Regex(
        """<a\s[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    override fun matches(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (!supportedHosts.any { host.endsWith(it) }) return false
        // /archives/数字 格式
        return uri.path?.matches(Regex("""/archives/\d+""")) == true
    }

    override suspend fun resolve(context: Context, url: String): SeriesResolver.Series? = withContext(Dispatchers.IO) {
        val html = runCatching { SiteResolverUtils.httpGet(url) }.getOrElse {
            DiagLog.w("MrdsResolver", "detail failed url=$url msg=${it.message}")
            return@withContext null
        }
        val title = extractTitle(html, url)
        val episodes = findEpisodes(html, url)
        if (episodes.size < 1) {
            DiagLog.w("MrdsResolver", "detail url=$url episodes=${episodes.size} -> not a valid page")
            return@withContext null
        }
        DiagLog.w(
            "MrdsResolver",
            "detail ok title=$title episodes=${episodes.size} first=${episodes.first().pageUrl}"
        )
        SeriesResolver.Series(title, url, episodes)
    }

    /** 从 archives 页面提取所有视频链接。 */
    private fun findEpisodes(html: String, pageUrl: String): List<SeriesResolver.Episode> {
        val results = mutableListOf<SeriesResolver.Episode>()
        var number = 0

        // 策略1：尝试专门的 video-item 结构
        val videoItem = Regex(
            """<div[^>]*class="[^"]*video[^"]*"[^>]*>.*?<a[^>]*href="([^"]+)"[^>]*>.*?(?:<img[^>]*alt="([^"]*)"[^>]*>|>([^<]+)</a)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        videoItem.findAll(html).forEach { m ->
            val href = SiteResolverUtils.unescape(m.groupValues[1])
            val alt = m.groupValues[2]?.ifBlank { null } ?: m.groupValues[3]?.ifBlank { null }
            val url = SiteResolverUtils.absolute(pageUrl, href)
            if (!url.startsWith("http")) return@forEach
            number++
            val label = alt?.let { SiteResolverUtils.cleanText(it) } ?: "视频 $number"
            results.add(SeriesResolver.Episode(number, label, url))
        }

        // 策略2：如果策略1没找到，尝试通用的文章列表结构
        if (results.isEmpty()) {
            val archiveLink = Regex("""<a\s[^>]*href="(/archives/\d+|/video/\d+)"[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            archiveLink.findAll(html).forEach { m ->
                val href = SiteResolverUtils.unescape(m.groupValues[1])
                val text = SiteResolverUtils.cleanText(m.groupValues[2])
                val url = SiteResolverUtils.absolute(pageUrl, href)
                if (!url.startsWith("http")) return@forEach
                number++
                val label = text.ifBlank { "视频 $number" }
                results.add(SeriesResolver.Episode(number, label, url))
            }
        }

        // 策略3：更宽泛的匹配 - 页面内所有类似视频详情页的链接
        if (results.isEmpty()) {
            val detailLink = Regex("""<a\s[^>]*href="([^"]*(?:/archives/|/video/|/play/|/watch/)\d+[^"]*)"[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            detailLink.findAll(html).forEach { m ->
                val href = SiteResolverUtils.unescape(m.groupValues[1])
                val text = SiteResolverUtils.cleanText(m.groupValues[2])
                val url = SiteResolverUtils.absolute(pageUrl, href)
                if (!url.startsWith("http")) return@forEach
                number++
                val label = text.ifBlank { "视频 $number" }
                results.add(SeriesResolver.Episode(number, label, url))
            }
        }

        // 去重
        return results.distinctBy { it.pageUrl }.take(200)
    }

    override fun extractTitle(html: String, pageUrl: String): String {
        val text = SiteResolverUtils.extractPageTitle(html)
            .replace(Regex("""[-_|]\s*(每日大赛|MRDS|在线观看|免费观看|高清|完整版).*$"""), "")
            .trim()
        return text.ifBlank {
            runCatching { URI(pageUrl).host }.getOrNull()?.removePrefix("www.") ?: "MRDS 视频合集"
        }
    }

    override suspend fun resolveStream(context: Context, episode: SeriesResolver.Episode): SeriesResolver.Stream? {
        val html = runCatching { SiteResolverUtils.httpGet(episode.pageUrl) }.getOrNull() ?: return null
        val direct = SiteResolverUtils.findStream(html, episode.pageUrl)
        return if (direct != null) {
            SeriesResolver.Stream(
                episode.number,
                episode.label,
                "",
                direct,
                YtDlpEngine.HLS_SELECTOR
            )
        } else {
            DiagLog.w("MrdsResolver", "no stream ep=${episode.number} -> fallback page url")
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