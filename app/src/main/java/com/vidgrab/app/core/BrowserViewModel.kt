package com.vidgrab.app.core

import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

private const val HOME_URL = "https://cn.bing.com"

 /** 切走页签时暂停网页里正在播放的音视频，给被暂停的元素打标记。 */
private const val PAUSE_MEDIA_JS =
    "(function(){var n=0,ms=document.querySelectorAll('video,audio');" +
        "for(var i=0;i<ms.length;i++){var m=ms[i];" +
        "if(!m.paused&&!m.ended){try{m.dataset.vidgrabPaused='1';m.pause();n++;}catch(e){}}}" +
        "return n;})()"

 /** 用户点击「继续播放」后，只恢复被我们暂停过的元素，不动用户自己暂停的视频。 */
private const val RESUME_MEDIA_JS =
    "(function(){var n=0,ms=document.querySelectorAll('video,audio');" +
        "for(var i=0;i<ms.length;i++){var m=ms[i];" +
        "if(m.dataset&&m.dataset.vidgrabPaused==='1'){" +
        "delete m.dataset.vidgrabPaused;n++;try{m.play().catch(function(){});}catch(e){}}}" +
        "return n;})()"

/**
 * 浏览器 ViewModel：持有 WebView 实例与状态，跨页签切换存活
 * 由 MainActivity 通过 viewModels() 获取，生命周期随 Activity
 */
class BrowserViewModel : ViewModel() {

    /** 桌面模式 UA；手机模式保留 WebView 原始 UA。 */
    companion object {
        const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    /** WebView 实例（首次创建后复用） */
    var webView: WebView? = null
        private set

    /** WebView 创建前收到的跳转请求，创建后补执行。 */
    private var pendingLoad: String? = null

    /** 创建时 WebView 自带的手机 UA，切回手机模式时用。 */
    private var phoneUserAgent: String? = null

    /** 当前是否桌面模式 */
    var desktopMode: Boolean = false
        private set

    /** 当前 URL */
    var currentUrl: String = "https://cn.bing.com"
        private set

    /** 当前标题 */
    var currentTitle: String = ""
        private set

    /** 是否可以后退 */
    var canGoBack = false
        private set

    /** 广告拦截开关 */
    var adBlockEnabled = true
        private set

    /** 初始化 WebView（仅首次调用有效） */
    fun initializeWebView(webView: WebView) {
        if (this.webView == null) {
            this.webView = webView
            phoneUserAgent = webView.settings.userAgentString
            applyUserAgent()
            this.webView?.let { w ->
                currentUrl = w.url ?: HOME_URL
                currentTitle = w.title ?: ""
                canGoBack = w.canGoBack()
            }
            // onCreate 里通过 intent 传来的地址，可能早于 WebView 创建，这里补上
            pendingLoad?.let { url ->
                pendingLoad = null
                this.webView?.loadUrl(url)
            }
        }
    }

    private fun applyUserAgent() {
        val w = webView ?: return
        w.settings.userAgentString = if (desktopMode) UA_DESKTOP else phoneUserAgent
    }

    /** 手机模式 / 电脑模式切换：改 UA 后重新加载当前页，让站点返回对应版式。 */
    fun setDesktopMode(enabled: Boolean, reloadNow: Boolean = true) {
        if (desktopMode == enabled) return
        desktopMode = enabled
        applyUserAgent()
        if (reloadNow) webView?.reload()
    }

    fun updateUrl(url: String) {
        currentUrl = url
    }

    fun updateTitle(title: String?) {
        currentTitle = title ?: ""
    }

    fun updateCanGoBack(canGoBack: Boolean) {
        this.canGoBack = canGoBack
    }

    fun setAdBlockEnabled(enabled: Boolean) {
        adBlockEnabled = enabled
    }

    fun loadUrl(url: String) {
        val view = webView
        if (view == null) {
            pendingLoad = url
            return
        }
        view.loadUrl(url)
    }

    fun goBack() {
        webView?.goBack()
    }

    fun reload() {
        webView?.reload()
    }

    fun evaluateJavascript(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    /**
     * 页签切换时暂停/恢复网页：WebView.onPause 停掉定时器与渲染，
     * JS 只恢复离开前正在播放的媒体，不自动播放用户原本暂停的视频。
     */
    /**
     * 页签切走时调用：暂停网页里正在播放的音视频。
     * 只暂停真正在播的那些，用户原本就暂停的视频不动；回调告知「是否真的有媒体被暂停」，
     * UI 据此决定切回来时要不要提示「点击继续播放」。
     */
    fun pauseMediaForTabSwitch(onResult: (Boolean) -> Unit) {
        val view = webView
        if (view == null) {
            onResult(false)
            return
        }
        view.evaluateJavascript(PAUSE_MEDIA_JS) { raw ->
            val paused = raw?.trim()?.trim('"')?.toIntOrNull() ?: 0
            onResult(paused > 0)
        }
    }

    /** 用户点击提示后恢复播放（只恢复被暂停过的媒体）。 */
    fun resumePausedMedia() {
        webView?.evaluateJavascript(RESUME_MEDIA_JS, null)
    }

    /** App 退到后台时暂停 WebView，避免后台继续出声/耗电。 */
    fun pauseWebView() {
        webView?.onPause()
    }

    /** App 回到前台时恢复 WebView。 */
    fun resumeWebView() {
        webView?.onResume()
    }

    override fun onCleared() {
        super.onCleared()
        // 释放 WebView 引用，避免内存泄漏
        webView?.destroy()
        webView = null
    }
}