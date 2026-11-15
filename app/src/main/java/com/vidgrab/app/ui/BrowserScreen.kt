package com.vidgrab.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.compose.ui.graphics.Color
import com.vidgrab.app.PlayerActivity
import com.vidgrab.app.core.AdBlocker
import com.vidgrab.app.core.BrowserStore
import com.vidgrab.app.core.CookieStore
import com.vidgrab.app.core.BrowserViewModel
import com.vidgrab.app.core.DetectedMedia
import com.vidgrab.app.core.DetectionLogEntry
import com.vidgrab.app.core.DirectDownloader
import com.vidgrab.app.core.FileNames
import com.vidgrab.app.core.MediaSniffer
import com.vidgrab.app.core.SiteSettings
import com.vidgrab.app.ui.SniffChips
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLDecoder

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
    viewModel: BrowserViewModel = viewModel(),
    isActive: Boolean = true,
    onUrlChange: (String) -> Unit,
    onDownload: (url: String, title: String?, direct: Boolean) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current

    // 网页 <input type=file> 的文件选择器
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return@rememberLauncherForActivityResult
        val uris: Array<Uri>? = when {
            result.resultCode != Activity.RESULT_OK -> null
            result.data?.clipData != null -> {
                val clip = result.data!!.clipData!!
                Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
            }
            result.data?.data != null -> arrayOf(result.data!!.data!!)
            else -> null
        }
        callback.onReceiveValue(uris)
    }
    val popupHost = remember(context) { PopupHost(context) }
    var pageError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose { popupHost.dismiss() }
    }

    // 从 ViewModel 读取状态
    var address by remember { mutableStateOf(viewModel.currentUrl) }
    var addressField by remember { mutableStateOf(TextFieldValue(viewModel.currentUrl)) }
    var addressFocused by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var mediaFilter by remember { mutableStateOf("全部") }
    var sheet by remember { mutableStateOf(BrowserSheet.NONE) }
    var libraryTab by remember { mutableIntStateOf(0) }
    var showSiteDialog by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(viewModel.canGoBack) }
    var adBlockEnabled by rememberSaveable { mutableStateOf(viewModel.adBlockEnabled) }
    var settingsVersion by remember { mutableIntStateOf(0) }

    val pageState = remember { PageState().also { it.url = viewModel.currentUrl } }

    // 页签切换不打断网页媒体：WebView 保持可见性状态，视频在后台页签继续缓冲/播放，
    // 切回时无需重新 play()，避免自动播放策略拒绝恢复。
    // 仅在 App 真正退到后台时暂停 WebView，避免后台出声和耗电。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.pauseWebView()
                Lifecycle.Event.ON_START -> viewModel.resumeWebView()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val detected by MediaSniffer.items.collectAsStateWithLifecycle()
    val logs by MediaSniffer.logs.collectAsStateWithLifecycle()
    val history by BrowserStore.history.collectAsStateWithLifecycle()
    val bookmarks by BrowserStore.bookmarks.collectAsStateWithLifecycle()

    val siteKey = remember(address, settingsVersion) { SiteSettings.siteKeyOf(address) }
    val siteDisabled = remember(siteKey, settingsVersion) {
        siteKey != null && SiteSettings.isAdBlockDisabled(address)
    }

    // 聚焦后等一帧再全选，避免被点击时的光标定位覆盖
    LaunchedEffect(addressFocused) {
        if (addressFocused) {
            delay(60)
            addressField = addressField.copy(
                selection = TextRange(0, addressField.text.length)
            )
        }
    }

    // 同步 ViewModel 的 adBlock 状态
    SideEffect { pageState.adBlock = adBlockEnabled }

    // 系统返回键处理（仅在浏览器页为当前页时生效，避免在其他页签误拦截返回键）
    BackHandler(enabled = canGoBack && isActive) {
        viewModel.goBack()
        canGoBack = viewModel.canGoBack
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 工具栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    viewModel.goBack()
                    canGoBack = viewModel.canGoBack
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
            OutlinedTextField(
                value = addressField,
                onValueChange = { addressField = it },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focus -> addressFocused = focus.isFocused },
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
                placeholder = { Text("输入网址") },
                trailingIcon = {
                    if (addressField.text.isNotEmpty()) {
                        IconButton(onClick = { addressField = TextFieldValue("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清空")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = {
                    viewModel.loadUrl(normalizeUrl(addressField.text))
                    focusManager.clearFocus()
                })
            )
            if (detected.isNotEmpty()) {
                // 嗅探入口放工具栏：底部整行按钮在键盘/布局变化后会被 WebView 抢走触摸，
                // 工具栏按钮实测可靠，角标显示数量
                BadgedBox(
                    badge = {
                        Badge { Text(detected.size.toString()) }
                    }
                ) {
                    IconButton(onClick = {
                        com.vidgrab.app.core.DiagLog.w(
                            "Sniff", "open sheet detected=${detected.size}", verbose = true
                        )
                        keyboard?.hide()
                        focusManager.clearFocus(force = true)
                        showSheet = true
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = "检测结果（${detected.size} 个媒体）",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            IconButton(onClick = { viewModel.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { sheet = BrowserSheet.MENU }) {
                Icon(Icons.Default.MoreVert, contentDescription = "菜单")
            }
        }

        // WebView - 使用 AndroidView 复用 ViewModel 中的 WebView 实例
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                // 首次创建 WebView 并交给 ViewModel 持有
                val wv = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    // 允许多窗口：window.open / 第三方登录弹窗由 PopupHost 承载，
                    // 不开启时登录授权弹窗会被直接吞掉。
                    settings.setSupportMultipleWindows(true)
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
                            pageError = null
                            pageState.url = url
                            pageState.title = view.title
                            canGoBack = view.canGoBack()
                            // 导航开始就收起键盘：否则地址栏保持焦点，输入法一直盖住下半屏，
                            // 嗅探入口点下去看起来「没反应」（其实是弹窗被键盘挡住）
                            keyboard?.hide()
                            focusManager.clearFocus(force = true)
                            viewModel.updateUrl(url)
                            viewModel.updateTitle(view.title)
                            viewModel.updateCanGoBack(view.canGoBack())
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
                                // tel: / mailto: / intent:// 交给系统处理，不再静默吞掉
                                val opened = openExternal(context, request.url)
                                MediaSniffer.log(
                                    MediaSniffer.SRC_INTERCEPT,
                                    target,
                                    if (opened) "交给外部应用打开" else "没有可打开该链接的应用"
                                )
                                if (!opened) {
                                    Toast.makeText(
                                        context,
                                        "没有可打开该链接的应用",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
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
                            // 记下当前页面的登录 Cookie，粘贴该站链接时可直接复用
                            CookieStore.rememberFromWebView(url)
                            view.evaluateJavascript(MediaSniffer.injectedJs, null)
                            address = url
                            if (!addressFocused) addressField = TextFieldValue(url)
                            onUrlChange(url)
                            canGoBack = view.canGoBack()
                            viewModel.updateUrl(url)
                            viewModel.updateTitle(view.title)
                            viewModel.updateCanGoBack(view.canGoBack())
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request.isForMainFrame) {
                                pageError = "${error.description}（错误码 ${error.errorCode}）"
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                                pageError = "服务器返回 HTTP ${errorResponse.statusCode}"
                            }
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
                            // 记下当前页面的登录 Cookie，粘贴该站链接时可直接复用
                            CookieStore.rememberFromWebView(url)
                            address = url
                            if (!addressFocused) addressField = TextFieldValue(url)
                            onUrlChange(url)
                            canGoBack = view.canGoBack()
                            viewModel.updateUrl(url)
                            viewModel.updateTitle(view.title)
                            viewModel.updateCanGoBack(view.canGoBack())
                        }
                    }

                    // 网页里点了“下载”后，WebView 默认什么都不做
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        handleWebDownload(
                            context = context,
                            url = url,
                            userAgent = userAgent ?: pageState.userAgent,
                            contentDisposition = contentDisposition,
                            mimeType = mimeType,
                            onAppDownload = onDownload
                        )
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            callback: ValueCallback<Array<Uri>>?,
                            params: FileChooserParams?
                        ): Boolean {
                            callback ?: return false
                            fileChooserCallback?.onReceiveValue(null)
                            fileChooserCallback = callback
                            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                                putExtra(
                                    Intent.EXTRA_ALLOW_MULTIPLE,
                                    params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                                )
                            }
                            return runCatching {
                                fileChooserLauncher.launch(intent)
                                true
                            }.getOrElse {
                                fileChooserCallback = null
                                false
                            }
                        }

                        override fun onJsAlert(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: JsResult?
                        ): Boolean {
                            result ?: return false
                            android.app.AlertDialog.Builder(view?.context ?: context)
                                .setTitle(view?.title ?: "提示")
                                .setMessage(message)
                                .setPositiveButton("确定") { _, _ -> result.confirm() }
                                .setOnCancelListener { result.cancel() }
                                .show()
                            return true
                        }

                        override fun onJsConfirm(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: JsResult?
                        ): Boolean {
                            result ?: return false
                            android.app.AlertDialog.Builder(view?.context ?: context)
                                .setTitle(view?.title ?: "请确认")
                                .setMessage(message)
                                .setPositiveButton("确定") { _, _ -> result.confirm() }
                                .setNegativeButton("取消") { _, _ -> result.cancel() }
                                .setOnCancelListener { result.cancel() }
                                .show()
                            return true
                        }

                        override fun onJsPrompt(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            defaultValue: String?,
                            result: JsPromptResult?
                        ): Boolean {
                            result ?: return false
                            val input = EditText(view?.context ?: context).apply {
                                setText(defaultValue.orEmpty())
                                setSingleLine()
                            }
                            android.app.AlertDialog.Builder(view?.context ?: context)
                                .setTitle(view?.title ?: "请输入")
                                .setMessage(message)
                                .setView(input)
                                .setPositiveButton("确定") { _, _ ->
                                    result.confirm(input.text?.toString().orEmpty())
                                }
                                .setNegativeButton("取消") { _, _ -> result.cancel() }
                                .setOnCancelListener { result.cancel() }
                                .show()
                            return true
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            resultMsg ?: return false
                            // 只接受用户点击触发的弹窗，避免广告自动弹
                            if (!isUserGesture) return false
                            val ua = pageState.userAgent.ifBlank {
                                view?.settings?.userAgentString.orEmpty()
                            }
                            return runCatching {
                                popupHost.show(resultMsg, ua) { uri -> openExternal(context, uri) }
                            }.getOrElse {
                                Toast.makeText(context, "无法打开弹窗", Toast.LENGTH_SHORT).show()
                                false
                            }
                        }

                        override fun onCloseWindow(window: WebView?) {
                            popupHost.dismiss()
                        }
                    }
                    loadUrl(viewModel.currentUrl)
                    viewModel.initializeWebView(this)
                }
                wv
            },
                update = { /* WebView 由 ViewModel 持有，不需要更新 */ }
            )

            pageError?.let { message ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("网页加载失败", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = {
                        pageError = null
                        viewModel.reload()
                    }) { Text("重新加载") }
                }
            }
        }

        // 嗅探入口已移到工具栏（BadgedBox 图标）：原先的底部整行按钮在键盘收起/布局变化后
        // 会被 WebView 的触摸区域抢走点击，真机上表现为「点了没反应」
    }

    // 底部弹窗：检测结果
    if (showSheet) {
        com.vidgrab.app.core.DiagLog.w("Sniff", "sheet composed", verbose = true)
        ModalBottomSheet(
            onDismissRequest = {
                com.vidgrab.app.core.DiagLog.w("Sniff", "sheet dismiss requested", verbose = true)
                showSheet = false
            },
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("全部", "HLS", "DASH", "视频", "音频").forEach { label ->
                        TextButton(
                            onClick = { mediaFilter = label },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                label,
                                color = if (mediaFilter == label) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                val filtered = detected.filter { media ->
                    when (mediaFilter) {
                        "HLS" -> media.kind == "HLS"
                        "DASH" -> media.kind == "DASH"
                        "视频" -> media.kind == "VIDEO" || media.kind == "MP4"
                        "音频" -> media.kind == "AUDIO" || media.kind == "M4A" || media.kind == "MP3"
                        else -> true
                    }
                }
                if (filtered.isEmpty()) {
                    Text("暂无该类媒体。", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    items(filtered) { media ->
                        DetectedRow(
                            media = media,
                            onPlay = {
                                showSheet = false
                                PlayerActivity.start(context, media.url, media.pageTitle)
                            },
                            onDownload = {
                                showSheet = false
                                onDownload(media.url, media.pageTitle ?: pageState.title, false)
                            },
                            onCopy = {
                                clipboard.setText(AnnotatedString(media.url))
                                Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                            },
                            onShare = { shareUrl(context, media.url) }
                        )
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // 底部弹窗：检测日志
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
                    TextButton(onClick = {
                        if (logs.isEmpty()) {
                            Toast.makeText(context, "暂无日志", Toast.LENGTH_SHORT).show()
                        } else {
                            val text = logs.joinToString("\n") { entry ->
                                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                    .format(java.util.Date(entry.time))
                                "$time [${entry.source}] ${entry.result} ${entry.url}"
                            }
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("复制全部") }
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

    // 底部弹窗：菜单
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
                MenuItem(
                    if (adBlockEnabled) "关闭广告拦截" else "开启广告拦截",
                    when {
                        siteDisabled -> "本站当前已单独禁用"
                        adBlockEnabled -> "当前开启"
                        else -> "当前关闭"
                    }
                ) {
                    adBlockEnabled = !adBlockEnabled
                    viewModel.setAdBlockEnabled(adBlockEnabled)
                    sheet = BrowserSheet.NONE
                }
                MenuItem("本站广告拦截设置", "当前站点") {
                    sheet = BrowserSheet.NONE
                    showSiteDialog = true
                }
                MenuItem("检测日志", "查看嗅探来源与结果") { sheet = BrowserSheet.LOGS }
                if (detected.isNotEmpty()) {
                    MenuItem(
                        "检测结果（${detected.size} 个媒体）",
                        "查看、复制或分享已嗅探到的地址"
                    ) {
                        keyboard?.hide()
                        focusManager.clearFocus(force = true)
                        sheet = BrowserSheet.NONE
                        showSheet = true
                    }
                }
                MenuItem("清空检测", "清空已嗅探到的媒体地址") {
                    MediaSniffer.clear()
                    sheet = BrowserSheet.NONE
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // 底部弹窗：历史/书签
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
                                    viewModel.loadUrl(entry.url)
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
                                    viewModel.loadUrl(entry.url)
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

    // 本站广告拦截设置对话框
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
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val (chipBg, chipFg) = when (media.kind) {
                "HLS" -> SniffChips.HlsBg to SniffChips.HlsFg
                "DASH" -> SniffChips.DashBg to SniffChips.DashFg
                "VIDEO", "MP4" -> SniffChips.VideoBg to SniffChips.VideoFg
                else -> SniffChips.AudioBg to SniffChips.AudioFg
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
        IconButton(onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, contentDescription = "复制链接")
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = "分享链接")
        }
    }
}

private fun shareUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "分享链接").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

private val WEB_MEDIA_EXTENSIONS = setOf(
    "mp4", "webm", "mkv", "mov", "m4v", "flv", "ts",
    "m4a", "mp3", "aac", "ogg", "opus", "wav"
)

/**
 * 网页触发下载（Content-Disposition 附件、直接点视频文件）：
 * 媒体文件转到 App 的下载页；其它文件交给系统下载器。
 */
private fun handleWebDownload(
    context: Context,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
    onAppDownload: (String, String?, Boolean) -> Unit
) {
    val rawName = fileNameFromDisposition(contentDisposition)
        ?: url.substringBefore('?').substringAfterLast('/').let { segment ->
            runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
        }
    val name = FileNames.publicFileName(rawName.ifBlank { "video" })
    val ext = name.substringAfterLast('.', "").lowercase()
    val isMedia = mimeType?.startsWith("video/") == true ||
        mimeType?.startsWith("audio/") == true ||
        ext in WEB_MEDIA_EXTENSIONS ||
        DirectDownloader.isDirectMediaUrl(url)

    if (isMedia) {
        val title = FileNames.clean(name.substringBeforeLast('.', name)).ifBlank { null }
        Toast.makeText(context, "已转到下载页", Toast.LENGTH_SHORT).show()
        onAppDownload(url, title, true)
        return
    }

    // 非媒体（zip/pdf 等）走系统下载器，不把 yt-dlp 当通用下载器
    val request = DownloadManager.Request(Uri.parse(url)).apply {
        setTitle(name)
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "VidGrab/$name")
        userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { addRequestHeader("Cookie", it) }
    }
    val queued = runCatching {
        context.getSystemService(DownloadManager::class.java)?.enqueue(request)
    }.getOrNull()
    Toast.makeText(
        context,
        if (queued != null) "已交给系统下载：$name" else "无法交给系统下载",
        Toast.LENGTH_SHORT
    ).show()
}

private fun fileNameFromDisposition(disposition: String?): String? {
    if (disposition.isNullOrBlank()) return null
    val encoded = Regex("""filename\*=([^;]+)""", RegexOption.IGNORE_CASE)
        .find(disposition)?.groupValues?.get(1)?.trim()?.trim('"')
    if (!encoded.isNullOrBlank()) {
        val value = encoded.substringAfter("''", encoded)
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
    return Regex("""filename=([^;]+)""", RegexOption.IGNORE_CASE)
        .find(disposition)?.groupValues?.get(1)?.trim()?.trim('"')
}

/** 交给系统打开 tel:/mailto:/intent:// 等外部链接；没有应用可处理时返回 false。 */
private fun openExternal(context: Context, uri: Uri): Boolean {
    val intent = if (uri.scheme?.lowercase() == "intent") {
        runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
            ?: return false
    } else {
        Intent(Intent.ACTION_VIEW, uri)
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

/**
 * window.open / target=_blank 的弹窗宿主：一个独立 WebView 的 Dialog。
 * 与主 WebView 共享 CookieManager，第三方登录完成后 Cookie 会写回主页面。
 */
private class PopupHost(private val context: Context) {

    private var dialog: Dialog? = null
    private var webView: WebView? = null

    fun dismiss() {
        val current = dialog
        dialog = null
        runCatching { current?.dismiss() }
        runCatching { webView?.destroy() }
        webView = null
    }

    fun show(resultMsg: Message, userAgent: String, onExternal: (Uri) -> Boolean): Boolean {
        dismiss()
        val popup = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.setSupportMultipleWindows(false)
            if (userAgent.isNotBlank()) settings.userAgentString = userAgent
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") {
                        onExternal(request.url)
                        return true
                    }
                    return false
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView?) {
                    dismiss()
                }
            }
        }
        val close = TextView(context).apply {
            text = "关闭 ✕"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setPadding(36, 28, 36, 28)
            setBackgroundColor(0xCC000000.toInt())
            setOnClickListener { dismiss() }
        }
        val container = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            addView(
                popup,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                close,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                )
            )
        }
        val popupDialog = Dialog(
            context,
            android.R.style.Theme_DeviceDefault_Light_NoActionBar
        ).apply {
            setContentView(
                container,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnDismissListener {
                runCatching { popup.destroy() }
                if (webView === popup) webView = null
                if (dialog === this) dialog = null
            }
        }
        dialog = popupDialog
        webView = popup
        popupDialog.show()
        (resultMsg.obj as? WebView.WebViewTransport)?.webView = popup
        resultMsg.sendToTarget()
        return true
    }
}