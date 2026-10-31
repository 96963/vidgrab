package com.vidgrab.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vidgrab.app.PlayerActivity
import com.vidgrab.app.core.AdBlocker
import com.vidgrab.app.core.BrowserStore
import com.vidgrab.app.core.DetectedMedia
import com.vidgrab.app.core.DetectionLogEntry
import com.vidgrab.app.core.MediaSniffer
import com.vidgrab.app.core.SiteSettings
import java.net.URI

const val HOME_URL = "https://cn.bing.com"

/**
 * 页面状态快照。WebView 只能在主线程访问，而 shouldInterceptRequest 与
 * JavascriptInterface 回调都在后台线程，所以这里用主线程更新的字段来避免跨线程调用。
 */
private class PageState {
    @Volatile var url: String = HOME_URL
    @Volatile var title: String? = null
    @Volatile var userAgent: String = ""
    @Volatile var adBlock: Boolean = true
}

private enum class BrowserSheet { NONE, MENU, LIBRARY, LOGS }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String,
    onUrlChange: (String) -> Unit,
    onDownload: (String, String?) -> Unit
) {
    val context = LocalContext.current
    var address by remember { mutableStateOf(initialUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(BrowserSheet.NONE) }
    var libraryTab by remember { mutableIntStateOf(0) }
    var showSiteDialog by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var adBlockEnabled by rememberSaveable { mutableStateOf(true) }
    var settingsVersion by remember { mutableIntStateOf(0) }
    val pageState = remember { PageState().also { it.url = initialUrl } }
    val detected by MediaSniffer.items.collectAsStateWithLifecycle()
    val logs by MediaSniffer.logs.collectAsStateWithLifecycle()
    val history by BrowserStore.history.collectAsStateWithLifecycle()
    val bookmarks by BrowserStore.bookmarks.collectAsStateWithLifecycle()

    val siteKey = remember(address, settingsVersion) { SiteSettings.siteKeyOf(address) }
    val siteDisabled = remember(siteKey, settingsVersion) {
        siteKey != null && SiteSettings.isAdBlockDisabled(address)
    }

    SideEffect { pageState.adBlock = adBlockEnabled }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
        canGoBack = webView?.canGoBack() == true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
                placeholder = { Text("输入网址") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = {
                    webView?.loadUrl(normalizeUrl(address))
                })
            )
            IconButton(
                onClick = {
                    webView?.goBack()
                    canGoBack = webView?.canGoBack() == true
                },
                enabled = canGoBack
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "后退",
                    tint = if (canGoBack) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
            IconButton(onClick = { webView?.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .combinedClickable(
                        onClick = { adBlockEnabled = !adBlockEnabled },
                        onLongClick = { showSiteDialog = true }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = when {
                        !adBlockEnabled -> "广告拦截：已关闭"
                        siteDisabled -> "广告拦截：本站已禁用"
                        else -> "广告拦截：开（长按对本站设置）"
                    },
                    tint = if (adBlockEnabled && !siteDisabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
            IconButton(onClick = { sheet = BrowserSheet.MENU }) {
                Icon(Icons.Default.MoreVert, contentDescription = "菜单")
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.userAgentString = settings.userAgentString +
                        " VidGrab/1.0"
                    pageState.userAgent = settings.userAgentString

                    // 尽量在页面脚本前注入，补上加载阶段发起的 XHR/fetch
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            MediaSniffer.injectedJs,
                            setOf("*")
                        )
                    }

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onMediaUrl(url: String) {
                            offerDetected(
                                url, pageState.url, pageState.title, pageState.userAgent,
                                MediaSniffer.SRC_JS
                            )
                        }

                        @JavascriptInterface
                        fun onMediaPlaylist(url: String, kind: String) {
                            offerDetected(
                                url, pageState.url, pageState.title, pageState.userAgent,
                                MediaSniffer.SRC_RESPONSE,
                                forcedKind = kind.ifBlank { null }
                            )
                        }

                        @JavascriptInterface
                        fun onPerformanceUrl(url: String) {
                            offerDetected(
                                url, pageState.url, pageState.title, pageState.userAgent,
                                MediaSniffer.SRC_PERFORMANCE
                            )
                        }
                    }, "VidGrabBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            pageState.url = url
                            pageState.title = view.title
                            canGoBack = view.canGoBack()
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val target = request.url.toString()
                            if (pageState.adBlock && AdBlocker.isBlocked(target, pageState.url)) {
                                android.util.Log.d("VidGrab", "adblock: $target")
                                if (MediaSniffer.classify(target) != null) {
                                    MediaSniffer.recordBlocked(target)
                                }
                                return AdBlocker.emptyResponse()
                            }
                            offerDetected(
                                target,
                                pageState.url,
                                pageState.title,
                                pageState.userAgent,
                                MediaSniffer.SRC_INTERCEPT
                            )
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val target = request.url.toString()
                            val scheme = request.url.scheme?.lowercase()
                            if (request.isForMainFrame && scheme != "http" && scheme != "https") {
                                MediaSniffer.log(MediaSniffer.SRC_ADBLOCK, target, "拦截外部跳转")
                                return true
                            }
                            if (request.isForMainFrame && pageState.adBlock &&
                                AdBlocker.isBlocked(target, pageState.url)
                            ) {
                                MediaSniffer.log(MediaSniffer.SRC_ADBLOCK, target, "拦截跳转")
                                return true
                            }
                            return super.shouldOverrideUrlLoading(view, request)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            pageState.url = url
                            pageState.title = view.title
                            BrowserStore.addHistory(url, view.title)
                            view.evaluateJavascript(MediaSniffer.injectedJs, null)
                            address = url
                            onUrlChange(url)
                            canGoBack = view.canGoBack()
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView,
                            url: String,
                            isReload: Boolean
                        ) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            pageState.url = url
                            pageState.title = view.title
                            BrowserStore.addHistory(url, view.title)
                            address = url
                            onUrlChange(url)
                            canGoBack = view.canGoBack()
                        }
                    }
                    loadUrl(initialUrl)
                    webView = this
                }
            },
            update = { webView = it }
        )

        if (detected.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { showSheet = true },
                modifier = Modifier.padding(12.dp),
                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                text = { Text("检测到 ${detected.size} 个媒体") }
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "检测结果",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        showSheet = false
                        sheet = BrowserSheet.LOGS
                    }) {
                        Text("日志")
                    }
                    TextButton(onClick = {
                        MediaSniffer.clear()
                        showSheet = false
                    }) {
                        Text("清空")
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    items(detected) { media ->
                        DetectedRow(
                            media = media,
                            onPlay = {
                                showSheet = false
                                PlayerActivity.start(context, media.url, media.pageTitle)
                            },
                            onDownload = {
                                showSheet = false
                                onDownload(media.url, media.pageTitle)
                            }
                        )
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (sheet == BrowserSheet.LOGS) {
        ModalBottomSheet(
            onDismissRequest = { sheet = BrowserSheet.NONE },
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "检测日志",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { MediaSniffer.clearLogs() }) { Text("清空") }
                }
                Spacer(Modifier.height(8.dp))
                if (logs.isEmpty()) {
                    Text("暂无日志。打开网页并播放视频后，这里会显示每次检测的来源与结果。")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                        items(logs) { entry ->
                            LogRow(entry)
                            HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (sheet == BrowserSheet.MENU) {
        ModalBottomSheet(
            onDismissRequest = { sheet = BrowserSheet.NONE },
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                MenuItem("历史记录", "最近访问的网页") {
                    libraryTab = 0
                    sheet = BrowserSheet.LIBRARY
                }
                MenuItem("书签", "已收藏的网页") {
                    libraryTab = 1
                    sheet = BrowserSheet.LIBRARY
                }
                val bookmarked = bookmarks.any { it.url == address }
                MenuItem(
                    if (bookmarked) "取消当前页书签" else "收藏当前页",
                    address
                ) {
                    if (bookmarked) BrowserStore.removeBookmark(address)
                    else BrowserStore.addBookmark(address, pageState.title)
                    sheet = BrowserSheet.NONE
                }
                MenuItem("检测日志", "查看嗅探来源与结果") { sheet = BrowserSheet.LOGS }
                MenuItem("清空检测", "清空已嗅探到的媒体地址") {
                    MediaSniffer.clear()
                    sheet = BrowserSheet.NONE
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (sheet == BrowserSheet.LIBRARY) {
        ModalBottomSheet(
            onDismissRequest = { sheet = BrowserSheet.NONE },
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (libraryTab == 0) "历史记录" else "书签",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (libraryTab == 0) {
                        TextButton(onClick = { BrowserStore.clearHistory() }) { Text("清空") }
                    }
                }
                TabRow(selectedTabIndex = libraryTab) {
                    Tab(
                        selected = libraryTab == 0,
                        onClick = { libraryTab = 0 },
                        text = { Text("历史") }
                    )
                    Tab(
                        selected = libraryTab == 1,
                        onClick = { libraryTab = 1 },
                        text = { Text("书签") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (libraryTab == 0) {
                    if (history.isEmpty()) {
                        Text("暂无历史记录。")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                            items(history) { entry ->
                                LibraryRow(entry.title, entry.url, time = entry.visitedAt) {
                                    webView?.loadUrl(entry.url)
                                    sheet = BrowserSheet.NONE
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                } else {
                    if (bookmarks.isEmpty()) {
                        Text("暂无书签。打开页面后，在菜单里点「收藏当前页」。")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                            items(bookmarks) { entry ->
                                LibraryRow(
                                    entry.title,
                                    entry.url,
                                    time = entry.savedAt,
                                    onDelete = { BrowserStore.removeBookmark(entry.url) }
                                ) {
                                    webView?.loadUrl(entry.url)
                                    sheet = BrowserSheet.NONE
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showSiteDialog) {
        val key = siteKey
        val disabled = siteDisabled
        AlertDialog(
            onDismissRequest = { showSiteDialog = false },
            title = { Text("本站广告拦截") },
            text = {
                Text(
                    if (key == null) "当前页面没有有效域名。"
                    else if (disabled) "已对 $key 禁用广告拦截。\n播放异常时保持禁用通常能恢复。"
                    else "是否对 $key 禁用广告拦截？\n如果开启拦截后视频无法播放，关闭本站拦截通常可解决。"
                )
            },
            confirmButton = {
                if (key != null) {
                    TextButton(onClick = {
                        SiteSettings.setAdBlockDisabled(key, !disabled)
                        settingsVersion++
                        showSiteDialog = false
                    }) {
                        Text(if (disabled) "恢复拦截" else "本站禁用")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSiteDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun LogRow(entry: DetectionLogEntry) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "${entry.source} · ${entry.result}",
            style = MaterialTheme.typography.labelMedium,
            color = if (entry.source == MediaSniffer.SRC_ADBLOCK) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
        Text(
            entry.url,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2
        )
        Text(
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(entry.time)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MenuItem(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun LibraryRow(
    title: String,
    url: String,
    time: Long,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (time > 0) {
                Text(
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(time)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        onDelete?.let {
            IconButton(onClick = it) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun DetectedRow(
    media: DetectedMedia,
    onPlay: () -> Unit,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val chipBg = when (media.kind) {
                "HLS" -> MaterialTheme.colorScheme.primaryContainer
                "DASH" -> DashSoft
                "VIDEO", "MP4" -> GreenSoft
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val chipFg = when (media.kind) {
                "HLS" -> MaterialTheme.colorScheme.primary
                "DASH" -> Dash
                "VIDEO", "MP4" -> Green
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                media.kind,
                style = MaterialTheme.typography.labelSmall,
                color = chipFg,
                modifier = Modifier
                    .background(chipBg, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Text(
                media.url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
        TextButton(onClick = onPlay) { Text("播放") }
        TextButton(onClick = onDownload) { Text("解析") }
    }
}

/**
 * 记录媒体地址，同时带上当前页面的 Cookie / Referer / UA，供 yt-dlp 复用。
 * 只用传入的快照字段，绝不在这里调用 WebView 方法（可能在后台线程）。
 */
private fun offerDetected(
    url: String,
    pageUrl: String,
    pageTitle: String?,
    userAgent: String,
    source: String,
    forcedKind: String? = null
) {
    if (forcedKind == null && MediaSniffer.classify(url) == null) return
    val headers = buildMap {
        if (pageUrl.isNotBlank()) put("Referer", pageUrl)
        if (userAgent.isNotBlank()) put("User-Agent", userAgent)
        originOf(pageUrl)?.let { put("Origin", it) }
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.let { if (it.isNotBlank()) put("Cookie", it) }
    }
    MediaSniffer.offer(url, pageUrl, pageTitle, headers, source, forcedKind)
}

private fun originOf(pageUrl: String): String? {
    val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
    val scheme = uri.scheme ?: return null
    val host = uri.host ?: return null
    return if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return HOME_URL
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
    else "https://$trimmed"
}
