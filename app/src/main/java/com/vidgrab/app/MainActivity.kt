package com.vidgrab.app

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vidgrab.app.core.DownloadManager
import com.vidgrab.app.ui.BrowserScreen
import com.vidgrab.app.ui.HOME_URL
import com.vidgrab.app.ui.HomeScreen
import com.vidgrab.app.ui.TasksScreen
import com.vidgrab.app.ui.VidGrabTheme

class MainActivity : ComponentActivity() {

    private val pendingUrl = mutableStateOf<String?>(null)
    private val browserUrl = mutableStateOf(HOME_URL)
    private val selectedTab = mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        handleIntent(intent)
        requestNotificationPermission()
        // 恢复上次未完成的下载
        DownloadManager.resumePending()

        setContent {
            VidGrabTheme {
                var tab by selectedTab
                val pending = pendingUrl.value
                val currentBrowserUrl = browserUrl.value

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
                        when (tab) {
                            0 -> HomeScreen(
                                initialUrl = pending,
                                onConsumed = { pendingUrl.value = null },
                                onTaskStarted = { tab = 2 }
                            )
                            1 -> BrowserScreen(
                                initialUrl = currentBrowserUrl,
                                onUrlChange = { browserUrl.value = it },
                                onDownload = { url ->
                                    pendingUrl.value = url
                                    tab = 0
                                }
                            )
                            else -> TasksScreen()
                        }
                    }
                }
            }
        }
    }

    private fun applySystemBars() {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val background = Color.parseColor(if (dark) "#101820" else "#EEF2F4")
        window.statusBarColor = background
        window.navigationBarColor = background
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
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
            browserUrl.value = url
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

    companion object {
        const val EXTRA_BROWSER_URL = "browser_url"
        private val URL_REGEX = Regex("""https?://[^\s]+""")
    }
}
