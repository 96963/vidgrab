package com.vidgrab.app.ui

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.core.DownloadManager
import com.vidgrab.app.core.DownloadTask
import com.vidgrab.app.core.PublicStorage
import com.vidgrab.app.core.YtDlpUpdater
import com.vidgrab.app.PlayerActivity
import kotlinx.coroutines.launch
import com.vidgrab.app.core.TaskState
import java.io.File

@Composable
fun TasksScreen() {
    val tasks by DownloadManager.tasks.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var deleteTarget by remember { mutableStateOf<DownloadTask?>(null) }
    var deleteFile by remember { mutableStateOf(true) }
    var renameTarget by remember { mutableStateOf<DownloadTask?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDialog by remember { mutableStateOf(false) }
    var batchDeleteFiles by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf("最新") }
    val displayed = remember(tasks, query, sortMode) {
        val filtered = tasks.filter { task ->
            query.isBlank() ||
                task.title.contains(query, ignoreCase = true) ||
                task.url.contains(query, ignoreCase = true)
        }
        when (sortMode) {
            "最早" -> filtered.sortedBy { it.createdAt }
            "状态" -> filtered.sortedWith(compareBy({ it.state.ordinal }, { -it.createdAt }))
            "名称" -> filtered.sortedBy { it.title.lowercase() }
            else -> filtered.sortedByDescending { it.createdAt }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (selecting) "已选 ${selected.size} 项" else "下载任务",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            if (selecting) {
                TextButton(onClick = {
                    selected = if (selected.size == tasks.size) emptySet()
                    else tasks.map { it.id }.toSet()
                }) { Text(if (selected.size == tasks.size && tasks.isNotEmpty()) "全不选" else "全选") }
                TextButton(onClick = {
                    selecting = false
                    selected = emptySet()
                }) { Text("取消") }
                TextButton(
                    onClick = { showBatchDialog = true },
                    enabled = selected.isNotEmpty()
                ) { Text("删除所选") }
            } else {
                TextButton(
                    onClick = {
                        selecting = true
                        selected = emptySet()
                    },
                    enabled = tasks.isNotEmpty()
                ) { Text("选择") }
                TextButton(
                    onClick = { showClearDialog = true },
                    enabled = tasks.any { it.isFinished }
                ) { Text("清除已完成") }
            }
        }
        if (tasks.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            Text(
                "还没有任务。去「下载」粘贴链接，或到「浏览器」里播放视频。",
                style = MaterialTheme.typography.bodyMedium
            )
            return@Column
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            placeholder = { Text("搜索标题或链接") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { query = "" }) { Text("清空") }
                }
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("最新", "最早", "状态", "名称").forEach { mode ->
                TextButton(onClick = { sortMode = mode }) {
                    Text(
                        mode,
                        color = if (sortMode == mode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        if (displayed.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("没有匹配的任务。", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(displayed, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    selecting = selecting,
                    selected = task.id in selected,
                    onSelect = {
                        selected = if (task.id in selected) selected - task.id else selected + task.id
                    },
                    onCancel = { DownloadManager.cancel(task.id) },
                    onPause = { DownloadManager.pause(task.id) },
                    onResume = { DownloadManager.resume(task.id) },
                    onRemove = {
                        deleteFile = DownloadManager.hasFile(task)
                        deleteTarget = task
                    },
                    onRetry = { DownloadManager.retry(task.id) },
                    onRename = { renameTarget = task },
                    onOpen = {
                        task.filePath?.let { openFile(context, it, task.title) }
                    }
                )
            }
        }
    }

    deleteTarget?.let { task ->
        val hasFiles = DownloadManager.hasFile(task)
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除任务") },
            text = {
                Column {
                    Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    if (hasFiles) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { deleteFile = !deleteFile }
                        ) {
                            Checkbox(checked = deleteFile, onCheckedChange = { deleteFile = it })
                            Text("同时删除文件")
                        }
                    } else {
                        Text(
                            "该任务没有可删除的文件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    DownloadManager.remove(task.id, deleteFile)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    if (showClearDialog) {
        val finished = tasks.filter { it.isFinished }
        val withFiles = finished.count { DownloadManager.hasFile(it) }
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除已完成") },
            text = {
                Column {
                    Text("将移除 ${finished.size} 个已结束的任务。")
                    if (withFiles > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "其中 $withFiles 个带有下载文件，可选择是否一起删除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    DownloadManager.clearFinished(deleteFiles = true)
                    showClearDialog = false
                }) { Text("连文件删除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    DownloadManager.clearFinished(deleteFiles = false)
                    showClearDialog = false
                }) { Text("仅删记录") }
            }
        )
    }

    if (showBatchDialog) {
        val chosen = tasks.filter { it.id in selected }
        val withFiles = chosen.count { DownloadManager.hasFile(it) }
        AlertDialog(
            onDismissRequest = { showBatchDialog = false },
            title = { Text("删除 ${chosen.size} 个任务") },
            text = {
                Column {
                    Text("选中的任务会从列表移除，其余任务不受影响。")
                    if (withFiles > 0) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { batchDeleteFiles = !batchDeleteFiles }
                        ) {
                            Checkbox(
                                checked = batchDeleteFiles,
                                onCheckedChange = { batchDeleteFiles = it }
                            )
                            Text("同时删除文件（$withFiles 个）")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    DownloadManager.removeAll(selected, batchDeleteFiles)
                    showBatchDialog = false
                    selecting = false
                    selected = emptySet()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDialog = false }) { Text("取消") }
            }
        )
    }

    renameTarget?.let { task ->
        var name by remember(task.id) { mutableStateOf(task.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名文件") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("文件名") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (task.state == TaskState.DONE) {
                            "扩展名保持不变；遇到重名会自动加序号。"
                        } else {
                            "下载完成后按新名称保存；遇到重名会自动加序号。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        DownloadManager.rename(task.id, name)
                        renameTarget = null
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TaskCard(
    task: DownloadTask,
    selecting: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onRename: () -> Unit,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var updatingYtdlp by remember { mutableStateOf(false) }
    var errorExpanded by remember(task.id, task.error) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selecting) Modifier.clickable(onClick = onSelect) else Modifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) {
                    Checkbox(checked = selected, onCheckedChange = { onSelect() })
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stateText(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (task.state) {
                            TaskState.DONE -> MaterialTheme.colorScheme.tertiary
                            TaskState.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (!task.warning.isNullOrBlank() &&
                        (task.state == TaskState.DONE ||
                            task.state == TaskState.PAUSED ||
                            task.state == TaskState.ERROR)
                    ) {
                        Text(
                            task.warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (supportsLivePlay(task) &&
                        (task.state == TaskState.QUEUED ||
                            task.state == TaskState.DOWNLOADING ||
                            task.state == TaskState.PAUSED ||
                            task.state == TaskState.PROCESSING)
                    ) {
                        TextButton(
                            onClick = { PlayerActivity.start(context, task.url, task.title) }
                        ) {
                            Text(
                                when (task.state) {
                                    TaskState.PAUSED -> "边下边播（网络源）"
                                    TaskState.PROCESSING -> "边下边播（正在导出）"
                                    else -> "边下边播"
                                }
                            )
                        }
                    }
                    if (task.state == TaskState.ERROR && !task.error.isNullOrBlank()) {
                        Text(
                            task.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = if (errorExpanded) 20 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row {
                            TextButton(onClick = { errorExpanded = !errorExpanded }) {
                                Text(if (errorExpanded) "收起" else "展开")
                            }
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(task.error.orEmpty()))
                                android.widget.Toast.makeText(context, "错误信息已复制", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text("复制错误") }
                        }
                    }
                    if (task.state == TaskState.ERROR) {
                        TextButton(
                            enabled = !updatingYtdlp,
                            onClick = {
                                scope.launch {
                                    updatingYtdlp = true
                                    YtDlpUpdater.update(context) { _, _ -> }
                                    updatingYtdlp = false
                                    DownloadManager.retry(task.id)
                                }
                            }
                        ) {
                            Text(if (updatingYtdlp) "正在更新 yt-dlp…" else "更新 yt-dlp 并重试")
                        }
                    }
                    if (task.state == TaskState.DONE) {
                        // 内容 Uri 查询较重，只在引用/标题变化时重新查询
                        remember(task.filePath, task.title) { fileLine(context, task) }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        remember(task.filePath, task.title) { diskNameIfDifferent(context, task) }?.let { name ->
                            Text(
                                "磁盘文件名：$name",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (!selecting) when (task.state) {
                    TaskState.DOWNLOADING, TaskState.QUEUED -> {
                        IconButton(onClick = onRename) {
                            Icon(Icons.Default.Edit, contentDescription = "重命名")
                        }
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = "暂停")
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Stop, contentDescription = "取消")
                        }
                    }
                    TaskState.PAUSED -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "继续")
                        }
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                    TaskState.DONE -> {
                        IconButton(onClick = onOpen) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "打开")
                        }
                        IconButton(onClick = onRename) {
                            Icon(Icons.Default.Edit, contentDescription = "重命名")
                        }
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                    TaskState.ERROR, TaskState.CANCELED -> {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = "重试")
                        }
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                    else -> IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
            if (task.state == TaskState.DOWNLOADING || task.state == TaskState.PROCESSING ||
                task.state == TaskState.PAUSED
            ) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        if (task.state == TaskState.PAUSED) append("已暂停 · ")
                        append((task.progress * 100).toInt()).append('%')
                        if (task.state == TaskState.PROCESSING) {
                            append(" · 正在保存到公共目录")
                        }
                        if (task.speed.isNotBlank()) append(" · ").append(task.speed)
                        if (task.eta.isNotBlank()) append(" · 剩余 ").append(task.eta)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private val DownloadTask.isFinished: Boolean
    get() = state == TaskState.DONE || state == TaskState.ERROR || state == TaskState.CANCELED

/** 任务地址本身是可播放的流（m3u8 / mp4）时，下载中也能直接看，不必等成品。 */
private fun supportsLivePlay(task: DownloadTask): Boolean {
    val url = task.url
    return url.contains(".m3u8", ignoreCase = true) ||
        url.contains(".mp4", ignoreCase = true) ||
        url.contains(".m4v", ignoreCase = true)
}

/** 已完成任务显示保存位置与文件大小。 */
private fun fileLine(context: android.content.Context, task: DownloadTask): String? {
    val ref = task.filePath ?: return null
    val info = PublicStorage.info(context, ref) ?: return null
    val size = formatSize(info.size)
    val location = "${PublicStorage.displayPath}/${info.name}"
    return if (size.isBlank()) location else "$size · $location"
}

/** 标题和磁盘文件名不一致时单独提示，避免用户按标题找不到文件。 */
private fun diskNameIfDifferent(context: android.content.Context, task: DownloadTask): String? {
    val ref = task.filePath ?: return null
    val info = PublicStorage.info(context, ref) ?: return null
    val diskBase = info.name.substringBeforeLast('.', info.name)
    return if (diskBase == task.title) null else info.name
}

private fun stateText(task: DownloadTask): String = when (task.state) {
    TaskState.QUEUED -> "排队中"
    TaskState.DOWNLOADING -> "下载中"
    TaskState.PROCESSING -> "处理中"
    TaskState.PAUSED -> "已暂停"
    TaskState.DONE -> "已完成"
    TaskState.ERROR -> "失败"
    TaskState.CANCELED -> "已取消"
}

private fun openFile(context: android.content.Context, path: String, title: String? = null) {
    // 统一用应用内播放器：系统播放器（vivo 视频）对 B 站转封装文件常出现卡顿/无声
    runCatching { com.vidgrab.app.PlayerActivity.start(context, path, title) }.onFailure {
        openWithSystem(context, path)
    }
}

private fun openWithSystem(context: android.content.Context, path: String) {
    val uri: Uri
    val mime: String
    if (PublicStorage.isContentRef(path)) {
        uri = Uri.parse(path)
        mime = context.contentResolver.getType(uri) ?: "*/*"
    } else {
        val file = File(path)
        if (!file.exists()) return
        val ext = file.extension.lowercase()
        mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "打开文件")) }
}
