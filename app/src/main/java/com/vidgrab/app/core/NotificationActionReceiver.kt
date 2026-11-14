package com.vidgrab.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知栏“暂停/继续/取消”按钮：不打开 App 也能控制单个任务。 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        when (intent.action) {
            ACTION_CANCEL_TASK -> {
                DownloadManager.cancel(taskId)
                Notifications.cancelTask(context, taskId)
            }
            ACTION_PAUSE_TASK -> DownloadManager.pause(taskId)
            ACTION_RESUME_TASK -> DownloadManager.resume(taskId)
        }
    }

    companion object {
        const val ACTION_CANCEL_TASK = "com.vidgrab.app.action.CANCEL_TASK"
        const val ACTION_PAUSE_TASK = "com.vidgrab.app.action.PAUSE_TASK"
        const val ACTION_RESUME_TASK = "com.vidgrab.app.action.RESUME_TASK"
        const val EXTRA_TASK_ID = "task_id"
    }
}
