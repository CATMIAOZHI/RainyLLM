package com.rainyllm.app.ui.screen

import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rainyllm.app.RainyLLMApp
import com.rainyllm.app.data.AppPreferences
import com.rainyllm.app.data.StatsRepository
import com.rainyllm.app.model.ModelRepository
import com.rainyllm.app.service.LlmServerService
import com.rainyllm.app.ui.component.DebugCard
import com.rainyllm.app.ui.component.LogViewer
import com.rainyllm.app.ui.component.ServerStatusCard

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val prefs = remember { AppPreferences(context) }

    // 从 DataStore 读取保存的端口和模型
    var port by remember { mutableIntStateOf(8080) }
    var selectedModel by remember { mutableStateOf("gemma4-e2b") }
    LaunchedEffect(Unit) {
        prefs.serverPort.collect { port = it }
    }
    LaunchedEffect(Unit) {
        prefs.selectedModel.collect { selectedModel = it }
    }

    // ── 状态同步：从 OpenAIServer.currentInstance 和 LlmServerService 拉取 ──
    var isServerRunning by remember { mutableStateOf(false) }
    var isEngineReady by remember { mutableStateOf(false) }
    var uptimeSec by remember { mutableLongStateOf(0L) }
    var requestLog by remember { mutableStateOf(listOf<com.rainyllm.app.ui.component.LogEntry>()) }
    var statsSummary by remember { mutableStateOf(StatsRepository.StatsSummary()) }
    var debugText by remember { mutableStateOf("") }

    // 记录引擎初始化错误（供 UI 显示）
    var initError by remember { mutableStateOf<String?>(null) }

    // 定时轮询服务器状态
    LaunchedEffect(Unit) {
        while (true) {
            val server = com.rainyllm.app.server.OpenAIServer.currentInstance
            isServerRunning = server?.isServerRunning == true
            isEngineReady = isServerRunning  // 服务器启动=引擎就绪
            // ★ Bug修复：同步引擎初始化错误信息到 UI
            initError = LlmServerService.lastInitError
            if (isServerRunning && server != null) {
                uptimeSec = (System.currentTimeMillis() - server.getStats().startTime) / 1000
                debugText = server.getDebugInfo()
                // 同步请求日志
                requestLog = server.getRequestLog().map { entry ->
                    com.rainyllm.app.ui.component.LogEntry(
                        timestamp = entry.timestamp,
                        method = entry.method,
                        path = entry.path,
                        statusCode = entry.statusCode,
                        elapsedMs = entry.elapsedMs,
                        requestBody = entry.requestBody,
                        responseSummary = entry.responseSummary
                    )
                }
                // 同步统计摘要
                val s = server.getStats()
                // 从请求日志计算平均耗时
                val logEntries = server.getRequestLog()
                val avgMs = if (logEntries.isNotEmpty())
                    logEntries.map { it.elapsedMs }.average().toLong() else 0L
                statsSummary = StatsRepository.StatsSummary(
                    totalRequests = s.totalRequests,
                    totalPromptTokens = s.totalPromptTokens,
                    totalCompletionTokens = s.totalCompletionTokens,
                    avgDurationMs = avgMs
                )
            }
            kotlinx.coroutines.delay(1000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题
        Text("🤖 雨晴LLM主控台", style = MaterialTheme.typography.headlineSmall)

        // 服务状态卡片
        ServerStatusCard(
            isRunning = isServerRunning,
            port = port,
            uptimeSec = uptimeSec,
            isEngineReady = isEngineReady
        )

        // 控制按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isServerRunning) {
                Button(
                    onClick = {
                        val repo = ModelRepository(RainyLLMApp.instance.modelsDir)
                        val modelFile = repo.findModelFile(selectedModel)
                            // 第 2 层：ID 直接拼接
                            ?: run {
                                val fallbackPath = java.io.File("${RainyLLMApp.instance.modelsDir}/${selectedModel}.litertlm")
                                if (fallbackPath.exists()) fallbackPath else null
                            }
                            // 第 3 层：第一个已下载模型
                            ?: repo.scanDownloadedModels().firstOrNull()?.let {
                                Log.w("Dashboard", "未找到 $selectedModel，回退到 ${it.modelInfo.id}")
                                selectedModel = it.modelInfo.id
                                kotlinx.coroutines.MainScope().launch { prefs.setSelectedModel(it.modelInfo.id) }
                                it.file
                            }
                            ?: java.io.File("${RainyLLMApp.instance.modelsDir}/gemma4-e2b.litertlm")
                        val intent = Intent(context, LlmServerService::class.java).apply {
                            action = LlmServerService.ACTION_START_SERVER
                            putExtra(LlmServerService.EXTRA_MODEL_PATH, modelFile.absolutePath)
                            putExtra(LlmServerService.EXTRA_CACHE_DIR, context.cacheDir.path)
                            putExtra(LlmServerService.EXTRA_PORT, port)
                            putExtra(LlmServerService.EXTRA_MODEL_ID, selectedModel)
                        }
                        context.startForegroundService(intent)
                        // 不在此处乐观设置 isServerRunning，由轮询从 OpenAIServer.currentInstance 同步
                        uptimeSec = 0
                    },
                    modifier = Modifier.weight(1f),
                    enabled = true
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("乖乖启动服务喵~")
                }
            } else {
                Button(
                    onClick = {
                        val intent = Intent(context, LlmServerService::class.java).apply {
                            action = LlmServerService.ACTION_STOP_SERVER
                        }
                        context.startService(intent)
                        isServerRunning = false
                        isEngineReady = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("抱抱！它要休息啦~")
                }
            }
        }

        // ★ Bug修复：引擎初始化失败时显示错误信息
        if (!isServerRunning && initError != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚠️", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "喵呜…启动失败了",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            initError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // 统计摘要
        if (statsSummary.totalRequests > 0) {
            StatsSummaryCard(statsSummary)
        }

        // Debug 诊断面板
        DebugCard(
            isRunning = isServerRunning,
            isEngineReady = isEngineReady,
            port = port,
            modelId = selectedModel,
            debugText = debugText
        )

        // 请求日志
        LogViewer(entries = requestLog)

        // 使用提示
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Text(
                text = "💡 ChatBox / Open WebUI 设置:\n" +
                       "   API 地址: http://127.0.0.1:$port\n" +
                       "   API Key: 任意值（留空即可）\n" +
                       "   模型: $selectedModel",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun StatsSummaryCard(summary: StatsRepository.StatsSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem("总请求", summary.totalRequests.toString())
            StatItem("Prompt Tokens", formatNumber(summary.totalPromptTokens))
            StatItem("Comp Tokens", formatNumber(summary.totalCompletionTokens))
            StatItem("平均耗时", "${summary.avgDurationMs}ms")
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatNumber(n: Long): String {
    return if (n >= 1000) "${n / 1000}K" else n.toString()
}