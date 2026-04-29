package com.rainyllm.app.ui.component

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 调试信息面板
 * 显示服务器状态、引擎状态、错误详情、请求统计等诊断信息
 */
@Composable
fun DebugCard(
    isRunning: Boolean,
    isEngineReady: Boolean,
    port: Int,
    modelId: String,
    debugText: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 折叠标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🐛", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Debug 诊断",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 快速状态指示灯
                    StatusDot(if (isRunning) "🟢" else "⚫", "服务")
                    Spacer(Modifier.width(4.dp))
                    StatusDot(if (isEngineReady) "✅" else "❌", "引擎")
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (expanded) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // 展开后的详细内容
            if (expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 快速概览
                    Text(
                        "📊 快速概览",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    DebugRow("服务状态", if (isRunning) "运行中" else "已停止")
                    DebugRow("引擎状态", if (isEngineReady) "就绪" else "未初始化")
                    DebugRow("端口", port.toString())
                    DebugRow("模型", modelId)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // 详细日志文本
                    Text(
                        "📋 详细诊断",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    SelectionContainer {
                        Text(
                            text = debugText.ifEmpty { "（暂无诊断数据）" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(symbol: String, label: String) {
    Text(
        text = symbol,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 1.dp)
    )
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(72.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}