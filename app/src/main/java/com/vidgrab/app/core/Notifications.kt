package com.vidgrab.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.vidgrab.app.R

object Notifications {

    private const val CHANNEL_ID = "downloads"
    private const val SERVICE_NOTIFICATION_ID = 1000
    private const val TASK_NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun builder(context: Context, title: String, text: String?, ongoing: Boolean) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)

    fun serviceNotification(context: Context) =
        builder(context, context.getString(R.string.app_name), context.getString(R.string.notification_running), true)
            .setProgress(0, 0, true)
            .build()

    fun showTask(context: Context, task: DownloadTask) {
        val percent = (task.progress * 100).toInt()
        val text = buildString {
            append(percent).append('%')
            if (task.speed.isNotBlank()) append(" · ").append(task.speed)
            if (task.eta.isNotBlank()) append(" · 剩余 ").append(task.eta)
        }
        val notification = builder(context, task.title, text, true)
            .setProgress(100, percent, task.progress <= 0f)
            .build()
        context.getSystemService<NotificationManager>()
            ?.notify(TASK_NOTIFICATION_ID, notification)
    }

    fun showDone(context: Context, title: String) {
        val notification = builder(context, context.getString(R.string.notification_done), title, false).build()
        context.getSystemService<NotificationManager>()
            ?.notify(TASK_NOTIFICATION_ID, notification)
    }

    fun showError(context: Context, title: String) {
        val notification = builder(context, context.getString(R.string.notification_error), title, false).build()
        context.getSystemService<NotificationManager>()
            ?.notify(TASK_NOTIFICATION_ID, notification)
    }

    fun serviceNotificationId() = SERVICE_NOTIFICATION_ID
}
