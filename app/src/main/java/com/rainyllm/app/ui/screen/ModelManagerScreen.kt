package com.rainyllm.app.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
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
import com.rainyllm.app.model.ModelRepository
import com.rainyllm.app.model.ModelValidator
import com.rainyllm.app.ui.component.ModelDownloadCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelManagerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }

    val modelsDir = RainyLLMApp.instance.modelsDir
    val repo = remember { ModelRepository(modelsDir) }
    val downloader = remember { ModelDownloader(context) }

    var models by remember { mutableStateOf(repo.getAllModels()) }
    var selectedModelId by remember { mutableStateOf("gemma4-e2b") }

    // 从 DataStore 加载已保存的模型选择
    LaunchedEffect(Unit) {
        prefs.selectedModel.collect { savedModel ->
            // 只有已下载的模型才能被选为当前模型
            if (repo.isModelDownloaded(savedModel)) {
                selectedModelId = savedModel
            }
        }
    }
    var downloadProgresses by remember { mutableStateOf(mapOf<String, Int>()) }
    var downloadingIds by remember { mutableStateOf(setOf<String>()) }
    var downloadIdsMap by remember { mutableStateOf(mapOf<String, Long>()) }

    // 存储空间
    var storageWarning by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(Unit) {
        // 定期刷新进度
        while (true) {
            downloadingIds.forEach { modelId ->
                val downloadId = downloadIdsMap[modelId] ?: return@forEach
                val progress = downloader.queryProgress(downloadId)
                if (progress >= 100) {
                    // 下载完成——校验
                    val file = repo.getModelFile(modelId)
                    val modelInfo = models.find { it.modelInfo.id == modelId }?.modelInfo
                    val validation = if (modelInfo != null) {
                        ModelValidator.validate(file, modelInfo.sha256)
                    } else null

                    downloadingIds = downloadingIds - modelId
                    models = repo.getAllModels()
                    downloadProgresses = downloadProgresses - modelId

                    if (validation is com.rainyllm.app.model.ValidationResult.Mismatch) {
                        storageWarning = "⚠️ 哎呀喵！校验失败啦，麻烦主人重新下载一下嘛~"
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

        // 操作栏：导入按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
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
                ModelDownloadCard(
                    model = model,
                    downloadProgress = downloadProgresses[model.modelInfo.id] ?: 0,
                    isDownloading = model.modelInfo.id in downloadingIds,
                    isSelected = model.modelInfo.id == selectedModelId && model.isDownloaded,
                    onDownload = {
                        val minBytes = model.modelInfo.sizeBytes + 1_000_000_000L
                        val available = downloader.checkStorageSpace(minBytes)
                        if (available < 0) {
                            storageWarning = "⚠️ 手机肚肚装不下啦！还需要 ${model.modelInfo.sizeGb} + 1GB 的空间哦喵~"
                            return@ModelDownloadCard
                        }
                        storageWarning = null
                        val file = repo.getModelFile(model.modelInfo.id)
                        val downloadId = downloader.startDownload(model.modelInfo, file, model.modelInfo.url)
                        downloadIdsMap = downloadIdsMap + (model.modelInfo.id to downloadId)
                        downloadingIds = downloadingIds + model.modelInfo.id
                    },
                    onDownloadMirror = {
                        val minBytes = model.modelInfo.sizeBytes + 1_000_000_000L
                        val available = downloader.checkStorageSpace(minBytes)
                        if (available < 0) {
                            storageWarning = "⚠️ 手机肚肚装不下啦！还需要 ${model.modelInfo.sizeGb} + 1GB 的空间哦喵~"
                            return@ModelDownloadCard
                        }
                        storageWarning = null
                        val file = repo.getModelFile(model.modelInfo.id)
                        val downloadId = downloader.startDownload(model.modelInfo, file, model.modelInfo.mirrorUrl)
                        downloadIdsMap = downloadIdsMap + (model.modelInfo.id to downloadId)
                        downloadingIds = downloadingIds + model.modelInfo.id
                    },
                    onCancel = {
                        val downloadId = downloadIdsMap[model.modelInfo.id]
                        if (downloadId != null) {
                            downloader.removeDownload(downloadId)
                            // 清理可能的不完整文件
                            val partialFile = repo.getModelFile(model.modelInfo.id)
                            if (partialFile.exists() && partialFile.length() < model.modelInfo.sizeBytes) {
                                partialFile.delete()
                            }
                        }
                        downloadingIds = downloadingIds - model.modelInfo.id
                    },
                    onDelete = {
                        repo.deleteModel(model.modelInfo.id)
                        if (selectedModelId == model.modelInfo.id) {
                            // 如果删除的是当前选中的模型，回退到默认模型
                            selectedModelId = "gemma4-e2b"
                            scope.launch { prefs.setSelectedModel("gemma4-e2b") }
                        }
                        models = repo.getAllModels()
                    },
                    onSelect = {
                        selectedModelId = model.modelInfo.id
                        scope.launch { prefs.setSelectedModel(model.modelInfo.id) }
                    },
                    onExport = {
                        exportModelId = model.modelInfo.id
                        exportLauncher.launch("${model.modelInfo.id}.litertlm")
                    }
                )
            }
        }
    }
}