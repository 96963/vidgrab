package com.vidgrab.app.core

import android.content.Context
import android.webkit.CookieManager
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 浏览器登录态复用：把内置浏览器页面上的 Cookie 按域名缓存下来，
 * 粘贴链接下载 / 播放时自动带上，不再只依赖“先嗅探过一次”的请求头。
 *
 * CookieManager 的读取只在主线程（浏览器回调）里做；下载线程只读内存缓存。
 */
object CookieStore {

    private const val PREFS = "cookie_store"
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val byHost = ConcurrentHashMap<String, String>()
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs?.all?.forEach { (key, value) ->
                if (key.startsWith("h:") && value is String && value.isNotBlank()) {
                    byHost[key.removePrefix("h:")] = value
                }
            }
        }
    }

    /** 在 WebView 页面加载完成后调用（主线程）。 */
    fun rememberFromWebView(url: String) {
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        remember(url, cookie)
    }

    fun remember(url: String, cookie: String?) {
        if (cookie.isNullOrBlank()) return
        val host = hostOf(url) ?: return
        byHost[host] = cookie
        prefs?.edit()?.putString("h:$host", cookie)?.apply()
    }

    /** 该 URL 是否有可复用 Cookie。 */
    fun hasCookie(url: String): Boolean = !cookieFor(url).isNullOrBlank()

    fun cookieFor(url: String): String? {
        val host = hostOf(url) ?: return null
        return byHost[host]
    }

    /** 合并嗅探请求头 + 浏览器 Cookie + 默认 UA。 */
    fun headersFor(url: String): Map<String, String> {
        val headers = MediaSniffer.headersFor(url).toMutableMap()
        val cookie = cookieFor(url)
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        if (headers["User-Agent"].isNullOrBlank()) headers["User-Agent"] = DEFAULT_UA
        return headers
    }

    private fun hostOf(url: String): String? = runCatching {
        URI(url).host?.lowercase()
    }.getOrNull()
}
