package com.rainyllm.app.ui.screen

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rainyllm.app.RainyLLMApp
import kotlinx.coroutines.delay
import java.io.RandomAccessFile

@Composable
fun PerformanceScreen(isVisible: Boolean = true) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var memInfo by remember { mutableStateOf(MemorySnapshot()) }
    var diskInfo by remember { mutableStateOf(DiskSnapshot()) }
    var cpuInfo by remember { mutableStateOf(CpuSnapshot()) }

    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
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

        // ── 卡片1：内存 ──
        SectionCard("🧠 内存") {
            // 系统内存条
            GaugeRow("系统已用", memInfo.systemUsedGb, memInfo.systemTotalGb,
                MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                "${"%.1f".format(memInfo.systemUsedGb)} / ${"%.1f".format(memInfo.systemTotalGb)} GB  |  可用 ${memInfo.systemAvailStr}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 64.dp)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            // VSS / RSS / PSS 三列
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricChip("VSS\n虚拟内存", memInfo.appVssStr, MaterialTheme.colorScheme.outline)
                MetricChip("RSS\n物理内存", memInfo.appRssStr, MaterialTheme.colorScheme.secondary)
                MetricChip("PSS\n比例分摊", memInfo.appPss, MaterialTheme.colorScheme.primary)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            // 堆内存
            InfoGrid(
                "Java 堆" to memInfo.appJavaHeap,
                "Native 堆" to memInfo.appNativeHeap,
                "HWM 峰值" to memInfo.appHwmStr,
                "模型文件(mmapped)" to memInfo.modelMappedStr
            )
        }

        // ── 卡片2：设备 ──
        SectionCard("📱 设备") {
            // CPU
            Text("CPU", style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            InfoGrid(
                "架构" to cpuInfo.arch,
                "核心数" to "${cpuInfo.coreCount} 核",
                "最大频率" to cpuInfo.maxFreq,
                "BogoMIPS" to cpuInfo.bogomips
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            // 磁盘
            Text("磁盘", style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            GaugeRow("内部存储", diskInfo.usedGb, diskInfo.totalGb,
                MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(6.dp))
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
private fun MetricChip(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.1f),
        modifier = Modifier.width(100.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontSize = 10.sp)
            Spacer(Modifier.height(2.dp))
            Text(value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = color)
        }
    }
}

@Composable
private fun GaugeRow(
    label: String,
    used: Float,
    total: Float,
    color: androidx.compose.ui.graphics.Color
) {
    val fraction = if (total > 0) (used / total).coerceIn(0f, 1f) else 0f
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.weight(1f).height(10.dp),
            color = color,
            trackColor = color.copy(alpha = 0.15f),
        )
        Spacer(Modifier.width(8.dp))
        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(36.dp))
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
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ── 数据模型 ──────────────────────────────────────

data class MemorySnapshot(
    val systemTotalGb: Float = 0f,
    val systemUsedGb: Float = 0f,
    val systemAvailGb: Float = 0f,
    val systemAvailStr: String = "—",
    val appVssStr: String = "—",
    val appRssStr: String = "—",
    val appHwmStr: String = "—",
    val appPss: String = "—",
    val appJavaHeap: String = "—",
    val appJavaUsedMb: Float = 0f,
    val appNativeHeap: String = "—",
    val modelMappedStr: String = "—"
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

// ── 数据采集 ──────────────────────────────────────

private fun collectMemoryInfo(context: Context): MemorySnapshot {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // 系统内存
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    val totalGb = mi.totalMem / (1024f * 1024f * 1024f)
    val availGb = mi.availMem / (1024f * 1024f * 1024f)
    val usedGb = totalGb - availGb
    val availStr = "%.1f GB".format(availGb)

    // /proc/self/status → VSS / RSS / HWM
    var vssKb = 0L
    var rssKb = 0L
    var hwmKb = 0L
    try {
        java.io.File("/proc/self/status").readLines().forEach { line ->
            when {
                line.startsWith("VmSize:") -> vssKb = line.split("\\s+".toRegex())[1].toLong()
                line.startsWith("VmRSS:")  -> rssKb = line.split("\\s+".toRegex())[1].toLong()
                line.startsWith("VmHWM:")  -> hwmKb = line.split("\\s+".toRegex())[1].toLong()
            }
        }
    } catch (_: Exception) {}

    // PSS
    var totalPssKb = 0
    try {
        val pids = intArrayOf(android.os.Process.myPid())
        val memInfos = am.getProcessMemoryInfo(pids)
        if (memInfos.isNotEmpty()) {
            totalPssKb = memInfos[0].totalPss
        }
    } catch (_: Exception) {}

    // Java 堆
    val rt = Runtime.getRuntime()
    val javaHeapUsed = rt.totalMemory() - rt.freeMemory()
    val javaHeap = "%.1f MB".format(javaHeapUsed / (1024.0 * 1024.0))
    val javaHeapUsedMb = javaHeapUsed / (1024f * 1024f)

    // Native 堆
    val nativeHeap = Debug.getNativeHeapAllocatedSize()
    val nativeHeapStr = "%.1f MB".format(nativeHeap / (1024.0 * 1024.0))

    // 模型文件映射估算（VSS - RSS）
    val modelMappedMb = ((vssKb - rssKb) / 1024f).coerceAtLeast(0f)
    val modelMappedStr = if (vssKb > 0 && rssKb > 0)
        "%.0f MB (VSS-RSS)".format(modelMappedMb) else "—"

    return MemorySnapshot(
        systemTotalGb = totalGb,
        systemUsedGb = usedGb,
        systemAvailGb = availGb,
        systemAvailStr = availStr,
        appVssStr = if (vssKb > 0) "%.0f MB".format(vssKb / 1024f) else "—",
        appRssStr = if (rssKb > 0) "%.0f MB".format(rssKb / 1024f) else "—",
        appHwmStr = if (hwmKb > 0) "%.0f MB".format(hwmKb / 1024f) else "—",
        appPss = "%.1f MB".format(totalPssKb / 1024.0),
        appJavaHeap = javaHeap,
        appJavaUsedMb = javaHeapUsedMb,
        appNativeHeap = nativeHeapStr,
        modelMappedStr = modelMappedStr
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
            reader.readLine()
            while (true) {
                val l = reader.readLine() ?: break
                if (l.startsWith("BogoMIPS")) {
                    bogomips = l.split(":").lastOrNull()?.trim() ?: "—"
                }
                if (l.startsWith("processor")) break
            }
        }
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
