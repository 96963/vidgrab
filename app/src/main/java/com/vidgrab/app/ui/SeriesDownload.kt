package com.vidgrab.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vidgrab.app.core.DownloadManager
import com.vidgrab.app.core.SeriesResolver
import kotlinx.coroutines.launch

/**
 * 剧集选集弹窗：勾选分集 → 逐集解析播放地址 → 按「剧名 第NN集」入队。
 * 首页粘贴链接和内置浏览器菜单共用这一个。
 */
@Composable
fun SeriesPickerDialog(
    series: SeriesResolver.Series,
    onDismiss: () -> Unit,
    onQueued: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember(series) { mutableStateOf<Set<Int>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("《${series.title}》共 ${series.episodes.size} 集") },
        text = {
            Column {
                Text(
                    "勾选要下载的分集，文件名按「剧名 第NN集」保存。整部剧体积很大，建议先试一两集。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (busy) {
                    LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "正在解析播放地址 $done/$total $step",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已选 ${selected.size} / ${series.episodes.size} 集",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            selected = if (selected.size == series.episodes.size) {
                                emptySet()
                            } else {
                                series.episodes.map { it.number }.toSet()
                            }
                        }) {
                            Text(if (selected.size == series.episodes.size) "全不选" else "全选")
                        }
                    }
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(series.episodes, key = { it.number }) { ep ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (ep.number in selected) {
                                            selected - ep.number
                                        } else {
                                            selected + ep.number
                                        }
                                    }
                            ) {
                                Checkbox(
                                    checked = ep.number in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) {
                                            selected + ep.number
                                        } else {
                                            selected - ep.number
                                        }
                                    }
                                )
                                Text(ep.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && selected.isNotEmpty(),
                onClick = {
                    val chosen = series.episodes.filter { it.number in selected }
                    if (chosen.isEmpty()) return@TextButton
                    busy = true
                    done = 0
                    total = chosen.size
                    step = ""
                    scope.launch {
                        val streams = SeriesResolver.resolveStreams(
                            context,
                            series.copy(episodes = chosen)
                        ) { d, t, label ->
                            done = d
                            total = t
                            step = label
                        }
                        var queued = 0
                        var failed = 0
                        chosen.forEach { ep ->
                            val stream = streams.firstOrNull { it.number == ep.number }
                            if (stream == null) {
                                failed++
                                return@forEach
                            }
                            val name = SeriesResolver.fileName(series.title, stream.label, ep.number)
                            runCatching {
                                DownloadManager.enqueue(
                                    url = stream.url,
                                    title = name,
                                    thumbnail = null,
                                    selector = stream.selector,
                                    audioOnly = false
                                )
                                queued++
                            }.onFailure { failed++ }
                        }
                        busy = false
                        Toast.makeText(
                            context,
                            "已加入 $queued 个任务" + if (failed > 0) "，$failed 集解析失败" else "",
                            Toast.LENGTH_LONG
                        ).show()
                        onQueued(queued)
                        onDismiss()
                    }
                }
            ) { Text(if (busy) "解析中…" else "开始下载（${selected.size} 集）") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        }
    )
}
