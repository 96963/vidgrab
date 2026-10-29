package com.vidgrab.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 下载任务的磁盘持久化：应用被杀后仍能恢复列表与未完成任务。 */
object DownloadStore {

    private const val FILE_NAME = "download_tasks.json"

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        if (file == null) {
            file = File(context.applicationContext.filesDir, FILE_NAME)
        }
    }

    @Synchronized
    fun load(): List<DownloadTask> = runCatching {
        val target = file ?: return emptyList()
        if (!target.exists()) return emptyList()
        val array = JSONArray(target.readText())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").ifBlank { return@mapNotNull null }
            val url = item.optString("url").ifBlank { return@mapNotNull null }
            val savedState = runCatching {
                TaskState.valueOf(item.optString("state"))
            }.getOrDefault(TaskState.QUEUED)
            DownloadTask(
                id = id,
                url = url,
                title = item.optString("title", url),
                thumbnail = item.optString("thumbnail").ifBlank { null },
                selector = item.optString("selector", "bestvideo*+bestaudio/best"),
                audioOnly = item.optBoolean("audioOnly", false),
                state = if (savedState == TaskState.DOWNLOADING) TaskState.QUEUED else savedState,
                progress = item.optDouble("progress", 0.0).toFloat(),
                filePath = item.optString("filePath").ifBlank { null },
                error = item.optString("error").ifBlank { null },
                createdAt = item.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(tasks: List<DownloadTask>) {
        val target = file ?: return
        runCatching {
            val array = JSONArray()
            tasks.forEach { task ->
                array.put(JSONObject().apply {
                    put("id", task.id)
                    put("url", task.url)
                    put("title", task.title)
                    put("thumbnail", task.thumbnail ?: "")
                    put("selector", task.selector)
                    put("audioOnly", task.audioOnly)
                    put("state", task.state.name)
                    put("progress", task.progress.toDouble())
                    put("filePath", task.filePath ?: "")
                    put("error", task.error ?: "")
                    put("createdAt", task.createdAt)
                })
            }
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(target)) {
                target.writeText(array.toString())
                temp.delete()
            }
        }
    }
}
