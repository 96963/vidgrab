package com.vidgrab.app

import android.app.Application
import com.vidgrab.app.core.AdBlocker
import com.vidgrab.app.core.BrowserStore
import com.vidgrab.app.core.DownloadManager
import com.vidgrab.app.core.Notifications
import com.vidgrab.app.core.SiteSettings
import com.vidgrab.app.core.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.createChannel(this)
        SiteSettings.init(this)
        BrowserStore.init(this)
        DownloadManager.init(this)
        // 首次启动在后台解压 Python / FFmpeg，避免拖慢首屏
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            AdBlocker.load(this@App)
            runCatching { YtDlpEngine.ensureReady(this@App) }
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
