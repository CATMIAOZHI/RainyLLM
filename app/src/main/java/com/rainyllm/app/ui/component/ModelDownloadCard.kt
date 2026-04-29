package com.rainyllm.app.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
    onExport: () -> Unit = {},
    isSelected: Boolean,
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
                Text(
                    text = model.modelInfo.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
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
                    if (isSelected) {
                        Text("🌟", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = onSelect) {
                        Text(if (isSelected) "使用中" else "选用")
                    }
                    TextButton(onClick = onExport) {
                        Text("导出")
                    }
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {
                    Button(onClick = onDownload, modifier = Modifier.height(36.dp)) {
                        Text("下载")
                    }
                }
            }
        }
    }
}