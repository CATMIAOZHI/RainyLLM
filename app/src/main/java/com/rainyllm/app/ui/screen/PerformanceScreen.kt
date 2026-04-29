package com.rainyllm.app.ui.screen

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rainyllm.app.RainyLLMApp
import kotlinx.coroutines.delay
import java.io.RandomAccessFile

@Composable
fun PerformanceScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // 实时数据
    var memInfo by remember { mutableStateOf(MemorySnapshot()) }
    var diskInfo by remember { mutableStateOf(DiskSnapshot()) }
    var cpuInfo by remember { mutableStateOf(CpuSnapshot()) }

    LaunchedEffect(Unit) {
        while (true) {
            memInfo = collectMemoryInfo(context)
            diskInfo = collectDiskInfo()
            cpuInfo = collectCpuInfo()
            delay(2000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("📊 性能监控", style = MaterialTheme.typography.headlineSmall)
        Text("数据每 2 秒刷新", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ── 内存 ──
        SectionCard("🧠 内存占用") {
            GaugeRow("系统内存", memInfo.systemUsedGb, memInfo.systemTotalGb,
                MaterialTheme.colorScheme.primary,
                subtitle = "${"%.1f".format(memInfo.systemUsedGb)} / ${"%.1f".format(memInfo.systemTotalGb)} GB")

            Spacer(Modifier.height(12.dp))

            InfoGrid(
                "应用 Java 堆" to memInfo.appJavaHeap,
                "Native 堆" to memInfo.appNativeHeap,
                "PSS 总计" to memInfo.appPss,
                "低内存" to if (memInfo.lowMemory) "⚠️ 是" else "✅ 否"
            )
        }

        // ── 环形图 ──
        SectionCard("📈 内存分布") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DonutChart(
                    label = "系统已用",
                    used = memInfo.systemUsedGb,
                    total = memInfo.systemTotalGb,
                    unit = "GB",
                    color = MaterialTheme.colorScheme.primary
                )
                // Java堆 + Native堆总和（LLM主要跑在Native）
                val appTotalMb = memInfo.appJavaUsedMb + memInfo.appNativeMb
                // 分母取 PSS，兜底用 Runtime.maxMemory
                val appMaxMb = if (memInfo.appPssMb > 0) memInfo.appPssMb
                               else memInfo.appJavaMaxMb + memInfo.appNativeMb
                DonutChart(
                    label = "引擎占用",
                    used = appTotalMb,
                    total = appMaxMb.coerceAtLeast(appTotalMb),
                    unit = "MB",
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        // ── CPU ──
        SectionCard("⚡ CPU") {
            InfoGrid(
                "架构" to cpuInfo.arch,
                "核心数" to "${cpuInfo.coreCount} 核",
                "最大频率" to cpuInfo.maxFreq,
                "BogoMIPS" to cpuInfo.bogomips
            )
        }

        // ── 磁盘 ──
        SectionCard("💾 磁盘") {
            GaugeRow("内部存储", diskInfo.usedGb, diskInfo.totalGb,
                MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
            InfoGrid(
                "总容量" to diskInfo.totalGbStr,
                "已用" to diskInfo.usedGbStr,
                "可用" to diskInfo.availGbStr,
                "模型目录" to diskInfo.modelDirSize
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ── 组件 ──────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun GaugeRow(label: String, used: Float, total: Float, color: androidx.compose.ui.graphics.Color, subtitle: String? = null) {
    val fraction = if (total > 0) (used / total).coerceIn(0f, 1f) else 0f
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.weight(1f).height(10.dp),
            color = color,
            trackColor = color.copy(alpha = 0.15f),
        )
        Spacer(Modifier.width(8.dp))
        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(40.dp))
    }
    Spacer(Modifier.height(2.dp))
    Text(subtitle ?: "${"%.1f".format(used)} / ${"%.1f".format(total)} GB",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 64.dp))
}

@Composable
private fun DonutChart(label: String, used: Float, total: Float, unit: String, color: androidx.compose.ui.graphics.Color) {
    val fraction = if (total > 0) (used / total).coerceIn(0f, 1f) else 0f
    val animatedFraction by animateFloatAsState(targetValue = fraction, animationSpec = tween(600))

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12f
            val radius = (size.minDimension - stroke) / 2f
            val topLeft = Offset((size.width - radius * 2) / 2f, (size.height - radius * 2) / 2f)
            val arcSize = Size(radius * 2, radius * 2)

            drawArc(
                color = color.copy(alpha = 0.15f),
                startAngle = -90f, sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = -90f, sweepAngle = 360f * animatedFraction,
                useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("${"%.0f".format(used)}$unit", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun InfoGrid(vararg items: Pair<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.toList().chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium)
                    }
                }
                // 奇数时占位
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ── 数据采集 ──────────────────────────────────────

data class MemorySnapshot(
    val systemTotalGb: Float = 0f,
    val systemUsedGb: Float = 0f,
    val systemAvailGb: Float = 0f,
    val appJavaHeap: String = "—",
    val appJavaUsedMb: Float = 0f,
    val appJavaMaxMb: Float = 0f,
    val appNativeHeap: String = "—",
    val appNativeMb: Float = 0f,
    val appPss: String = "—",
    val appPssBytes: Long = 0L,
    val appPssMb: Float = 0f,
    val lowMemory: Boolean = false
)

data class DiskSnapshot(
    val totalGb: Float = 0f,
    val usedGb: Float = 0f,
    val availGb: Float = 0f,
    val totalGbStr: String = "—",
    val usedGbStr: String = "—",
    val availGbStr: String = "—",
    val modelDirSize: String = "—"
)

data class CpuSnapshot(
    val arch: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "—",
    val coreCount: Int = Runtime.getRuntime().availableProcessors(),
    val maxFreq: String = "—",
    val bogomips: String = "—"
)

private fun collectMemoryInfo(context: Context): MemorySnapshot {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)

    val totalGb = mi.totalMem / (1024f * 1024f * 1024f)
    val availGb = mi.availMem / (1024f * 1024f * 1024f)
    val usedGb = totalGb - availGb

    val rt = Runtime.getRuntime()
    val javaHeapUsed = rt.totalMemory() - rt.freeMemory()
    val javaHeap = "%.1f MB".format(javaHeapUsed / (1024.0 * 1024.0))
    val javaHeapUsedMb = javaHeapUsed / (1024f * 1024f)
    val javaHeapMaxMb = rt.maxMemory() / (1024f * 1024f)

    val nativeHeap = Debug.getNativeHeapAllocatedSize()
    val nativeHeapStr = "%.1f MB".format(nativeHeap / (1024.0 * 1024.0))
    val nativeHeapMb = nativeHeap / (1024f * 1024f)

    // 应用 PSS
    var pssBytes = 0L
    try {
        val pids = intArrayOf(android.os.Process.myPid())
        val memInfos = am.getProcessMemoryInfo(pids)
        if (memInfos.isNotEmpty()) {
            pssBytes = memInfos[0].totalPss * 1024L
        }
    } catch (_: Exception) {}
    val pssStr = "%.1f MB".format(pssBytes / (1024.0 * 1024.0))
    val pssMb = pssBytes / (1024f * 1024f)

    return MemorySnapshot(
        systemTotalGb = totalGb,
        systemUsedGb = usedGb,
        systemAvailGb = availGb,
        appJavaHeap = javaHeap,
        appJavaUsedMb = javaHeapUsedMb,
        appJavaMaxMb = javaHeapMaxMb,
        appNativeHeap = nativeHeapStr,
        appNativeMb = nativeHeapMb,
        appPss = pssStr,
        appPssBytes = pssBytes,
        appPssMb = pssMb,
        lowMemory = mi.lowMemory
    )
}

private fun collectDiskInfo(): DiskSnapshot {
    val stat = StatFs(Environment.getDataDirectory().path)
    val totalBytes = stat.totalBytes
    val availBytes = stat.availableBytes
    val usedBytes = totalBytes - availBytes

    val totalGb = totalBytes / (1024f * 1024f * 1024f)
    val usedGb = usedBytes / (1024f * 1024f * 1024f)
    val availGb = availBytes / (1024f * 1024f * 1024f)

    // 模型目录大小
    var modelSize = "—"
    try {
        val dir = RainyLLMApp.instance.modelsDir
        val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        modelSize = "%.1f MB".format(size / (1024.0 * 1024.0))
    } catch (_: Exception) {}

    return DiskSnapshot(
        totalGb = totalGb, usedGb = usedGb, availGb = availGb,
        totalGbStr = "%.1f GB".format(totalGb),
        usedGbStr = "%.1f GB".format(usedGb),
        availGbStr = "%.1f GB".format(availGb),
        modelDirSize = modelSize
    )
}

private fun collectCpuInfo(): CpuSnapshot {
    var maxFreq = "—"
    var bogomips = "—"
    try {
        RandomAccessFile("/proc/cpuinfo", "r").use { reader ->
            reader.readLine()?.let { line ->
                while (true) {
                    val l = reader.readLine() ?: break
                    if (l.startsWith("BogoMIPS")) {
                        bogomips = l.split(":").lastOrNull()?.trim() ?: "—"
                    }
                    if (l.startsWith("processor")) break // 下一个CPU，停
                }
            }
        }
        // 尝试从 /sys 读最大频率
        val freqFiles = listOf(
            "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq",
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"
        )
        for (path in freqFiles) {
            try {
                val freqKhz = java.io.File(path).readText().trim().toLongOrNull()
                if (freqKhz != null) {
                    maxFreq = "%.1f GHz".format(freqKhz / 1_000_000.0)
                    break
                }
            } catch (_: Exception) {}
        }
    } catch (_: Exception) {}

    return CpuSnapshot(maxFreq = maxFreq, bogomips = bogomips)
}