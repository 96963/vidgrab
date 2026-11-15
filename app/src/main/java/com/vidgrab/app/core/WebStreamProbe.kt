package com.vidgrab.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 播放页兜底探测：有些站点的 m3u8 由播放器 JS 现算（如 MacCMS 的加密 player_aaaa），
 * HTTP 抓页面拿不到。这里用一个不可见的 WebView 真跑一遍页面，拦它的媒体请求。
 *
 * 只在 HTTP 挖不到地址时调用；全局串行，避免同时开多个 WebView。
 */
object WebStreamProbe {

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private val gate = Mutex()

    suspend fun probe(context: Context, pageUrl: String, timeoutMs: Long = 18_000): String? =
        gate.withLock {
            withContext(Dispatchers.Main) {
                runProbe(context, pageUrl, timeoutMs)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runProbe(context: Context, pageUrl: String, timeoutMs: Long): String? {
        val hit = CompletableDeferred<String?>()
        var mp4: String? = null
        val web = WebView(context)
        return try {
            with(web.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = MOBILE_UA
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url?.toString().orEmpty()
                    if (!hit.isCompleted) {
                        when {
                            url.contains(".m3u8", ignoreCase = true) -> hit.complete(url)
                            url.contains(".mp4", ignoreCase = true) -> if (mp4 == null) mp4 = url
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // 大多数播放器会自动起播；起不来的话点一下「播放」按钮再试
                    view.evaluateJavascript(
                        "(function(){" +
                            "var s=['video','.play','button.play','.vjs-big-play-button','#play','.btn-play','.player-play'];" +
                            "for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(e){e.click();return '1';}}" +
                            "var bs=document.querySelectorAll('button,a,div');" +
                            "for(var j=0;j<bs.length;j++){var t=(bs[j].textContent||'').trim();" +
                            "if(t.length<=6&&/播放|立即播放|开始播放/.test(t)){bs[j].click();return '1';}}" +
                            "return '0';})()",
                        null
                    )
                }
            }
            web.loadUrl(pageUrl)
            val m3u8 = withTimeoutOrNull(timeoutMs) { hit.await() }
            m3u8 ?: mp4
        } catch (e: Throwable) {
            DiagLog.w("Probe", "fail url=$pageUrl msg=${e.message?.take(120)}")
            null
        } finally {
            runCatching { web.stopLoading() }
            runCatching { web.destroy() }
        }
    }
}
