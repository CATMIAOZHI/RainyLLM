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
        Text("数据每 2 秒刷新 | PSS = 私有内存 + 共享分摊",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ── 卡片1：系统内存概览 ──
        SectionCard("🧠 系统内存") {
            GaugeRow("系统已用", memInfo.systemUsedGb, memInfo.systemTotalGb,
                MaterialTheme.colorScheme.primary,
                subtitle = "${"%.1f".format(memInfo.systemUsedGb)} / ${"%.1f".format(memInfo.systemTotalGb)} GB")
            Spacer(Modifier.height(10.dp))
            InfoGrid(
                "可用内存" to memInfo.systemAvailStr,
                "低内存状态" to if (memInfo.lowMemory) "⚠️ 触发中" else "✅ 正常"
            )
        }

        // ── 卡片2：进程内存明细 ──
        SectionCard("📋 进程内存明细") {
            // PSS / RSS / VSS 三列对比
            Text("虚拟 vs 物理 vs 比例分摊",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricChip("VSS\n虚拟内存", memInfo.appVssStr, MaterialTheme.colorScheme.outline)
                MetricChip("RSS\n物理内存", memInfo.appRssStr, MaterialTheme.colorScheme.secondary)
                MetricChip("PSS\n比例分摊", memInfo.appPss, MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            Text("VSS 含 mmap 模型文件(不占物理内存) | RSS 为实际物理页 | PSS 按比例分摊共享库",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 10.sp)

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            // PSS 细分
            Text("PSS 组成分解",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            InfoGrid(
                "私有脏页(独占)" to memInfo.privateDirtyStr,
                "私有干净页" to memInfo.privateCleanStr,
                "共享脏页(分摊)" to memInfo.sharedDirtyStr,
                "共享干净页(分摊)" to memInfo.sharedCleanStr
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            // ── 堆内存 + 引擎占用对比 ──
            Text("堆内存 & 引擎占用估算",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))

            // Native堆 vs 私有脏页 双条对比
            val nativeMb = memInfo.appNativeMb
            val privDirtyMb = memInfo.privateDirtyMb
            val maxBar = maxOf(nativeMb, privDirtyMb, 1f)
            DualBarRow(
                label1 = "Native堆", value1 = nativeMb, max1 = maxBar,
                color1 = MaterialTheme.colorScheme.secondary,
                label2 = "私有脏页", value2 = privDirtyMb, max2 = maxBar,
                color2 = MaterialTheme.colorScheme.error,
                subtitle = "CPU推理≈Native堆 | GPU推理≈私有脏页(模型副本+shader)"
            )

            Spacer(Modifier.height(8.dp))
            InfoGrid(
                "Java 堆" to memInfo.appJavaHeap,
                "Native 堆" to memInfo.appNativeHeap,
                "私有脏页(引擎)" to memInfo.privateDirtyStr,
                "HWM 峰值" to memInfo.appHwmStr,
                "模型文件(mmapped)" to memInfo.modelMappedStr,
                "Native堆/私有脏页" to if (privDirtyMb > 0 && nativeMb > 0)
                    "%.1fx".format(privDirtyMb / nativeMb) else "—"
            )
        }

        // ── 卡片3：环形图分布 ──
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
                // 引擎占用 = RSS − Java堆（物理内存中非Java部分）
                val engineMb = memInfo.engineEstimateMb
                DonutChart(
                    label = "引擎占用\n(RSS-Java堆)",
                    used = engineMb,
                    total = (memInfo.systemTotalGb * 1024f).coerceAtLeast(engineMb + 1f),
                    unit = "MB",
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "左环：系统整体内存压力 | 右环：进程物理内存(扣除Java堆)\n\n" +
                "📐 引擎占用 = RSS − Java堆已用。RSS 是进程实际占用的物理页，\n" +
                "   包含 mmap 模型权重(按需加载)、Native堆(KV Cache+buffer)、\n" +
                "   GPU shader/模型副本，是 CPU/GPU 后端都诚实的指标。\n\n" +
                "💡 解读指南：\n" +
                "   • CPU推理：引擎占用 ≈ Native堆 + mmap已加载页(干净+脏)\n" +
                "   • GPU推理：引擎占用 = CPU部分 + GPU模型副本 + shader编译缓存\n" +
                "   • 若私有脏页/Native堆比值 >2.0 → GPU 额外副本约占一半物理内存",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
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
private fun DualBarRow(
    label1: String, value1: Float, max1: Float, color1: androidx.compose.ui.graphics.Color,
    label2: String, value2: Float, max2: Float, color2: androidx.compose.ui.graphics.Color,
    subtitle: String
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label1, modifier = Modifier.width(64.dp),
                style = MaterialTheme.typography.labelSmall, color = color1, fontSize = 10.sp)
            LinearProgressIndicator(
                progress = { if (max1 > 0) (value1 / max1).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.weight(1f).height(6.dp),
                color = color1, trackColor = color1.copy(alpha = 0.12f),
            )
            Spacer(Modifier.width(6.dp))
            Text("%.0f MB".format(value1),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace, color = color1)
        }
        Spacer(Modifier.height(3.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label2, modifier = Modifier.width(64.dp),
                style = MaterialTheme.typography.labelSmall, color = color2, fontSize = 10.sp)
            LinearProgressIndicator(
                progress = { if (max2 > 0) (value2 / max2).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.weight(1f).height(6.dp),
                color = color2, trackColor = color2.copy(alpha = 0.12f),
            )
            Spacer(Modifier.width(6.dp))
            Text("%.0f MB".format(value2),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace, color = color2)
        }
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontSize = 9.sp, modifier = Modifier.padding(start = 64.dp))
    }
}

@Composable
private fun GaugeRow(
    label: String,
    used: Float,
    total: Float,
    color: androidx.compose.ui.graphics.Color,
    subtitle: String? = null
) {
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
private fun DonutChart(
    label: String,
    used: Float,
    total: Float,
    unit: String,
    color: androidx.compose.ui.graphics.Color
) {
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
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("${"%.0f".format(used)}$unit",
                style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
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
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ── 数据模型 ──────────────────────────────────────

data class MemorySnapshot(
    // 系统级
    val systemTotalGb: Float = 0f,
    val systemUsedGb: Float = 0f,
    val systemAvailGb: Float = 0f,
    val systemAvailStr: String = "—",
    // 进程虚拟/物理/分摊
    val appVssStr: String = "—",
    val appRssStr: String = "—",
    val appHwmStr: String = "—",
    // PSS 总量
    val appPss: String = "—",
    val appPssBytes: Long = 0L,
    val appPssMb: Float = 0f,
    // PSS 细分
    val privateDirtyStr: String = "—",
    val privateDirtyMb: Float = 0f,
    val privateCleanStr: String = "—",
    val sharedDirtyStr: String = "—",
    val sharedCleanStr: String = "—",
    // 堆内存
    val appJavaHeap: String = "—",
    val appJavaUsedMb: Float = 0f,
    val appJavaMaxMb: Float = 0f,
    val appNativeHeap: String = "—",
    val appNativeMb: Float = 0f,
    // 引擎占用估算：RSS − Java堆（物理内存非Java部分）
    val engineEstimateMb: Float = 0f,
    // 模型文件映射（mmap 部分估算）
    val modelMappedStr: String = "—",
    // 状态
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

// ── 数据采集 ──────────────────────────────────────

private fun collectMemoryInfo(context: Context): MemorySnapshot {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // ── 系统内存 ──
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    val totalGb = mi.totalMem / (1024f * 1024f * 1024f)
    val availGb = mi.availMem / (1024f * 1024f * 1024f)
    val usedGb = totalGb - availGb
    val availStr = "%.1f GB".format(availGb)

    // ── /proc/self/status → VSS / RSS / HWM ──
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

    // ── PSS 及细分（通过 ActivityManager.getProcessMemoryInfo） ──
    var totalPssKb = 0
    var privateDirtyKb = 0
    var privateCleanKb = 0
    var sharedDirtyKb = 0
    var sharedCleanKb = 0
    try {
        val pids = intArrayOf(android.os.Process.myPid())
        val memInfos = am.getProcessMemoryInfo(pids)
        if (memInfos.isNotEmpty()) {
            val dm = memInfos[0]
            totalPssKb = dm.totalPss
            privateDirtyKb = dm.totalPrivateDirty
            privateCleanKb = dm.totalPrivateClean
            sharedDirtyKb = dm.totalSharedDirty
            sharedCleanKb = dm.totalSharedClean
        }
    } catch (_: Exception) {}

    // ── Java 堆 ──
    val rt = Runtime.getRuntime()
    val javaHeapUsed = rt.totalMemory() - rt.freeMemory()
    val javaHeap = "%.1f MB".format(javaHeapUsed / (1024.0 * 1024.0))
    val javaHeapUsedMb = javaHeapUsed / (1024f * 1024f)
    val javaHeapMaxMb = rt.maxMemory() / (1024f * 1024f)

    // ── Native 堆 ──
    val nativeHeap = Debug.getNativeHeapAllocatedSize()
    val nativeHeapStr = "%.1f MB".format(nativeHeap / (1024.0 * 1024.0))
    val nativeHeapMb = nativeHeap / (1024f * 1024f)

    // ── 模型文件映射估算（VSS - RSS 近似，主要来自 mmap） ──
    val modelMappedMb = ((vssKb - rssKb) / 1024f).coerceAtLeast(0f)
    val modelMappedStr = if (vssKb > 0 && rssKb > 0)
        "%.0f MB (VSS-RSS)".format(modelMappedMb) else "—"

    // ── 引擎占用估算：RSS − Java堆（物理内存中非Java部分） ──
    val engineEstimateMb = ((rssKb / 1024f) - javaHeapUsedMb).coerceAtLeast(0f)

    return MemorySnapshot(
        systemTotalGb = totalGb,
        systemUsedGb = usedGb,
        systemAvailGb = availGb,
        systemAvailStr = availStr,
        appVssStr = if (vssKb > 0) "%.0f MB".format(vssKb / 1024f) else "—",
        appRssStr = if (rssKb > 0) "%.0f MB".format(rssKb / 1024f) else "—",
        appHwmStr = if (hwmKb > 0) "%.0f MB".format(hwmKb / 1024f) else "—",
        appPss = "%.1f MB".format(totalPssKb / 1024.0),
        appPssBytes = totalPssKb * 1024L,
        appPssMb = totalPssKb / 1024f,
        privateDirtyStr = "%.1f MB".format(privateDirtyKb / 1024.0),
        privateDirtyMb = privateDirtyKb / 1024f,
        privateCleanStr = "%.1f MB".format(privateCleanKb / 1024.0),
        sharedDirtyStr = "%.1f MB".format(sharedDirtyKb / 1024.0),
        sharedCleanStr = "%.1f MB".format(sharedCleanKb / 1024.0),
        appJavaHeap = javaHeap,
        appJavaUsedMb = javaHeapUsedMb,
        appJavaMaxMb = javaHeapMaxMb,
        appNativeHeap = nativeHeapStr,
        appNativeMb = nativeHeapMb,
        engineEstimateMb = engineEstimateMb,
        modelMappedStr = modelMappedStr,
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