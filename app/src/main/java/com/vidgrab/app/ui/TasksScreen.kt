package com.vidgrab.app.ui

import android.content.Intent
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.core.DownloadManager
import com.vidgrab.app.core.DownloadTask
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
            Text("下载任务", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { showClearDialog = true },
                enabled = tasks.any { it.isFinished }
            ) {
                Text("清除已完成")
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
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onCancel = { DownloadManager.cancel(task.id) },
                    onRemove = {
                        deleteFile = DownloadManager.filesOf(task).isNotEmpty()
                        deleteTarget = task
                    },
                    onRetry = { DownloadManager.retry(task.id) },
                    onRename = { renameTarget = task },
                    onOpen = {
                        task.filePath?.let { openFile(context, it) }
                    }
                )
            }
        }
    }

    deleteTarget?.let { task ->
        val hasFiles = DownloadManager.filesOf(task).isNotEmpty()
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
        val withFiles = finished.count { DownloadManager.filesOf(it).isNotEmpty() }
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
                        "扩展名保持不变；遇到重名会自动加序号。",
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
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onRename: () -> Unit,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    if (task.state == TaskState.DONE) {
                        fileLine(task)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                when (task.state) {
                    TaskState.DOWNLOADING, TaskState.QUEUED -> IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Stop, contentDescription = "取消")
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
            if (task.state == TaskState.DOWNLOADING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append((task.progress * 100).toInt()).append('%')
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

/** 已完成任务显示相对路径与文件大小。 */
private fun fileLine(task: DownloadTask): String? {
    val file = task.filePath?.let(::File) ?: return null
    if (!file.exists()) return null
    val size = formatSize(file.length())
    return if (size.isBlank()) "VidGrab/${file.name}" else "$size · VidGrab/${file.name}"
}

private fun stateText(task: DownloadTask): String = when (task.state) {
    TaskState.QUEUED -> "排队中"
    TaskState.DOWNLOADING -> "下载中"
    TaskState.PROCESSING -> "处理中"
    TaskState.DONE -> "已完成"
    TaskState.ERROR -> "失败：${task.error ?: "未知错误"}"
    TaskState.CANCELED -> "已取消"
}

private fun openFile(context: android.content.Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    val ext = file.extension.lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "打开文件")) }
}
