package com.rainyllm.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val responseSummary: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
)

/**
 * 请求日志嵌入式预览卡片 → 点击打开二级全屏页面
 */
@Composable
fun LogViewer(
    entries: List<LogEntry>,
    onClearLog: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showFullScreen by remember { mutableStateOf(false) }

    // ── 预览卡片 ──
    Card(
        modifier = modifier.fillMaxWidth().clickable { showFullScreen = true }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("📋 请求日志", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${entries.size} 条记录${if (entries.isNotEmpty()) " · 最新 ${formatTimestamp(entries.last().timestamp)}" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("查看详情 →", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }

    // ── 全屏日志页面 ──
    if (showFullScreen) {
        FullScreenDialog(onDismiss = { showFullScreen = false }) {
            LogFullScreenContent(
                entries = entries,
                onClear = {
                    onClearLog?.invoke()
                },
                onClose = { showFullScreen = false }
            )
        }
    }
}

/**
 * 全屏 Dialog 容器 — 脱离父级滚动容器，避免 Scaffold 嵌套崩溃
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val windowInsets = WindowInsets.systemBars
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFullScreenContent(
    entries: List<LogEntry>,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()
    var showClearDialog by remember { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<LogEntry?>(null) }
    // 排序：false=倒序(最新在前，默认), true=正序(最旧在前)
    var sortAscending by remember { mutableStateOf(false) }

    // 根据排序计算展示列表
    val displayedEntries = remember(entries, sortAscending) {
        if (sortAscending) entries else entries.reversed()
    }

    // 自动滚动：倒序到顶部(最新)，正序到底部(最旧)
    LaunchedEffect(displayedEntries.size, sortAscending) {
        if (displayedEntries.isNotEmpty()) {
            if (sortAscending) {
                listState.animateScrollToItem(displayedEntries.size - 1)
            } else {
                listState.animateScrollToItem(0)
            }
        }
    }

    // 清空确认弹窗
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空日志") },
            text = { Text("确定要清除全部 ${entries.size} 条请求日志吗？此操作不可撤销喵~") },
            confirmButton = {
                TextButton(onClick = {
                    onClear()
                    showClearDialog = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    // 条目详情弹窗
    selectedEntry?.let { entry ->
        LogDetailDialog(entry = entry, onDismiss = { selectedEntry = null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📋 请求日志 (${entries.size} 条)") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { sortAscending = !sortAscending }) {
                            Icon(Icons.Default.SwapVert, contentDescription = "切换排序",
                                tint = if (sortAscending)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空日志",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📭", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("暂无请求日志", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("启动服务器后，API 请求将在此显示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(displayedEntries, key = { "${it.timestamp}_${it.method}_${it.path}" }) { entry ->
                    LogEntryCard(entry = entry, onClick = { selectedEntry = entry })
                }
            }
        }
    }
}

@Composable
private fun LogEntryCard(entry: LogEntry, onClick: () -> Unit) {
    val statusColor = when {
        entry.statusCode in 200..299 -> Color(0xFF4CAF50)
        entry.statusCode in 400..499 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 状态码标记
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = entry.statusCode.toString(),
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.method,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "${entry.elapsedMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTimestamp(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                if (entry.promptTokens > 0 || entry.completionTokens > 0) {
                    Text(
                        text = "⬆${formatToken(entry.promptTokens)} ⬇${formatToken(entry.completionTokens)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LogDetailDialog(entry: LogEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("请求详情", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = when {
                        entry.statusCode in 200..299 -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                        entry.statusCode in 400..499 -> Color(0xFFFF9800).copy(alpha = 0.15f)
                        else -> Color(0xFFF44336).copy(alpha = 0.15f)
                    },
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "${entry.method} ${entry.statusCode}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
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
                    DetailSection("📥 请求体 (${entry.requestBody.length} 字符)") {
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
    return SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(ts))
}

private fun formatToken(n: Int): String {
    return if (n >= 1000) "${n / 1000}K" else n.toString()
}