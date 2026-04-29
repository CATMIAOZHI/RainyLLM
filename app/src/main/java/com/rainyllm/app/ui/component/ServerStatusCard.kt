package com.rainyllm.app.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 服务器状态卡片
 * 显示端口号、运行时长、连接状态
 */
@Composable
fun ServerStatusCard(
    isRunning: Boolean,
    port: Int,
    uptimeSec: Long,
    isEngineReady: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isRunning) "🟢 服务运行中" else "⚫ 服务已停止",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                if (isRunning) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                    ) {
                        // 脉冲点由颜色指示
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("端口", port.toString())
                InfoItem("引擎", if (isEngineReady) "✅ 就绪" else "⏳ 加载中")
                InfoItem("运行时长", formatUptime(uptimeSec))
            }
        }
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatUptime(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val min = seconds / 60
    if (min < 60) return "${min}m"
    val h = min / 60
    val m = min % 60
    return "${h}h ${m}m"
}