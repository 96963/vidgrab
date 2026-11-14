package com.vidgrab.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.vidgrab.app.core.CrashReporter
import com.vidgrab.app.core.DirectDownloader
import com.vidgrab.app.core.DownloadManager
import com.vidgrab.app.core.DownloadSettings
import com.vidgrab.app.core.ErrorMessages
import com.vidgrab.app.core.FileNames
import com.vidgrab.app.core.FormatOption
import com.vidgrab.app.core.MediaInfo
import com.vidgrab.app.core.YtDlpEngine
import com.vidgrab.app.core.YtDlpUpdater
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    initialUrl: String?,
    initialTitle: String? = null,
    /** 浏览器里点了“下载”按钮：直接走直链下载，不再让 yt-dlp 解析。 */
    initialDirect: Boolean = false,
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
    var errorCanUpdate by remember { mutableStateOf(false) }

    var showSettings by remember { mutableStateOf(false) }
    var ytdlpVersion by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateProgress by remember { mutableFloatStateOf(-1f) }
    var fragments by remember { mutableIntStateOf(DownloadSettings.fragments) }
    var aria2c by remember { mutableStateOf(DownloadSettings.aria2c) }
    var concurrent by remember { mutableIntStateOf(DownloadSettings.concurrent) }
    var wifiOnly by remember { mutableStateOf(DownloadSettings.wifiOnly) }
    var speedLimit by remember { mutableIntStateOf(DownloadSettings.speedLimitKbps) }
    var saveTreeUri by remember { mutableStateOf(DownloadSettings.saveTreeUri) }
    val saveTreeName = remember(saveTreeUri) {
        saveTreeUri?.let {
            runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull()
        } ?: "Download/VidGrab（默认）"
    }
    // 点「开始下载」后先弹窗确认/修改文件名，再入队
    var pendingDownload by remember { mutableStateOf<Pair<MediaInfo, FormatOption>?>(null) }
    var showBatch by remember { mutableStateOf(false) }
    var batchText by remember { mutableStateOf("") }
    var batchBusy by remember { mutableStateOf(false) }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            DownloadSettings.saveTreeUri = uri.toString()
            saveTreeUri = uri.toString()
        }
    }

    fun setParseError(raw: String?) {
        val friendly = ErrorMessages.describe(raw)
        error = friendly.message
        errorCanUpdate = friendly.canUpdateYtdlp
    }

    LaunchedEffect(showSettings) {
        if (showSettings) {
            ytdlpVersion = YtDlpUpdater.installedVersion(context)
        }
    }

    LaunchedEffect(initialUrl, initialDirect) {
        if (!initialUrl.isNullOrBlank()) {
            url = initialUrl
            info = null
            error = null
            errorCanUpdate = false
            if (initialDirect) {
                loading = false
                val segment = initialUrl.substringBefore('?').substringAfterLast('/')
                val name = FileNames.clean(initialTitle ?: segment).ifBlank { "video" }
                val ext = segment.substringAfterLast('.', "").lowercase()
                info = MediaInfo(
                    title = name,
                    uploader = null,
                    durationSeconds = 0,
                    thumbnail = null,
                    webpageUrl = initialUrl,
                    formats = listOf(
                        FormatOption(
                            id = DirectDownloader.DIRECT_SELECTOR,
                            label = "直接下载原始文件",
                            ext = ext.ifBlank { "mp4" },
                            filesize = 0,
                            audioOnly = false
                        )
                    )
                )
            } else {
                loading = true
                runCatching { YtDlpEngine.fetchInfo(context, initialUrl) }
                    .onSuccess { parsed ->
                        // 嗅探到的媒体常解析不出标题，用页面标题兜底
                        info = parsed.copy(
                            title = FileNames.resolveTitle(parsed.title, parsed.webpageUrl, initialTitle)
                        )
                    }
                    .onFailure { setParseError(it.message) }
                loading = false
            }
            onConsumed()
        }
    }

    fun parse() {
        val target = url.trim()
        if (target.isEmpty()) return
        loading = true
        info = null
        error = null
        errorCanUpdate = false
        scope.launch {
            runCatching { YtDlpEngine.fetchInfo(context, target) }
                .onSuccess { info = it }
                .onFailure { setParseError(it.message) }
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
        TextButton(onClick = { showBatch = true }, modifier = Modifier.align(Alignment.End)) {
            Text("批量下载多个链接 / 播放列表")
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Column {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (errorCanUpdate) {
                    TextButton(
                        enabled = !updating,
                        onClick = {
                            scope.launch {
                                updating = true
                                updateProgress = -1f
                                val result = YtDlpUpdater.update(context) { progress, _ ->
                                    updateProgress = progress
                                }
                                error = when (result) {
                                    is YtDlpUpdater.Result.Updated ->
                                        "yt-dlp 已更新到 ${result.version}，请重新解析"
                                    is YtDlpUpdater.Result.UpToDate ->
                                        "yt-dlp 已是最新 ${result.version ?: ""}，该问题可能不是解析器过期"
                                    is YtDlpUpdater.Result.Failed -> "更新失败：${result.message}"
                                }
                                errorCanUpdate = false
                                ytdlpVersion = YtDlpUpdater.installedVersion(context)
                                updateProgress = -1f
                                updating = false
                            }
                        }
                    ) {
                        Text(if (updating) "正在更新 yt-dlp…" else "更新 yt-dlp 后重试")
                    }
                }
            }
        }

        info?.let { media ->
            Spacer(Modifier.height(16.dp))
            MediaInfoCard(media) { format ->
                pendingDownload = media to format
            }
        }
    }

    pendingDownload?.let { (media, format) ->
        var name by remember(media, format) { mutableStateOf(media.title) }
        var saveSubs by remember(media, format) { mutableStateOf(false) }
        var saveThumb by remember(media, format) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("下载文件名") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "格式：${format.label}，扩展名自动决定",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { saveSubs = !saveSubs }
                    ) {
                        Checkbox(checked = saveSubs, onCheckedChange = { saveSubs = it })
                        Text("同时保存字幕（中/英）", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { saveThumb = !saveThumb }
                    ) {
                        Checkbox(checked = saveThumb, onCheckedChange = { saveThumb = it })
                        Text("同时保存封面", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        DownloadManager.enqueue(
                            url = media.webpageUrl,
                            title = FileNames.clean(name).ifBlank { media.title },
                            thumbnail = media.thumbnail,
                            selector = format.id,
                            audioOnly = format.audioOnly,
                            writeSubs = saveSubs,
                            writeThumbnail = saveThumb
                        )
                        pendingDownload = null
                        onTaskStarted()
                    }
                ) { Text("开始下载") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = null }) { Text("取消") }
            }
        )
    }

    if (showBatch) {
        AlertDialog(
            onDismissRequest = { if (!batchBusy) showBatch = false },
            title = { Text("批量下载") },
            text = {
                Column {
                    Text(
                        "每行一个链接；播放列表会自动展开，全部按最佳画质下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = batchText,
                        onValueChange = { batchText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        shape = RoundedCornerShape(8.dp),
                        placeholder = { Text("https://…\nhttps://…") }
                    )
                    if (batchBusy) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在解析并入队…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !batchBusy && batchText.isNotBlank(),
                    onClick = {
                        val targets = batchText.split(Regex("""\s+"""))
                            .map { it.trim() }
                            .filter { it.startsWith("http://") || it.startsWith("https://") }
                            .distinct()
                        if (targets.isEmpty()) return@TextButton
                        batchBusy = true
                        scope.launch {
                            var queued = 0
                            var failed = 0
                            targets.forEach { target ->
                                val entries = runCatching {
                                    YtDlpEngine.fetchPlaylistEntries(context, target)
                                }.getOrDefault(emptyList())
                                if (entries.isNotEmpty()) {
                                    entries.forEach { (entryUrl, entryTitle) ->
                                        DownloadManager.enqueue(
                                            url = entryUrl,
                                            title = FileNames.clean(entryTitle).ifBlank { "视频" },
                                            thumbnail = null,
                                            selector = "bestvideo*+bestaudio/best",
                                            audioOnly = false
                                        )
                                        queued++
                                    }
                                } else {
                                    runCatching { YtDlpEngine.fetchInfo(context, target) }
                                        .onSuccess { media ->
                                            val format = media.formats.firstOrNull {
                                                it.id == "bestvideo*+bestaudio/best"
                                            } ?: media.formats.first()
                                            DownloadManager.enqueue(
                                                url = media.webpageUrl,
                                                title = media.title,
                                                thumbnail = media.thumbnail,
                                                selector = format.id,
                                                audioOnly = format.audioOnly
                                            )
                                            queued++
                                        }
                                        .onFailure { failed++ }
                                }
                            }
                            batchBusy = false
                            batchText = ""
                            showBatch = false
                            if (queued > 0) onTaskStarted()
                            android.widget.Toast.makeText(
                                context,
                                if (failed > 0) "已加入 $queued 个任务，$failed 个解析失败" else "已加入 $queued 个任务",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                ) { Text(if (batchBusy) "处理中…" else "开始下载") }
            },
            dismissButton = {
                TextButton(enabled = !batchBusy, onClick = { showBatch = false }) { Text("取消") }
            }
        )
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
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
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text("下载设置", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "分片并发（HLS/DASH），新任务生效",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadSettings.FRAGMENT_OPTIONS.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    fragments = option
                                    DownloadSettings.fragments = option
                                }
                                .padding(horizontal = 4.dp)
                        ) {
                            RadioButton(
                                selected = fragments == option,
                                onClick = {
                                    fragments = option
                                    DownloadSettings.fragments = option
                                }
                            )
                            Text("$option", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            aria2c = !aria2c
                            DownloadSettings.aria2c = aria2c
                        }
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("多线程下载（aria2c）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "对普通 MP4 等单文件更有效；HLS/DASH 仍由内置下载器处理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = aria2c,
                        onCheckedChange = {
                            aria2c = it
                            DownloadSettings.aria2c = it
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("同时下载数（新任务生效）", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadSettings.CONCURRENT_OPTIONS.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    concurrent = option
                                    DownloadManager.setConcurrent(option)
                                }
                                .padding(horizontal = 4.dp)
                        ) {
                            RadioButton(
                                selected = concurrent == option,
                                onClick = {
                                    concurrent = option
                                    DownloadManager.setConcurrent(option)
                                }
                            )
                            Text("$option", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            wifiOnly = !wifiOnly
                            DownloadSettings.wifiOnly = wifiOnly
                        }
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("仅 Wi-Fi 下载", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "移动数据下任务会暂停，回到 Wi-Fi 自动继续",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = {
                            wifiOnly = it
                            DownloadSettings.wifiOnly = it
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("限速", style = MaterialTheme.typography.bodyMedium)
                DownloadSettings.SPEED_LIMIT_OPTIONS.chunked(3).forEach { options ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        options.forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        speedLimit = option
                                        DownloadSettings.speedLimitKbps = option
                                    }
                                    .padding(horizontal = 4.dp)
                            ) {
                                RadioButton(
                                    selected = speedLimit == option,
                                    onClick = {
                                        speedLimit = option
                                        DownloadSettings.speedLimitKbps = option
                                    }
                                )
                                Text(
                                    when (option) {
                                        0 -> "不限"
                                        else -> if (option >= 1024) "${option / 1024} MB/s" else "$option KB/s"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("保存位置", style = MaterialTheme.typography.bodyMedium)
                Text(
                    saveTreeName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row {
                    Button(
                        onClick = { treeLauncher.launch(null) },
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("选择目录") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            DownloadSettings.saveTreeUri = null
                            saveTreeUri = null
                        }
                    ) { Text("恢复默认") }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { CrashReporter.share(context) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) { Text("反馈 / 导出崩溃日志") }
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
