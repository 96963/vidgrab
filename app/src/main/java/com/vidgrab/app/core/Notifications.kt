package com.vidgrab.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.vidgrab.app.MainActivity
import com.vidgrab.app.R

/**
 * 下载通知：每个任务一条独立通知（可单独取消），点通知回到“任务”页。
 * 之前所有任务共用一条通知，只能看到最后一个任务的进度。
 */
object Notifications {

    private const val CHANNEL_ID = "downloads_v2"
    private const val SERVICE_NOTIFICATION_ID = 1000
    private const val TASK_ID_BASE = 2000
    private const val TASK_ID_RANGE = 100_000

    fun createChannel(context: Context) {
        // NotificationChannel 是 API 26 才有的类，低版本直接跳过
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel),
            // LOW 在 MIUI/HyperOS 会被归入“静默通知”并隐藏操作按钮；
            // DEFAULT + 静音既能露出“取消”，又不会响铃/震动。
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 任务 id 是 UUID，映射成稳定的通知 id。 */
    fun notificationId(taskId: String): Int =
        TASK_ID_BASE + (kotlin.math.abs(taskId.hashCode().toLong()) % TASK_ID_RANGE).toInt()

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TASKS, true)
        }
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelAction(
        context: Context,
        taskId: String,
        notificationId: Int
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CANCEL_TASK
            putExtra(NotificationActionReceiver.EXTRA_TASK_ID, taskId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action(R.drawable.ic_stat_download, "取消", pending)
    }

    private fun pauseAction(
        context: Context,
        taskId: String,
        notificationId: Int
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_PAUSE_TASK
            putExtra(NotificationActionReceiver.EXTRA_TASK_ID, taskId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action(R.drawable.ic_stat_download, "暂停", pending)
    }

    private fun resumeAction(
        context: Context,
        taskId: String,
        notificationId: Int
    ): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_RESUME_TASK
            putExtra(NotificationActionReceiver.EXTRA_TASK_ID, taskId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action(R.drawable.ic_stat_download, "继续", pending)
    }

    private fun builder(context: Context, title: String, text: String?, ongoing: Boolean) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent(context))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(
                if (ongoing) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS
            )

    fun serviceNotification(context: Context) =
        builder(
            context,
            context.getString(R.string.app_name),
            context.getString(R.string.notification_running),
            true
        )
            .setProgress(0, 0, true)
            .build()

    fun showTask(context: Context, task: DownloadTask) {
        val percent = (task.progress * 100).toInt().coerceIn(0, 100)
        val paused = task.state == TaskState.PAUSED
        val text = buildString {
            if (paused) {
                append("已暂停 · ").append(percent).append('%')
            } else {
                append(percent).append('%')
                if (task.state == TaskState.PROCESSING) append(" · 正在保存到公共目录")
                if (task.speed.isNotBlank()) append(" · ").append(task.speed)
                if (task.eta.isNotBlank()) append(" · 剩余 ").append(task.eta)
            }
        }
        val id = notificationId(task.id)
        val builder = builder(context, task.title, text, !paused)
            .setProgress(100, percent, task.progress <= 0f && !paused)
        if (paused) {
            builder.addAction(resumeAction(context, task.id, id))
        } else {
            builder.addAction(pauseAction(context, task.id, id))
        }
        val notification = builder
            .addAction(cancelAction(context, task.id, id))
            .build()
        context.getSystemService<NotificationManager>()
            ?.notify(id, notification)
    }

    fun showDone(context: Context, taskId: String, title: String, warning: String? = null) {
        val text = if (warning.isNullOrBlank()) title else "$title（$warning）"
        val notification = builder(
            context,
            context.getString(R.string.notification_done),
            text,
            false
        ).build()
        context.getSystemService<NotificationManager>()
            ?.notify(notificationId(taskId), notification)
    }

    fun showError(context: Context, taskId: String, title: String) {
        val notification = builder(
            context,
            context.getString(R.string.notification_error),
            title,
            false
        ).build()
        context.getSystemService<NotificationManager>()
            ?.notify(notificationId(taskId), notification)
    }

    /** 任务结束/删除后清掉它的通知。 */
    fun cancelTask(context: Context, taskId: String) {
        runCatching {
            context.getSystemService<NotificationManager>()?.cancel(notificationId(taskId))
        }
    }

    fun serviceNotificationId() = SERVICE_NOTIFICATION_ID
}
