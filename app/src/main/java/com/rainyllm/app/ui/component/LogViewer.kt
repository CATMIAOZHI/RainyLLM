package com.rainyllm.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

/**
 * HTTP 请求日志条目（UI 层数据模型）
 */
data class LogEntry(
    val timestamp: Long,
    val method: String,
    val path: String,
    val statusCode: Int,
    val elapsedMs: Long,
    val requestBody: String = "",
    val responseSummary: String = ""
)

@Composable
fun LogViewer(
    entries: List<LogEntry>,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "📋 请求日志 (最近 ${entries.size} 条)",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Text(
                    "暂无请求",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(entries.reversed()) { entry ->
                        LogEntryRow(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    var showDetail by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDetail = true }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 状态码颜色
        val statusColor = when (entry.statusCode) {
            in 200..299 -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
            in 400..499 -> androidx.compose.ui.graphics.Color(0xFFFF9800)
            else -> androidx.compose.ui.graphics.Color(0xFFF44336)
        }

        Text(
            text = entry.statusCode.toString(),
            color = statusColor,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(32.dp)
        )
        Text(
            text = entry.method,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(40.dp)
        )
        Text(
            text = entry.path,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
            fontSize = 11.sp
        )
        Text(
            text = "${entry.elapsedMs}ms",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showDetail) {
        LogDetailDialog(entry = entry, onDismiss = { showDetail = false })
    }
}

@Composable
private fun LogDetailDialog(entry: LogEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("请求详情", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${entry.method} ${entry.statusCode}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 基本信息
                DetailSection("📌 基本信息") {
                    DetailRow("时间", formatTimestamp(entry.timestamp))
                    DetailRow("方法", entry.method)
                    DetailRow("路径", entry.path)
                    DetailRow("状态码", entry.statusCode.toString())
                    DetailRow("耗时", "${entry.elapsedMs}ms")
                }

                // 请求体
                if (entry.requestBody.isNotBlank()) {
                    DetailSection("📥 请求体") {
                        SelectionContainer {
                            Text(
                                text = entry.requestBody,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 响应摘要
                if (entry.responseSummary.isNotBlank()) {
                    DetailSection("📤 响应摘要") {
                        SelectionContainer {
                            Text(
                                text = entry.responseSummary,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(64.dp),
            fontSize = 12.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTimestamp(ts: Long): String {
    return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(ts))
}