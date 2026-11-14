package com.vidgrab.app.core

import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

private const val HOME_URL = "https://cn.bing.com"

/**
 * 浏览器 ViewModel：持有 WebView 实例与状态，跨页签切换存活
 * 由 MainActivity 通过 viewModels() 获取，生命周期随 Activity
 */
class BrowserViewModel : ViewModel() {

    /** WebView 实例（首次创建后复用） */
    var webView: WebView? = null
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
            this.webView?.let { w ->
                currentUrl = w.url ?: HOME_URL
                currentTitle = w.title ?: ""
                canGoBack = w.canGoBack()
            }
        }
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
        webView?.loadUrl(url)
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