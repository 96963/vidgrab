package com.vidgrab.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知栏“取消”按钮：不打开 App 也能停掉单个任务。 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_TASK) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        DownloadManager.cancel(taskId)
        Notifications.cancelTask(context, taskId)
    }

    companion object {
        const val ACTION_CANCEL_TASK = "com.vidgrab.app.action.CANCEL_TASK"
        const val EXTRA_TASK_ID = "task_id"
    }
}
