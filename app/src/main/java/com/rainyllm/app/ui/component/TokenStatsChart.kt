package com.rainyllm.app.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Token 使用量折线图（简单实现）
 */
@Composable
fun TokenStatsChart(
    dataPoints: List<Int>,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📊 Token 使用趋势",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))

            if (dataPoints.isEmpty()) {
                Text(
                    "暂无数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    val maxVal = (dataPoints.maxOrNull() ?: 1).coerceAtLeast(1)
                    val stepX = size.width / (dataPoints.size - 1).coerceAtLeast(1)

                    for (i in 0 until dataPoints.size - 1) {
                        val x1 = i * stepX
                        val y1 = size.height - (dataPoints[i].toFloat() / maxVal) * size.height
                        val x2 = (i + 1) * stepX
                        val y2 = size.height - (dataPoints[i + 1].toFloat() / maxVal) * size.height

                        drawLine(
                            color = androidx.compose.ui.graphics.Color(0xFF1976D2),
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = 2.5f
                        )

                        drawCircle(
                            color = androidx.compose.ui.graphics.Color(0xFF1976D2),
                            radius = 3f,
                            center = Offset(x1, y1)
                        )
                    }

                    val lastIdx = dataPoints.size - 1
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color(0xFF1976D2),
                        radius = 3f,
                        center = Offset(lastIdx * stepX,
                            size.height - (dataPoints[lastIdx].toFloat() / maxVal) * size.height)
                    )
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("最近 ${dataPoints.size} 条", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("峰值: ${dataPoints.maxOrNull() ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}