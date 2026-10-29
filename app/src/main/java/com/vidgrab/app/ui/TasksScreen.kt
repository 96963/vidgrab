package com.vidgrab.app.ui

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
            TextButton(onClick = { DownloadManager.clearFinished() }) {
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
                    onRemove = { DownloadManager.remove(task.id) },
                    onRetry = { DownloadManager.retry(task.id) },
                    onOpen = {
                        task.filePath?.let { openFile(context, it) }
                    }
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
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
                }
                when (task.state) {
                    TaskState.DOWNLOADING, TaskState.QUEUED -> IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Stop, contentDescription = "取消")
                    }
                    TaskState.DONE -> IconButton(onClick = onOpen) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "打开")
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
