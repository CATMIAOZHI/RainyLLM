package com.rainyllm.app.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rainyllm.app.model.DownloadedModel

/**
 * 模型下载进度卡片
 */
@Composable
fun ModelDownloadCard(
    model: DownloadedModel,
    downloadProgress: Int,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onDownloadMirror: () -> Unit = {},
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit = {},
    onExport: () -> Unit = {},
    isSelected: Boolean,
    displayName: String = model.modelInfo.name,
    isCustom: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 模型图标
            Text("🧠", style = MaterialTheme.typography.headlineSmall)

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isCustom && model.isDownloaded) {
                        TextButton(
                            onClick = onRename,
                            contentPadding = PaddingValues(4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("✏️", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(
                    text = if (model.isDownloaded)
                        "✅ 已下载 · ${model.modelInfo.sizeGb}"
                    else
                        "📥 ${model.modelInfo.sizeGb}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (model.modelInfo.description.isNotEmpty()) {
                    Text(
                        text = model.modelInfo.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            when {
                isDownloading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                        Text(
                            "$downloadProgress%",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(onClick = onCancel) {
                        Text("取消")
                    }
                }
                model.isDownloaded -> {
                    var menuExpanded by remember { mutableStateOf(false) }
                    if (isSelected) {
                        Text("🌟", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = onSelect) {
                        Text(if (isSelected) "使用中" else "选用")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多操作",
                                modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("导出") },
                                onClick = { menuExpanded = false; onExport() }
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuExpanded = false; onDelete() }
                            )
                        }
                    }
                }
                else -> {
                    // 两个下载源：国内镜像优先
                    val hasMirror = model.modelInfo.mirrorUrl.isNotEmpty()
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (hasMirror) {
                            OutlinedButton(
                                onClick = onDownloadMirror,
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("🌏 国内镜像", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        TextButton(
                            onClick = onDownload,
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(
                                if (hasMirror) "海外原链" else "下载",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}