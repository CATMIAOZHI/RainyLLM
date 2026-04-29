package com.rainyllm.app.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.rainyllm.app.RainyLLMApp
import com.rainyllm.app.data.AppPreferences
import com.rainyllm.app.engine.InferenceException
import com.rainyllm.app.engine.LlmEngine
import kotlinx.coroutines.*

// ── 数据模型 ───────────────────────────────────────────

data class ChatMessage(
    val id: Long,
    val role: Role,
    val content: String,
    val isStreaming: Boolean = false,
    val imageUri: Uri? = null,
    val audioUri: Uri? = null
)

enum class Role { USER, MODEL }

data class ConversationRecord(
    val id: Long,
    val title: String,
    val messages: List<ChatMessage>,
    val systemPrompt: String
)

// ── 主屏幕 ─────────────────────────────────────────────

@Composable
fun ChatTestScreen(
    modelPath: String = "",
    cacheDir: String = ""
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }

    var engine by remember { mutableStateOf<LlmEngine?>(null) }
    var isInitializing by remember { mutableStateOf(false) }
    var initError by remember { mutableStateOf<String?>(null) }
    var selectedModel by remember { mutableStateOf("gemma4-e2b") }
    // engineKey >= 0 时自动加载/重建引擎；-1 表示等待用户手动触发
    var engineKey by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        prefs.selectedModel.collect { newModel ->
            if (newModel != selectedModel) {
                selectedModel = newModel
                // 只有引擎已加载（或用户已请求加载）时才触发重建
                if (engineKey >= 0) engineKey++
            }
        }
    }

    val effectiveModelPath = modelPath.ifEmpty {
        "${RainyLLMApp.instance.modelsDir}/${selectedModel}.litertlm"
    }
    val effectiveCacheDir = cacheDir.ifEmpty { context.cacheDir.path }

    // engineKey 变化时：关闭旧引擎 → 加载新引擎（engineKey <0 时不执行）
    DisposableEffect(engineKey) {
        if (engineKey >= 0) {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            isInitializing = true
            initError = null
            engine = null
            scope.launch {
                try {
                    val newEngine = LlmEngine(
                        effectiveModelPath, effectiveCacheDir,
                        visionBackend = com.google.ai.edge.litertlm.Backend.GPU(),
                        audioBackend = com.google.ai.edge.litertlm.Backend.CPU()
                    )
                    newEngine.initialize()
                    engine = newEngine
                } catch (e: Exception) {
                    initError = if (e is com.rainyllm.app.engine.EngineInitException)
                        e.message else "初始化失败: ${e.localizedMessage}"
                } finally {
                    isInitializing = false
                }
            }
            onDispose {
                scope.cancel()
                engine?.close()
                engine = null
            }
        } else {
            onDispose { }
        }
    }

    if (engine == null) {
        EngineNotLoadedScreen(
            modelPath = effectiveModelPath,
            isInitializing = isInitializing,
            error = initError,
            onRetry = { engineKey++ }
        )
    } else {
        ChatContent(
            engine = engine!!,
            modelId = selectedModel,
            onUnload = {
                engine?.close()
                engine = null
                engineKey = -1
            }
        )
    }
}

// ── 引擎未加载界面（不变） ──────────────────────────────

@Composable
private fun EngineNotLoadedScreen(
    modelPath: String,
    isInitializing: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text("🐱☁️", style = MaterialTheme.typography.displayMedium)
            Text("模型引擎未加载", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "模型路径: $modelPath",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (error != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(error, modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = onRetry, enabled = !isInitializing, modifier = Modifier.height(48.dp)) {
                if (isInitializing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("加载中…")
                } else {
                    Text("🚀 初始化引擎")
                }
            }
        }
    }
}

// ── 聊天界面 ───────────────────────────────────────────

@Composable
private fun ChatContent(engine: LlmEngine, modelId: String, onUnload: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val prefs = remember { AppPreferences(context) }

    var messages = remember { mutableStateListOf<ChatMessage>() }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var systemPrompt by remember { mutableStateOf("") }
    var showPromptEditor by remember { mutableStateOf(false) }
    var promptDraft by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }

    // 对话历史存储
    val historyMap = remember { mutableStateMapOf<Long, ConversationRecord>() }
    val historyFile = remember { java.io.File(context.filesDir, "chat_history.json") }

    // 启动时加载历史
    LaunchedEffect(Unit) {
        val loaded = loadHistory(historyFile)
        historyMap.putAll(loaded)
    }

    // 每次 historyMap 变化时保存
    LaunchedEffect(historyMap.size) {
        if (historyMap.isNotEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                saveHistory(historyFile, historyMap.toMap())
            }
        } else {
            // 清空时删除文件
            historyFile.delete()
        }
    }

    // 从 DataStore 加载系统提示词
    LaunchedEffect(Unit) {
        prefs.systemPrompt.collect { sp ->
            systemPrompt = sp
            promptDraft = sp
        }
    }

    // 选中的媒体
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) imageUri = uri
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) audioUri = uri
    }

    // key(systemPrompt) 驱动 DisposableEffect：提示词变化时旧的 close、新的创建
    var conversation by remember { mutableStateOf<com.google.ai.edge.litertlm.Conversation?>(null) }
    DisposableEffect(systemPrompt) {
        val config = if (systemPrompt.isNotBlank())
            ConversationConfig(systemInstruction = Contents.of(systemPrompt))
        else null
        val newConv = engine.createConversation(config)
        conversation = newConv
        onDispose { newConv.close() }
    }

    // 离开对话页时自动保存当前对话
    DisposableEffect(Unit) {
        onDispose {
            saveCurrentConversation(messages, systemPrompt, historyMap)
        }
    }

    // 每轮对话完成后自动保存
    LaunchedEffect(isGenerating, messages.size) {
        if (!isGenerating && messages.isNotEmpty()) {
            // 延迟一小段确保消息状态稳定
            kotlinx.coroutines.delay(1000L)
            saveCurrentConversation(messages, systemPrompt, historyMap)
        }
    }

    // 自动滚动到底部 — 仅在用户已在底部时跟随流式输出
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty() && !listState.canScrollForward) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // ── 历史页面（独立全屏） ──
    if (showHistory) {
        HistoryPage(
            historyMap = historyMap,
            onLoad = { record ->
                messages.clear()
                messages.addAll(record.messages)
                systemPrompt = record.systemPrompt
                scope.launch { prefs.setSystemPrompt(systemPrompt) }
                showHistory = false
            },
            onDelete = { id -> historyMap.remove(id) },
            onClose = { showHistory = false }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 系统提示词条 + 操作按钮 ──
        Surface(
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Tune, contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                // 当前模型名
                Text(modelId, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { showPromptEditor = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑提示词", modifier = Modifier.size(14.dp))
                }
                IconButton(onClick = { showHistory = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.History, contentDescription = "历史", modifier = Modifier.size(14.dp))
                }
                IconButton(onClick = {
                    saveCurrentConversation(messages, systemPrompt, historyMap)
                    messages.clear()
                }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "新对话", modifier = Modifier.size(14.dp))
                }
                // 卸载模型
                var showUnloadConfirm by remember { mutableStateOf(false) }
                IconButton(onClick = { showUnloadConfirm = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "卸载模型", modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                }
                if (showUnloadConfirm) {
                    AlertDialog(
                        onDismissRequest = { showUnloadConfirm = false },
                        title = { Text("卸载模型") },
                        text = { Text("确定要卸载 ${modelId} 吗？\n内存将被释放，可在模型页面重新加载。") },
                        confirmButton = {
                            TextButton(onClick = {
                                showUnloadConfirm = false
                                saveCurrentConversation(messages, systemPrompt, historyMap)
                                onUnload()
                            }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showUnloadConfirm = false }) { Text("取消") }
                        }
                    )
                }
            }
        }

        // 对话列表
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message = message)
            }
        }

        // 媒体预览条
        if (imageUri != null || audioUri != null) {
            MediaPreviewBar(
                imageUri = imageUri,
                audioUri = audioUri,
                onRemoveImage = { imageUri = null },
                onRemoveAudio = { audioUri = null }
            )
        }

        // 输入栏
        ChatInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            onPickImage = { imagePicker.launch("image/*") },
            onPickAudio = { audioPicker.launch("audio/*") },
            onSend = {
                val conv = conversation ?: return@ChatInputBar
                val hasText = inputText.isNotBlank()
                val hasMedia = imageUri != null || audioUri != null
                if ((hasText || hasMedia) && !isGenerating) {
                    val displayText = if (hasText) inputText.trim()
                        else if (imageUri != null) "📷 请描述这张图片"
                        else "🎵 请描述这段音频"

                    val userMsg = ChatMessage(
                        id = System.currentTimeMillis(),
                        role = Role.USER,
                        content = displayText,
                        imageUri = imageUri,
                        audioUri = audioUri
                    )
                    val modelMsg = ChatMessage(
                        id = System.currentTimeMillis() + 1,
                        role = Role.MODEL,
                        content = "",
                        isStreaming = true
                    )
                    messages.add(userMsg)
                    val modelIdx = messages.size
                    messages.add(modelMsg)
                    val currentInput = inputText.trim()
                    val currentImage = imageUri
                    val currentAudio = audioUri
                    inputText = ""
                    imageUri = null
                    audioUri = null
                    isGenerating = true

                    scope.launch {
                        var fullResponse = ""
                        try {
                            val flow = if (currentImage != null || currentAudio != null) {
                                val contents = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    buildContents(context, currentInput, currentImage, currentAudio)
                                }
                                engine.generateResponseAsync(conv, Contents.of(contents))
                            } else {
                                engine.generateResponseAsync(conv, currentInput)
                            }
                            flow.collect { token ->
                                fullResponse += token
                                messages[modelIdx] = messages[modelIdx].copy(content = fullResponse)
                            }
                            messages[modelIdx] = messages[modelIdx].copy(isStreaming = false)
                        } catch (e: InferenceException) {
                            messages[modelIdx] = messages[modelIdx].copy(
                                content = fullResponse + "\n\n⚠️ 推理错误: ${e.message}",
                                isStreaming = false
                            )
                        } catch (e: Exception) {
                            messages[modelIdx] = messages[modelIdx].copy(
                                content = fullResponse + "\n\n⚠️ 错误: ${e.localizedMessage}",
                                isStreaming = false
                            )
                        } finally {
                            isGenerating = false
                        }
                    }
                }
            },
            enabled = !isGenerating && (inputText.isNotBlank() || imageUri != null || audioUri != null)
        )
    }

    // ── 系统提示词编辑弹窗 ──
    if (showPromptEditor) {
        AlertDialog(
            onDismissRequest = { showPromptEditor = false },
            title = { Text("⚙️ 系统提示词") },
            text = {
                Column {
                    Text(
                        "设定 AI 的行为风格。留空则使用默认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        maxLines = 8,
                        placeholder = { Text("例如: 你是一只小猫AI助手…") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    systemPrompt = promptDraft.trim()
                    scope.launch { prefs.setSystemPrompt(systemPrompt) }
                    showPromptEditor = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    promptDraft = systemPrompt
                    showPromptEditor = false
                }) { Text("取消") }
            }
        )
    }
}

// ── 构建多模态 Contents ─────────────────────────────────

private fun buildContents(context: android.content.Context, text: String, imageUri: Uri?, audioUri: Uri?): List<Content> {
    val list = mutableListOf<Content>()
    imageUri?.let {
        val bytes = context.contentResolver.openInputStream(it)?.readBytes()
        if (bytes != null) list.add(Content.ImageBytes(bytes))
    }
    audioUri?.let {
        val bytes = context.contentResolver.openInputStream(it)?.readBytes()
        if (bytes != null) list.add(Content.AudioBytes(bytes))
    }
    if (text.isNotBlank()) list.add(Content.Text(text))
    else if (list.isEmpty()) list.add(Content.Text("请描述"))
    return list
}

// ── 媒体预览条 ──────────────────────────────────────────

@Composable
private fun MediaPreviewBar(
    imageUri: Uri?,
    audioUri: Uri?,
    onRemoveImage: () -> Unit,
    onRemoveAudio: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        imageUri?.let {
            Box(modifier = Modifier.size(56.dp)) {
                Image(
                    painter = rememberAsyncImagePainter(it),
                    contentDescription = "预览",
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                IconButton(
                    onClick = onRemoveImage,
                    modifier = Modifier.size(18.dp).align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "移除", modifier = Modifier.size(12.dp))
                }
            }
        }
        audioUri?.let {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.height(40.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Audiotrack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("🎵 音频", style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = onRemoveAudio, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "移除", modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
    }
}

// ── 消息气泡 ───────────────────────────────────────────

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == Role.USER
    val shape = if (isUser) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
                else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center) {
                Text("🐱", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(8.dp))
        }

        Surface(shape = shape, color = bubbleColor, modifier = Modifier.widthIn(max = 280.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 图片缩略图
                message.imageUri?.let {
                    Image(
                        painter = rememberAsyncImagePainter(it),
                        contentDescription = "图片",
                        modifier = Modifier.size(width = 200.dp, height = 150.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(6.dp))
                }
                message.audioUri?.let {
                    Text("🎵 音频附件", style = MaterialTheme.typography.labelSmall, color = contentColor)
                    Spacer(Modifier.height(4.dp))
                }
                if (message.content.isNotEmpty()) {
                    Text(message.content, color = contentColor, style = MaterialTheme.typography.bodyMedium)
                }
                if (message.isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(contentColor.copy(alpha = 0.6f)))
                }
            }
        }

        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center) {
                Text("👤", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ── 历史记录页面 ─────────────────────────────────────

@Composable
private fun HistoryPage(
    historyMap: Map<Long, ConversationRecord>,
    onLoad: (ConversationRecord) -> Unit,
    onDelete: (Long) -> Unit,
    onClose: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val allRecords = remember(historyMap) {
        historyMap.values.sortedByDescending { it.id }
    }
    val filtered = remember(searchQuery, allRecords) {
        if (searchQuery.isBlank()) allRecords
        else allRecords.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📜 对话历史", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("返回") }
            }
        }

        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("搜索历史对话…") },
            singleLine = true,
            leadingIcon = { Text("🔍") },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "清除", modifier = Modifier.size(16.dp))
                    }
                }
            },
            shape = RoundedCornerShape(12.dp)
        )

        // 列表
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (searchQuery.isNotBlank()) "没有匹配的对话" else "暂无历史对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered) { record ->
                    val dateStr = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                    ).format(java.util.Date(record.id))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        onClick = { onLoad(record) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(record.title, style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2)
                                    // 附件标记
                                    val hasImg = record.messages.any { it.imageUri != null }
                                    val hasAud = record.messages.any { it.audioUri != null }
                                    if (hasImg || hasAud) {
                                        Spacer(Modifier.height(2.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (hasImg) Text("📷", style = MaterialTheme.typography.labelSmall)
                                            if (hasAud) Text("🎵", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${record.messages.size} 条消息",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        dateStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    if (record.systemPrompt.isNotBlank()) {
                                        Text(
                                            "提示词: ${record.systemPrompt.take(40)}…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            maxLines = 1
                                        )
                                    }
                                }
                                IconButton(onClick = { onDelete(record.id) }) {
                                    Icon(Icons.Default.Close, contentDescription = "删除",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 输入栏 ─────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickAudio: () -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图片按钮
            IconButton(onClick = onPickImage, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = "图片",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp))
            }
            // 音频按钮
            IconButton(onClick = onPickAudio, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Audiotrack, contentDescription = "音频",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp))
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("来和本地AI聊天吧...") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = enabled,
                modifier = Modifier.size(44.dp),
                shape = CircleShape
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── 历史持久化 ─────────────────────────────────────

private fun saveCurrentConversation(
    messages: List<ChatMessage>,
    systemPrompt: String,
    historyMap: MutableMap<Long, ConversationRecord>
) {
    if (messages.isEmpty()) return
    // 用第一条用户消息的 hash 做去重 key，避免重复保存
    val dedupKey = messages.firstOrNull { it.role == Role.USER }?.content?.hashCode() ?: messages.hashCode()
    val existing = historyMap.values.find { rec ->
        rec.messages.firstOrNull { it.role == Role.USER }?.content?.hashCode() == dedupKey
    }
    if (existing != null) return // 已保存过

    val firstUser = messages.firstOrNull { it.role == Role.USER }
    val title = firstUser?.content?.take(20) ?: "空对话"
    val record = ConversationRecord(
        id = System.currentTimeMillis(),
        title = title,
        messages = messages.toList(),
        systemPrompt = systemPrompt
    )
    historyMap[record.id] = record
}

private fun saveHistory(file: java.io.File, history: Map<Long, ConversationRecord>) {
    try {
        val root = org.json.JSONArray()
        for ((_, record) in history) {
            val obj = org.json.JSONObject()
            obj.put("id", record.id)
            obj.put("title", record.title)
            obj.put("systemPrompt", record.systemPrompt)
            val msgs = org.json.JSONArray()
            for (msg in record.messages) {
                val m = org.json.JSONObject()
                m.put("id", msg.id)
                m.put("role", msg.role.name)
                m.put("content", msg.content)
                msg.imageUri?.let { m.put("imageUri", it.toString()) }
                msg.audioUri?.let { m.put("audioUri", it.toString()) }
                msgs.put(m)
            }
            obj.put("messages", msgs)
            root.put(obj)
        }
        file.writeText(root.toString())
    } catch (_: Exception) {}
}

private fun loadHistory(file: java.io.File): Map<Long, ConversationRecord> {
    return try {
        if (!file.exists()) return emptyMap()
        val root = org.json.JSONArray(file.readText())
        val map = mutableMapOf<Long, ConversationRecord>()
        for (i in 0 until root.length()) {
            val obj = root.getJSONObject(i)
            val msgs = mutableListOf<ChatMessage>()
            val msgsArr = obj.getJSONArray("messages")
            for (j in 0 until msgsArr.length()) {
                val m = msgsArr.getJSONObject(j)
                val imgUri = m.optString("imageUri", "").ifEmpty { null }
                val audUri = m.optString("audioUri", "").ifEmpty { null }
                msgs.add(ChatMessage(
                    id = m.getLong("id"),
                    role = Role.valueOf(m.getString("role")),
                    content = m.getString("content"),
                    imageUri = imgUri?.let { Uri.parse(it) },
                    audioUri = audUri?.let { Uri.parse(it) }
                ))
            }
            val record = ConversationRecord(
                id = obj.getLong("id"),
                title = obj.getString("title"),
                messages = msgs,
                systemPrompt = obj.optString("systemPrompt", "")
            )
            map[record.id] = record
        }
        map
    } catch (_: Exception) { emptyMap() }
}
