package com.vidgrab.app.core

enum class TaskState {
    QUEUED, DOWNLOADING, PROCESSING, DONE, ERROR, CANCELED
}

/** 一个可下载的格式选项，id 直接作为 yt-dlp 的 -f 选择器。 */
data class FormatOption(
    val id: String,
    val label: String,
    val ext: String,
    val filesize: Long,
    val audioOnly: Boolean
)

data class MediaInfo(
    val title: String,
    val uploader: String?,
    val durationSeconds: Int,
    val thumbnail: String?,
    val webpageUrl: String,
    val formats: List<FormatOption>
)

data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val selector: String,
    val audioOnly: Boolean,
    val state: TaskState = TaskState.QUEUED,
    val progress: Float = 0f,
    val speed: String = "",
    val eta: String = "",
    val filePath: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** 浏览器嗅探到的媒体资源。 */
data class DetectedMedia(
    val url: String,
    val kind: String,          // HLS / DASH / VIDEO / AUDIO
    val pageUrl: String,
    val pageTitle: String?,
    val foundAt: Long = System.currentTimeMillis()
)
