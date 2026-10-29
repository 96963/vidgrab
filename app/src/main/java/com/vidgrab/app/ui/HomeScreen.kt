package com.vidgrab.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vidgrab.app.core.DownloadManager
import com.vidgrab.app.core.FormatOption
import com.vidgrab.app.core.MediaInfo
import com.vidgrab.app.core.YtDlpEngine
import com.vidgrab.app.core.YtDlpUpdater
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    initialUrl: String?,
    onConsumed: () -> Unit,
    onTaskStarted: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var showSettings by remember { mutableStateOf(false) }
    var ytdlpVersion by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateProgress by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(showSettings) {
        if (showSettings) {
            ytdlpVersion = YtDlpUpdater.installedVersion(context)
        }
    }

    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) {
            url = initialUrl
            info = null
            error = null
            loading = true
            runCatching { YtDlpEngine.fetchInfo(context, initialUrl) }
                .onSuccess { info = it }
                .onFailure { error = it.message ?: "解析失败" }
            loading = false
            onConsumed()
        }
    }

    fun parse() {
        val target = url.trim()
        if (target.isEmpty()) return
        loading = true
        info = null
        error = null
        scope.launch {
            runCatching { YtDlpEngine.fetchInfo(context, target) }
                .onSuccess { info = it }
                .onFailure { error = it.message ?: "解析失败" }
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "粘贴视频链接",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            placeholder = { Text("https://…") },
            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = {
                    clipboard.getText()?.text?.let { url = it }
                }) {
                    Icon(Icons.Default.Search, contentDescription = "粘贴")
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go
            )
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { parse() },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = !loading && url.isNotBlank()
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("解析中…")
            } else {
                Text("解析链接")
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        info?.let { media ->
            Spacer(Modifier.height(16.dp))
            MediaInfoCard(media) { format ->
                DownloadManager.enqueue(
                    url = media.webpageUrl,
                    title = media.title,
                    thumbnail = media.thumbnail,
                    selector = format.id,
                    audioOnly = format.audioOnly
                )
                onTaskStarted()
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("设置", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    "yt-dlp 版本：${ytdlpVersion ?: "读取中…"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                if (updating) {
                    if (updateProgress >= 0f) {
                        LinearProgressIndicator(
                            progress = { updateProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("正在下载更新…", style = MaterialTheme.typography.bodySmall)
                }
                updateMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !updating,
                    onClick = {
                        scope.launch {
                            updating = true
                            updateMessage = null
                            updateProgress = -1f
                            val result = YtDlpUpdater.update(context) { progress, _ ->
                                updateProgress = progress
                            }
                            updateMessage = when (result) {
                                is YtDlpUpdater.Result.Updated ->
                                    "已更新到 ${result.version}，新任务将使用新版本"
                                is YtDlpUpdater.Result.UpToDate ->
                                    "已是最新版本 ${result.version ?: ""}"
                                is YtDlpUpdater.Result.Failed -> result.message
                            }
                            ytdlpVersion = YtDlpUpdater.installedVersion(context)
                            updateProgress = -1f
                            updating = false
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(if (updating) "更新中…" else "检查并更新 yt-dlp")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MediaInfoCard(media: MediaInfo, onDownload: (FormatOption) -> Unit) {
    var selected by remember(media) { mutableStateOf(media.formats.first()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!media.thumbnail.isNullOrBlank()) {
                    AsyncImage(
                        model = media.thumbnail,
                        contentDescription = null,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(
                        media.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    media.uploader?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (media.durationSeconds > 0) {
                        Text(
                            formatDuration(media.durationSeconds),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("选择格式", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                media.formats.forEach { format ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = format }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == format,
                            onClick = { selected = format }
                        )
                        Text(
                            format.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (format.filesize > 0) {
                            Text(
                                formatSize(format.filesize),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onDownload(selected) },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开始下载 · ${selected.label}")
            }
        }
    }
}

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format("%.1f %s", value, units[index])
}
