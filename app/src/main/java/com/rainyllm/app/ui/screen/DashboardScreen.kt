package com.rainyllm.app.ui.screen

import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun DashboardScreen(isVisible: Boolean = true) {
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
    // 本地日志缓存：服务器停止后不清空，直到手动清空
    var cachedLog by remember { mutableStateOf(listOf<com.rainyllm.app.ui.component.LogEntry>()) }

    // 日志持久化文件
    val logFile = remember { java.io.File(context.filesDir, "request_logs.json") }

    // 启动时从文件加载缓存日志
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                if (logFile.exists()) {
                    val json = logFile.readText()
                    val arr = JSONArray(json)
                    val loaded = (0 until arr.length()).map { i ->
                        val obj = arr.getJSONObject(i)
                        com.rainyllm.app.ui.component.LogEntry(
                            timestamp = obj.getLong("timestamp"),
                            method = obj.getString("method"),
                            path = obj.getString("path"),
                            statusCode = obj.getInt("statusCode"),
                            elapsedMs = obj.getLong("elapsedMs"),
                            requestBody = obj.optString("requestBody", ""),
                            responseSummary = obj.optString("responseSummary", ""),
                            promptTokens = obj.optInt("promptTokens", 0),
                            completionTokens = obj.optInt("completionTokens", 0)
                        )
                    }
                    if (loaded.isNotEmpty()) {
                        cachedLog = loaded.takeLast(1000)
                    }
                }
            } catch (_: Exception) {
                // 文件损坏则忽略
            }
        }
    }

    // 记录引擎初始化错误（供 UI 显示）
    var initError by remember { mutableStateOf<String?>(null) }
    // 防抖：启动进行中标记
    var isStarting by remember { mutableStateOf(false) }

    // 定时轮询服务器状态 — 仅在 Tab 可见时运行
    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
        while (true) {
            val server = com.rainyllm.app.server.OpenAIServer.currentInstance
            isServerRunning = server?.isServerRunning == true
            isEngineReady = isServerRunning  // 服务器启动=引擎就绪
            // ★ Bug修复：同步引擎初始化错误信息到 UI
            initError = LlmServerService.lastInitError
            // 启动成功或失败时解除防抖
            if (isServerRunning || initError != null) {
                isStarting = false
            }
            if (isServerRunning && server != null) {
                uptimeSec = (System.currentTimeMillis() - server.getStats().startTime) / 1000
                debugText = server.getDebugInfo()
                // 同步请求日志：合并到本地缓存（去重）
                val serverLogs = server.getRequestLog().map { entry ->
                    com.rainyllm.app.ui.component.LogEntry(
                        timestamp = entry.timestamp,
                        method = entry.method,
                        path = entry.path,
                        statusCode = entry.statusCode,
                        elapsedMs = entry.elapsedMs,
                        requestBody = entry.requestBody,
                        responseSummary = entry.responseSummary,
                        promptTokens = entry.promptTokens,
                        completionTokens = entry.completionTokens
                    )
                }
                requestLog = serverLogs
                // 合并：更新已缓存条目的耗时/摘要 + 添加新条目
                // SSE流式中 serve() 先创建日志(耗时短)，send() 完成后 setLogEntryAt 才更新
                val serverMap = serverLogs.associateBy { "${it.timestamp}_${it.method}_${it.path}" }
                val merged = mutableListOf<com.rainyllm.app.ui.component.LogEntry>()
                for (cached in cachedLog) {
                    val key = "${cached.timestamp}_${cached.method}_${cached.path}"
                    val updated = serverMap[key]?.let { server ->
                        if (server.elapsedMs > cached.elapsedMs || server.responseSummary.isNotEmpty()) {
                            cached.copy(
                                elapsedMs = server.elapsedMs,
                                responseSummary = server.responseSummary,
                                promptTokens = server.promptTokens,
                                completionTokens = server.completionTokens
                            )
                        } else cached
                    } ?: cached
                    merged.add(updated)
                }
                val existingKeys = merged.map { "${it.timestamp}_${it.method}_${it.path}" }.toSet()
                for (server in serverLogs) {
                    if ("${server.timestamp}_${server.method}_${server.path}" !in existingKeys) {
                        merged.add(server)
                    }
                }
                cachedLog = merged.takeLast(1000)
                // 修复：每次日志更新后立即持久化，不受 LaunchedEffect 生命周期限制
                kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
                    try {
                        val arr = JSONArray()
                        cachedLog.forEach { entry ->
                            val obj = JSONObject()
                            obj.put("timestamp", entry.timestamp)
                            obj.put("method", entry.method)
                            obj.put("path", entry.path)
                            obj.put("statusCode", entry.statusCode)
                            obj.put("elapsedMs", entry.elapsedMs)
                            obj.put("requestBody", entry.requestBody)
                            obj.put("responseSummary", entry.responseSummary)
                            obj.put("promptTokens", entry.promptTokens)
                            obj.put("completionTokens", entry.completionTokens)
                            arr.put(obj)
                        }
                        logFile.writeText(arr.toString())
                    } catch (_: Exception) {}
                }
                // 统计摘要
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
                        isStarting = true
                        val repo = ModelRepository(RainyLLMApp.instance.modelsDir)
                        val modelFile = repo.findModelFile(selectedModel)
                            // 第 2 层：ID 直接拼接
                            ?: run {
                                val fallbackPath = java.io.File("${RainyLLMApp.instance.modelsDir}/${selectedModel}.litertlm")
                                if (fallbackPath.exists()) fallbackPath else null
                            }
                            // 修复：第3层回退不再静默修改 selectedModel，保护用户的选择
                            ?: repo.scanDownloadedModels().firstOrNull()?.let {
                                Log.w("Dashboard", "未找到 $selectedModel，临时回退到 ${it.modelInfo.id}（不改变选择）")
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
                    enabled = !isStarting
                ) {
                    if (isStarting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("启动中…")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("乖乖启动服务喵~")
                    }
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

        // 请求日志（使用本地缓存 + 清空回调）
        LogViewer(
            entries = cachedLog,
            onClearLog = {
                cachedLog = emptyList()
                // 立即清空持久化文件
                kotlinx.coroutines.MainScope().launch(Dispatchers.IO) {
                    try { logFile.writeText("[]") } catch (_: Exception) {}
                }
                // 同步清空服务器日志
                com.rainyllm.app.server.OpenAIServer.currentInstance?.clearRequestLog()
            }
        )

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