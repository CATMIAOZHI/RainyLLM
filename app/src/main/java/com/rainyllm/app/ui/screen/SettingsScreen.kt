package com.rainyllm.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rainyllm.app.data.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ── 参数说明数据 ──────────────────────────────────────

private data class ParamInfo(val name: String, val desc: String, val recommended: String)

private val paramDocs = mapOf(
    "backend" to ParamInfo(
        "推理后端",
        "CPU：兼容性最好，所有手机都能用。\nGPU：利用手机显卡加速推理，速度更快但需要设备支持 OpenCL，部分手机可能不兼容。",
        "推荐：优先尝试 GPU，如果加载失败再切回 CPU"
    ),
    "temperature" to ParamInfo(
        "Temperature（温度）",
        "控制输出随机性。值越低回答越确定、一致；值越高越有创意、多变。\n0.0 = 完全确定性（适合代码/数学）\n2.0 = 最大随机性（适合创意写作）",
        "推荐：0.7（对话）| 0.2（代码）| 1.2（创作）"
    ),
    "topK" to ParamInfo(
        "Top-K 采样",
        "限制每步只从概率最高的 K 个词中采样。值越小输出越保守聚焦，值越大词汇越丰富。\n1 = 只选最可能的词（贪婪）\n100 = 广泛候选",
        "推荐：40（平衡准确与多样）"
    ),
    "topP" to ParamInfo(
        "Top-P（核采样）",
        "从累积概率达到 P 的最小词集合中采样。与 Top-K 互补，动态调整候选词数量。\n0.0 = 极度保守\n1.0 = 考虑所有词",
        "推荐：0.95（默认）| 0.5（精确任务）"
    ),
    "maxTokens" to ParamInfo(
        "最大输出 Token",
        "单次回复最多生成多少个 token。中文约 1.5 字/token，英文约 0.75 词/token。\n值越大回答越长，但也会增加推理时间。",
        "推荐：2048–4096（日常对话）| 8192+（长文）"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }
    val scrollState = rememberScrollState()

    // 实际持久化值（从 DataStore 读取）
    var port by remember { mutableIntStateOf(8080) }
    var backend by remember { mutableStateOf("cpu") }
    var temperature by remember { mutableFloatStateOf(0.7f) }
    var topK by remember { mutableIntStateOf(40) }
    var topP by remember { mutableFloatStateOf(0.95f) }
    var maxTokens by remember { mutableIntStateOf(4096) }
    var idleTimeout by remember { mutableIntStateOf(5) }
    var keepAlive by remember { mutableStateOf(true) }

    // 独立文本缓冲区
    var portText by remember { mutableStateOf("8080") }
    var tempText by remember { mutableStateOf("0.7") }
    var topKText by remember { mutableStateOf("40") }
    var topPText by remember { mutableStateOf("0.95") }
    var maxTokensText by remember { mutableStateOf("4096") }
    var idleTimeoutText by remember { mutableStateOf("5") }

    // 弹窗状态
    var helpDialogKey by remember { mutableStateOf<String?>(null) }

    // 从 DataStore 一次性加载初始值
    LaunchedEffect(Unit) {
        portText = prefs.serverPort.first().toString(); port = portText.toIntOrNull() ?: 8080
        temperature = prefs.temperature.first(); tempText = "%.2f".format(temperature)
        topK = prefs.topK.first(); topKText = topK.toString()
        topP = prefs.topP.first(); topPText = "%.2f".format(topP)
        maxTokens = prefs.maxTokens.first(); maxTokensText = maxTokens.toString()
        idleTimeout = prefs.idleTimeoutMin.first(); idleTimeoutText = idleTimeout.toString()
        backend = prefs.backend.first()
        keepAlive = prefs.keepAlive.first()
    }

    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { focusManager.clearFocus() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        Text("⚙️ 雨晴的小设置", style = MaterialTheme.typography.headlineSmall)

        // 服务器设置
        SettingsSection("🌐 服务器") {
            OutlinedTextField(
                value = portText,
                onValueChange = { v ->
                    portText = v
                    v.toIntOrNull()?.coerceIn(1024, 65535)?.let {
                        port = it; scope.launch { prefs.setServerPort(it) }
                    }
                },
                label = { Text("端口号") },
                supportingText = { Text("范围: 1024 – 65535 | ⚠️ 需重启服务器生效") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 模型设置
        SettingsSection("🧠 模型设置") {
            // 推理后端（草稿模式：点击只改本地状态）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = backend == "cpu",
                    onClick = { backend = "cpu" },
                    label = { Text("CPU") }
                )
                FilterChip(
                    selected = backend == "gpu",
                    onClick = { backend = "gpu" },
                    label = { Text("GPU") }
                )
                Spacer(Modifier.weight(1f))
                HelpButton { helpDialogKey = "backend" }
            }

            Spacer(Modifier.height(8.dp))

            // Temperature + Top-K
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = tempText,
                    onValueChange = { v ->
                        tempText = v
                        v.toFloatOrNull()?.coerceIn(0f, 2f)?.let { temperature = it }
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Temperature")
                            HelpButton { helpDialogKey = "temperature" }
                        }
                    },
                    supportingText = { Text("0.0 – 2.0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = topKText,
                    onValueChange = { v ->
                        topKText = v
                        v.toIntOrNull()?.coerceIn(1, 100)?.let { topK = it }
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Top-K")
                            HelpButton { helpDialogKey = "topK" }
                        }
                    },
                    supportingText = { Text("1 – 100") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Top-P + MaxTokens
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = topPText,
                    onValueChange = { v ->
                        topPText = v
                        v.toFloatOrNull()?.coerceIn(0f, 1f)?.let { topP = it }
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Top-P")
                            HelpButton { helpDialogKey = "topP" }
                        }
                    },
                    supportingText = { Text("0.0 – 1.0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = maxTokensText,
                    onValueChange = { v ->
                        maxTokensText = v
                        v.toIntOrNull()?.coerceIn(1, 32768)?.let { maxTokens = it }
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("最大 Token")
                            HelpButton { helpDialogKey = "maxTokens" }
                        }
                    },
                    supportingText = { Text("1 – 32768") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // 重置 / 保存 按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            temperature = prefs.temperature.first(); tempText = "%.2f".format(temperature)
                            topK = prefs.topK.first(); topKText = topK.toString()
                            topP = prefs.topP.first(); topPText = "%.2f".format(topP)
                            maxTokens = prefs.maxTokens.first(); maxTokensText = maxTokens.toString()
                            backend = prefs.backend.first()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("重置") }

                Button(
                    onClick = {
                        scope.launch {
                            prefs.setBackend(backend)
                            prefs.setTemperature(temperature)
                            prefs.setTopK(topK)
                            prefs.setTopP(topP)
                            prefs.setMaxTokens(maxTokens)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "💡 点击「保存」后新对话生效。参数影响随机性和输出长度，修改前建议先了解含义。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }

        // 高级设置
        SettingsSection("⏱️ 高级") {
            OutlinedTextField(
                value = idleTimeoutText,
                onValueChange = { v ->
                    idleTimeoutText = v
                    v.toIntOrNull()?.coerceIn(1, 60)?.let {
                        idleTimeout = it; scope.launch { prefs.setIdleTimeoutMin(it) }
                    }
                },
                label = { Text("空闲超时（分钟）") },
                supportingText = { Text("1 – 60 分钟 | ⚠️ 需重启服务器生效") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "💡 HTTP 服务器模式下，客户端会话超过此时长无活动将自动释放内存。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }

        // 通知保活
        SettingsSection("🔔 通知保活") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("常驻通知栏", style = MaterialTheme.typography.bodyMedium)
                    Text("显示服务器状态，防止被系统清理",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = keepAlive,
                    onCheckedChange = {
                        keepAlive = it
                        scope.launch { prefs.setKeepAlive(it) }
                        com.rainyllm.app.RainyLLMApp.instance.syncKeepAlive(it)
                    }
                )
            }
        }

        // ── 关于 ──────────────────────────────────────
        val uriHandler = LocalUriHandler.current
        SettingsSection("ℹ️ 关于 RainyLLM") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AboutRow("版本", "1.0.0")
                AboutRow("推理引擎", "LiteRT-LM (Google AI Edge)")
                AboutRow("模型", "Gemma 4 E2B / E4B")
                AboutRow("HTTP 服务", "NanoHTTPd 2.3.1")
                AboutRow("UI 框架", "Jetpack Compose + Material 3")
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        "GitHub",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(80.dp)
                    )
                    Text(
                        "CATMIAOZHI/RainyLLM",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://github.com/CATMIAOZHI/RainyLLM")
                        }
                    )
                }
                AboutRow("🐱", "Made with ☁️ by Rainy & 水晴")
            }
        }

        Spacer(Modifier.height(16.dp))
        }
    }

    // ── 参数说明弹窗 ──────────────────────────────────
    helpDialogKey?.let { key ->
        val info = paramDocs[key] ?: return@let
        AlertDialog(
            onDismissRequest = { helpDialogKey = null },
            icon = { Text("💡", style = MaterialTheme.typography.headlineSmall) },
            title = { Text(info.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(info.desc, style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    Text(
                        info.recommended,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { helpDialogKey = null }) { Text("知道啦喵~") }
            }
        )
    }
}

// ── 辅助组件 ──────────────────────────────────────

@Composable
private fun HelpButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(20.dp)) {
        Icon(
            Icons.AutoMirrored.Filled.HelpOutline,
            contentDescription = "帮助",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}