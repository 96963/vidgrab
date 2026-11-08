package com.vidgrab.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import com.vidgrab.app.core.BrowserViewModel
import com.vidgrab.app.core.DownloadManager
import com.vidgrab.app.ui.BrowserScreen
import com.vidgrab.app.ui.HOME_URL
import com.vidgrab.app.ui.HomeScreen
import com.vidgrab.app.ui.TasksScreen
import com.vidgrab.app.ui.VidGrabTheme
import com.vidgrab.app.ui.syncSystemBars

class MainActivity : ComponentActivity() {

    private val pendingUrl = mutableStateOf<String?>(null)
    private val pendingTitle = mutableStateOf<String?>(null)
    private val selectedTab = mutableIntStateOf(0)
    private val browserViewModel: BrowserViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                // 用户拒绝存储权限，公共目录写入将失败，后续会回退到私有目录
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        requestNotificationPermission()
        requestStoragePermission()
        // 恢复上次未完成的下载
        DownloadManager.resumePending()

        setContent {
            VidGrabTheme {
                // 同步状态栏/导航栏颜色
                syncSystemBars(this@MainActivity, isSystemInDarkTheme())
                var tab by selectedTab
                val pending = pendingUrl.value

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                                label = { Text("下载") }
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = { Icon(Icons.Default.Language, contentDescription = null) },
                                label = { Text("浏览器") }
                            )
                            NavigationBarItem(
                                selected = tab == 2,
                                onClick = { tab = 2 },
                                icon = { Icon(Icons.Default.List, contentDescription = null) },
                                label = { Text("任务") }
                            )
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        // 三个页面始终常驻组合，仅把非活动页面移出屏幕。
                        // 这样切页签时 WebView 不会被销毁，页面/滚动/视频播放进度得以保留。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (tab == 0) 1f else 0f)
                                .graphicsLayer {
                                    translationX = if (tab == 0) 0f else 100000f
                                }
                        ) {
                            HomeScreen(
                                initialUrl = pending,
                                initialTitle = pendingTitle.value,
                                onConsumed = {
                                    pendingUrl.value = null
                                    pendingTitle.value = null
                                },
                                onTaskStarted = { tab = 2 }
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (tab == 1) 1f else 0f)
                                .graphicsLayer {
                                    translationX = if (tab == 1) 0f else 100000f
                                }
                        ) {
                            BrowserScreen(
                                viewModel = browserViewModel,
                                isActive = tab == 1,
                                onUrlChange = { browserViewModel.updateUrl(it) },
                                onDownload = { url, title ->
                                    pendingUrl.value = url
                                    pendingTitle.value = title
                                    tab = 0
                                }
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (tab == 2) 1f else 0f)
                                .graphicsLayer {
                                    translationX = if (tab == 2) 0f else 100000f
                                }
                        ) {
                            TasksScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_BROWSER_URL)?.let { url ->
            browserViewModel.loadUrl(url)
            selectedTab.intValue = 1
            return
        }
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        val url = URL_REGEX.find(text)?.value ?: return
        pendingUrl.value = url
        selectedTab.intValue = 0
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestStoragePermission() {
        // Android 9 (API 28) 及以下需要运行时申请 WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    companion object {
        const val EXTRA_BROWSER_URL = "browser_url"
        private val URL_REGEX = Regex("""https?://[^\s]+""")
    }
}
