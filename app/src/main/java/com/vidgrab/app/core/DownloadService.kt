package com.vidgrab.app.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 仅用于在下载期间保持进程处于前台，实际下载由 DownloadManager 执行。
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // targetSdk 35 起必须使用带类型的 startForeground（manifest 已声明 dataSync）
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            Notifications.serviceNotificationId(),
            Notifications.serviceNotification(this),
            type
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * Android 15（API 35）对 dataSync 前台服务有约 6 小时/24 小时上限，
     * 到点系统回调这里；必须尽快停止前台状态，否则会被判定 ANR。
     * 进行中的任务保留 .part，用户回到 App 后点“重试”即可续传。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadManager.onServiceTimeout()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
        }
    }
}
