package com.rainyllm.app.ui.screen

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rainyllm.app.RainyLLMApp
import com.rainyllm.app.data.AppPreferences
import com.rainyllm.app.model.DownloadedModel
import com.rainyllm.app.model.ModelDownloader
import com.rainyllm.app.model.ModelInfo
import com.rainyllm.app.model.ModelRepository
import com.rainyllm.app.model.ModelValidator
import com.rainyllm.app.ui.component.ModelDownloadCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelManagerScreen(isVisible: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }

    val modelsDir = RainyLLMApp.instance.modelsDir
    val repo = remember { ModelRepository(modelsDir) }
    val downloader = remember { ModelDownloader(context) }

    var models by remember { mutableStateOf(repo.getAllModels()) }
    var selectedModelId by remember { mutableStateOf("gemma4-e2b") }

    // 从 DataStore 加载已保存的模型选择（无条件恢复用户选择）
    LaunchedEffect(Unit) {
        prefs.selectedModel.collect { savedModel ->
            selectedModelId = savedModel
        }
    }
    var downloadProgresses by remember { mutableStateOf(mapOf<String, Int>()) }
    var downloadingIds by remember { mutableStateOf(setOf<String>()) }
    var downloadIdsMap by remember { mutableStateOf(mapOf<String, Long>()) }

    // 存储空间
    var storageWarning by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    // ── 自定义模型名 ──
    var customNames by remember { mutableStateOf(mapOf<String, String>()) }
    var renameModelId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        prefs.modelCustomNames.collect { jsonStr ->
            customNames = try {
                val obj = org.json.JSONObject(jsonStr)
                val map = mutableMapOf<String, String>()
                obj.keys().forEach { key -> map[key] = obj.getString(key) }
                map
            } catch (_: Exception) { emptyMap() }
        }
    }

    fun isCustomModel(modelId: String): Boolean =
        ModelInfo.PRESET_MODELS.none { it.id == modelId }

    // ── 文件选择器：导入 ──
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val fileName = try {
                // 尝试从 URI 获取文件名
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) it.getString(idx) else "imported.litertlm"
                    } else "imported.litertlm"
                } ?: "imported.litertlm"
            } catch (_: Exception) { "imported.litertlm" }

            if (!fileName.endsWith(".litertlm")) {
                importMessage = "❌ 只支持 .litertlm 格式的模型文件喵~"
                return@launch
            }

            importMessage = "⏳ 正在导入 $fileName …"
            val result = withContext(Dispatchers.IO) {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext null
                repo.importModelFromStream(inputStream, fileName)
            }
            importMessage = if (result != null) {
                models = repo.getAllModels()
                // 导入成功后自动选中
                val downloaded = repo.scanDownloadedModels()
                    .find { it.file.name == fileName }
                if (downloaded != null) {
                    selectedModelId = downloaded.modelInfo.id
                    scope.launch { prefs.setSelectedModel(downloaded.modelInfo.id) }
                }
                "✅ 导入成功！${result.name}"
            } else {
                "❌ 导入失败，请检查文件是否完整喵~"
            }
        }
    }

    // ── 文件创建器：导出 ──
    var exportModelId by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val modelId = exportModelId ?: return@rememberLauncherForActivityResult
        exportModelId = null
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            importMessage = "⏳ 正在导出…"
            val ok = withContext(Dispatchers.IO) {
                try {
                    val sourceFile = repo.getModelFile(modelId)
                    if (!sourceFile.exists()) return@withContext false
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            importMessage = if (ok) "✅ 导出成功！" else "❌ 导出失败喵~"
        }
    }

    // 修复：仅在 Tab 可见或有下载任务时轮询进度
    LaunchedEffect(isVisible, downloadingIds.size) {
        if (!isVisible && downloadingIds.isEmpty()) return@LaunchedEffect
        while (true) {
            downloadingIds.forEach { modelId ->
                val downloadId = downloadIdsMap[modelId] ?: return@forEach
                val progress = downloader.queryProgress(downloadId)
                if (progress >= 100) {
                    // 下载完成——规范化文件名（处理 Content-Disposition 覆盖问题）
                    val expectedFile = repo.getModelFile(modelId)
                    val actualFile = downloader.normalizeDownloadedFile(downloadId, expectedFile)
                    val validatedFile = actualFile?.takeIf { it.exists() } ?: expectedFile.takeIf { it.exists() }
                    if (validatedFile == null) {
                        Log.w("ModelManager", "下载完成后文件不存在: $modelId")
                        downloadingIds = downloadingIds - modelId
                        storageWarning = "❌ 下载完成但找不到文件喵~"
                        return@forEach
                    }

                    // 校验
                    val modelInfo = models.find { it.modelInfo.id == modelId }?.modelInfo
                    val validation = if (modelInfo != null) {
                        ModelValidator.validate(validatedFile, modelInfo.sha256)
                    } else null

                    downloadingIds = downloadingIds - modelId
                    // 刷新模型列表（此时新文件名应该能被 scanDownloadedModels 匹配）
                    models = repo.getAllModels()
                    downloadProgresses = downloadProgresses - modelId
                    // 清除警告
                    storageWarning = null

                    if (validation is com.rainyllm.app.model.ValidationResult.Mismatch) {
                        storageWarning = "⚠️ 哎呀喵！校验失败啦，麻烦主人重新下载一下嘛~"
                    } else {
                        // ✅ 下载校验成功后，自动切换到新模型
                        selectedModelId = modelId
                        scope.launch { prefs.setSelectedModel(modelId) }
                        importMessage = "✅ ${modelInfo?.name ?: modelId} 已下载并自动选中喵~"
                    }
                } else if (progress < 0) {
                    downloadingIds = downloadingIds - modelId
                    storageWarning = "❌ 下载失败惹，是不是网线被雨晴踩断了...请重试喵！"
                } else {
                    downloadProgresses = downloadProgresses + (modelId to progress)
                }
            }
            kotlinx.coroutines.delay(1000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("📦 雨晴的模型小仓库", style = MaterialTheme.typography.headlineSmall)

        // 操作栏：重新扫描 + 导入按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = {
                models = repo.getAllModels()
                importMessage = "🔍 已重新扫描模型列表喵~"
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "重新扫描")
            }
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            }) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入模型")
            }
        }

        if (storageWarning != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = storageWarning!!,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // 导入/导出状态消息
        if (importMessage != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (importMessage!!.startsWith("✅"))
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else if (importMessage!!.startsWith("❌"))
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = importMessage!!,
                    modifier = Modifier.padding(12.dp),
                    color = if (importMessage!!.startsWith("✅"))
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else if (importMessage!!.startsWith("❌"))
                        MaterialTheme.colorScheme.onErrorContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(models) { model ->
                val modelId = model.modelInfo.id
                val isCustom = isCustomModel(modelId)
                ModelDownloadCard(
                    model = model,
                    downloadProgress = downloadProgresses[modelId] ?: 0,
                    isDownloading = modelId in downloadingIds,
                    isSelected = modelId == selectedModelId && model.isDownloaded,
                    displayName = customNames[modelId] ?: model.modelInfo.name,
                    isCustom = isCustom,
                    onRename = {
                        renameModelId = modelId
                        renameText = customNames[modelId] ?: model.modelInfo.name
                    },
                    onDownload = {
                        val minBytes = model.modelInfo.sizeBytes + 1_000_000_000L
                        val available = downloader.checkStorageSpace(minBytes)
                        if (available < 0) {
                            storageWarning = "⚠️ 手机肚肚装不下啦！还需要 ${model.modelInfo.sizeGb} + 1GB 的空间哦喵~"
                            return@ModelDownloadCard
                        }
                        storageWarning = null
                        val file = repo.getModelFile(modelId)
                        val downloadId = downloader.startDownload(model.modelInfo, file, model.modelInfo.url)
                        downloadIdsMap = downloadIdsMap + (modelId to downloadId)
                        downloadingIds = downloadingIds + modelId
                    },
                    onDownloadMirror = {
                        val minBytes = model.modelInfo.sizeBytes + 1_000_000_000L
                        val available = downloader.checkStorageSpace(minBytes)
                        if (available < 0) {
                            storageWarning = "⚠️ 手机肚肚装不下啦！还需要 ${model.modelInfo.sizeGb} + 1GB 的空间哦喵~"
                            return@ModelDownloadCard
                        }
                        storageWarning = null
                        val file = repo.getModelFile(modelId)
                        val downloadId = downloader.startDownload(model.modelInfo, file, model.modelInfo.mirrorUrl)
                        downloadIdsMap = downloadIdsMap + (modelId to downloadId)
                        downloadingIds = downloadingIds + modelId
                    },
                    onCancel = {
                        val downloadId = downloadIdsMap[modelId]
                        if (downloadId != null) {
                            downloader.removeDownload(downloadId)
                            val partialFile = repo.getModelFile(modelId)
                            if (partialFile.exists() && partialFile.length() < model.modelInfo.sizeBytes) {
                                partialFile.delete()
                            }
                        }
                        downloadingIds = downloadingIds - modelId
                    },
                    onDelete = {
                        repo.deleteModel(modelId)
                        if (selectedModelId == modelId) {
                            selectedModelId = "gemma4-e2b"
                            scope.launch { prefs.setSelectedModel("gemma4-e2b") }
                        }
                        models = repo.getAllModels()
                    },
                    onSelect = {
                        selectedModelId = modelId
                        scope.launch { prefs.setSelectedModel(modelId) }
                    },
                    onExport = {
                        exportModelId = modelId
                        exportLauncher.launch("${modelId}.litertlm")
                    }
                )
            }
        }

        // ── 重命名对话框 ──────────────────────────────────
        if (renameModelId != null) {
            AlertDialog(
                onDismissRequest = { renameModelId = null },
                title = { Text("✏️ 自定义模型名称") },
                text = {
                    Column {
                        Text("为「${renameModelId}」设置一个别名",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            singleLine = true,
                            label = { Text("模型名称") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            prefs.setModelCustomName(renameModelId!!, renameText.trim())
                            importMessage = "✅ 已重命名为「${renameText.trim()}」喵~"
                        }
                        renameModelId = null
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { renameModelId = null }) { Text("取消") }
                }
            )
        }
    }
}