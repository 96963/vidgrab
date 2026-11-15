package com.vidgrab.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通用「剧集页」解析，不绑定具体站点：
 *
 *   1. 从任意页面里找出「同一 URL 模板、编号递增」的链接 → 当成选集列表
 *      （/vodplay/123-1-1.html、/play/12345-2.html、?ep=3 这类都认）
 *   2. 逐集打开页面，按优先级挖播放地址：
 *      页面里的 m3u8 / mp4 直链 → iframe 里的播放器页面 → 播放器配置里的 url
 *   3. 挖不到就退回分集页 URL，交给 yt-dlp 自己解析
 */
object SeriesResolver {

    private val UAS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    )

    private val anchor = Regex(
        """<a\s[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val iframe = Regex(
        """<(?:iframe|frame)[^>]*src="([^"]+)"""",
        RegexOption.IGNORE_CASE
    )
    private val h1Title = Regex(
        """<h1[^>]*>(.*?)</h1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val ogTitle = Regex(
        """<meta[^>]+property="og:title"[^>]+content="([^"]+)"""",
        RegexOption.IGNORE_CASE
    )
    private val docTitle = Regex(
        """<title[^>]*>(.*?)</title>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val tag = Regex("""<[^>]+>""")
    private val mediaUrl = Regex(
        """https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val jsonUrl = Regex(
        """["']url["']\s*:\s*["']([^"']+?(?:m3u8|mp4)[^"']*?)["']""",
        RegexOption.IGNORE_CASE
    )
    /** 最后一段数字：/vodplay/252957-1-1.html 取末尾的 1；?ep=3 取 3。 */
    private val lastNumber = Regex("""(\d+)(?=[^\d]*$)""")
    private val episodeText = Regex(
        """第\s*\d{1,4}\s*[集话期回部]|[Ee][Pp]\s*\d{1,3}|Episode\s*\d{1,3}|^\d{1,4}$"""
    )

    data class Episode(val number: Int, val label: String, val pageUrl: String)

    data class Series(val title: String, val pageUrl: String, val episodes: List<Episode>)

    data class Stream(
        val number: Int,
        val label: String,
        /** 落盘用的标题：剧名 + 集数。 */
        val title: String,
        val url: String,
        /** yt-dlp 的选择器：直链 m3u8/mp4 用 best，分集页交给默认策略。 */
        val selector: String = YtDlpEngine.HLS_SELECTOR
    )

    /** 任何 http(s) 页面都可以先试着找分集，找不到再按单视频处理。 */
    fun looksLikeSeriesPage(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        return raw.startsWith("http://") || raw.startsWith("https://")
    }

    /**
     * 解析页面里的分集列表。认不出（少于 2 集）就返回 null，调用方回退单视频解析。
     */
    suspend fun resolveSeries(context: Context, url: String): Series? = withContext(Dispatchers.IO) {
        val html = runCatching { httpGet(url) }.getOrElse {
            DiagLog.w("Series", "detail failed url=$url msg=${it.message}")
            return@withContext null
        }
        val title = seriesTitle(html, url)
        val episodes = findEpisodes(html, url)
        if (episodes.size < 2) {
            DiagLog.w("Series", "detail url=$url episodes=${episodes.size} -> not a series")
            return@withContext null
        }
        DiagLog.w(
            "Series",
            "detail ok title=$title episodes=${episodes.size} first=${episodes.first().pageUrl}"
        )
        Series(title, url, episodes)
    }

    /**
     * 逐集解析播放地址。四路并发，单集失败只丢那一集。
     * onProgress 在主线程回调（done, total, 当前集）。
     */
    suspend fun resolveStreams(
        context: Context,
        series: Series,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): List<Stream> = coroutineScope {
        val gate = Semaphore(4)
        val done = AtomicInteger(0)
        series.episodes.map { ep ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val stream = runCatching { resolveStream(context, ep) }.getOrNull()
                    val count = done.incrementAndGet()
                    withContext(Dispatchers.Main) { onProgress(count, series.episodes.size, ep.label) }
                    stream?.let {
                        it.copy(title = fileName(series.title, it.label, ep.number))
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** 单集：页面里的直链 → iframe 播放页 → 播放器配置 → WebView 真跑一遍页面。 */
    private suspend fun resolveStream(context: Context, ep: Episode): Stream {
        val page = runCatching { httpGet(ep.pageUrl) }.getOrNull()
        val direct = page?.let { findStream(it, ep.pageUrl, 0) }
        if (direct != null) {
            return Stream(ep.number, ep.label, "", direct, YtDlpEngine.HLS_SELECTOR)
        }
        // 播放器地址由 JS 现算时，HTTP 页面里没有任何直链，交给不可见 WebView 去拦
        val sniffed = if (page == null || page.contains("player", ignoreCase = true)) {
            WebStreamProbe.probe(context, ep.pageUrl)
        } else {
            null
        }
        if (sniffed != null) {
            DiagLog.w("Series", "probe ok ep=${ep.number} url=${sniffed.take(100)}")
            return Stream(ep.number, ep.label, "", sniffed, YtDlpEngine.HLS_SELECTOR)
        }
        DiagLog.w("Series", "no stream ep=${ep.number} -> fallback page url")
        return Stream(ep.number, ep.label, "", ep.pageUrl, YtDlpEngine.DEFAULT_SELECTOR)
    }

    /** 在页面文本里挖播放地址；iframe 里再试一层（最多两层）。 */
    private fun findStream(html: String, base: String, depth: Int): String? {
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
            val sub = runCatching { httpGet(src) }.getOrNull() ?: return@forEach
            findStream(sub, src, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * 找分集链接：把页面里所有链接按「URL 模板」归类（末尾数字换成 {n}），
     * 取编号最多的一组；文案像集数的优先。
     */
    private fun findEpisodes(html: String, pageUrl: String): List<Episode> {
        data class Raw(val number: Int, val label: String, val url: String, val template: String)

        val raws = anchor.findAll(html).mapNotNull { m ->
            val href = unescape(m.groupValues[1])
            if (href.startsWith("javascript:") || href.startsWith("#")) return@mapNotNull null
            val text = cleanText(m.groupValues[2])
            if (!episodeText.containsMatchIn(text)) return@mapNotNull null
            val url = absolute(pageUrl, href)
            if (!url.startsWith("http")) return@mapNotNull null
            val pathAndQuery = runCatching {
                val uri = URI(url)
                (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
            }.getOrNull().orEmpty()
            val numberMatch = lastNumber.find(pathAndQuery) ?: return@mapNotNull null
            val number = numberMatch.value.toIntOrNull() ?: return@mapNotNull null
            val template = pathAndQuery.replaceRange(numberMatch.range, "{n}")
            Raw(number, text, url, template)
        }.toList()

        if (raws.isEmpty()) return emptyList()
        val best = raws.groupBy { it.template }
            .maxByOrNull { entry -> entry.value.distinctBy { it.number }.size }
            ?: return emptyList()
        return best.value
            .distinctBy { it.number }
            .sortedBy { it.number }
            .take(200)
            .map { Episode(it.number, it.label.ifBlank { "第${it.number}集" }, it.url) }
    }

    /** 剧集标题：h1 → og:title → <title>，去掉站点后缀和《》。 */
    private fun seriesTitle(html: String, pageUrl: String): String {
        val raw = h1Title.find(html)?.groupValues?.get(1)
            ?: ogTitle.find(html)?.groupValues?.get(1)
            ?: docTitle.find(html)?.groupValues?.get(1)
            ?: ""
        val text = cleanSeriesTitle(raw)
        return text.ifBlank {
            runCatching { URI(pageUrl).host }.getOrNull()?.removePrefix("www.") ?: "剧集"
        }
    }

    /**
     * 中文站点的标题常常是「谷雨详情介绍-谷雨在线观看-谷雨迅雷下载 - 耐看点播…」，
     * 直接落盘会把站点名一起带进去；这里截到「详情介绍」这类词之前。
     */
    private fun cleanSeriesTitle(raw: String): String {
        var text = cleanText(raw).trim('《', '》', ' ', '\u3000')
        val cutWords = listOf(
            "详情介绍", "剧情介绍", "在线观看", "免费观看", "迅雷下载",
            "免费播放", "高清完整版", "在线播放"
        )
        val cutAt = cutWords.map { text.indexOf(it) }.filter { it > 0 }.minOrNull()
        if (cutAt != null) text = text.substring(0, cutAt)
        text = text.replace(Regex("""[\s\-_|·:：]+$"""), "")
        return text.trim()
    }

    /** 给界面用：剧集标题 + 干净文件名，集数统一成「第NN集」。 */
    fun fileName(seriesTitle: String, label: String, number: Int): String {
        val ep = episodeLabel(label, number)
        val base = FileNames.clean(seriesTitle).ifBlank { "剧集" }
        return FileNames.clean("$base $ep").ifBlank { ep }
    }

    /** 分集文案归一：站点写「01」「第1集」「EP1」都落成「第NN集」。 */
    private fun episodeLabel(label: String, number: Int): String {
        val text = label.trim().replace(Regex("""\s+"""), "")
        return when {
            text.isBlank() -> "第%02d集".format(number)
            Regex("""^第\d{1,4}[集话期回部]""").containsMatchIn(text) -> text
            Regex("""^(?i)(ep|e)\s*\d{1,4}""").containsMatchIn(text) -> text
            text.all { it.isDigit() } -> "第%02d集".format(number)
            else -> text
        }
    }

    // ---------- 基础工具 ----------

    private fun cleanText(raw: String): String {
        val text = unescape(tag.replace(raw, " "))
            .replace('\u00a0', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        return text.trim('《', '》', ' ', '\u3000')
    }

    private fun absolute(base: String, href: String): String {
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

    private fun unescape(raw: String): String = raw
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun httpGet(url: String): String {
        var lastError: IOException? = null
        for (index in UAS.indices) {
            try {
                return request(url, UAS[index])
            } catch (e: HttpStatusException) {
                lastError = e
                // 不少站点只给手机 UA，桌面 UA 直接 404；换 UA 再试一次
                if (e.code !in setOf(403, 404, 406, 429, 451) || index == UAS.lastIndex) throw e
            } catch (e: IOException) {
                lastError = e
                if (index == UAS.lastIndex) throw e
            }
        }
        throw lastError ?: IOException("请求失败 $url")
    }

    private class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code $url")

    private fun request(url: String, ua: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw HttpStatusException(conn.responseCode, url)
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
