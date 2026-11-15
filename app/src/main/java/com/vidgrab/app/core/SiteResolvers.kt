package com.vidgrab.app.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * 站点特定解析器框架：针对特定网站的分集/视频列表解析。
 * 当通用的 SeriesResolver 无法正确识别页面结构时，可实现此接口。
 */
interface SiteResolver {

    /** 该解析器支持的域名后缀（如 "mrds.com"、"mrds66.com"）。 */
    val supportedHosts: Set<String>

    /** 判断 URL 是否为该站点的列表/详情页。 */
    fun matches(url: String): Boolean

    /**
     * 解析页面，返回分集列表。
     * 返回 null 表示无法解析（不是支持的页面类型，或解析失败）。
     */
    suspend fun resolve(context: Context, url: String): SeriesResolver.Series?

    /** 可选：自定义剧集标题提取（默认用 SeriesResolver 的通用逻辑）。 */
    fun extractTitle(html: String, pageUrl: String): String? = null

    /** 可选：自定义单集播放地址解析（默认用 SeriesResolver 的通用逻辑）。 */
    suspend fun resolveStream(context: Context, episode: SeriesResolver.Episode): SeriesResolver.Stream? = null
}

/**
 * 站点解析器注册表：维护所有已注册的 SiteResolver，按域名分发。
 */
object SiteResolverRegistry {

    private val resolvers = mutableListOf<SiteResolver>()

    /** 注册解析器（模块初始化时调用）。 */
    fun register(resolver: SiteResolver) {
        resolvers.add(resolver)
    }

    /** 根据 URL 找到匹配的解析器。 */
    fun findForUrl(url: String): SiteResolver? {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return null
        return resolvers.firstOrNull { resolver ->
            resolver.supportedHosts.any { host.endsWith(it) }
        }
    }

    /**
     * 尝试用站点特定解析器解析，失败则回退到通用 SeriesResolver。
     * 这是给调用方（HomeScreen/BrowserScreen）用的统一入口。
     */
    suspend fun resolveSeries(context: Context, url: String): SeriesResolver.Series? {
        val resolver = findForUrl(url)
        if (resolver != null) {
            val result = runCatching { resolver.resolve(context, url) }.getOrNull()
            if (result != null) return result
        }
        // 回退到通用解析器
        return SeriesResolver.resolveSeries(context, url)
    }

    /** 解析单集流（优先用站点特定逻辑，回退到通用逻辑）。 */
    suspend fun resolveStream(context: Context, episode: SeriesResolver.Episode, baseUrl: String): SeriesResolver.Stream? {
        val resolver = findForUrl(baseUrl)
        if (resolver != null) {
            val result = runCatching { resolver.resolveStream(context, episode) }.getOrNull()
            if (result != null) return result
        }
        // 回退到通用逻辑（通过 SeriesResolver.resolveStreams 内部处理）
        return null
    }
}