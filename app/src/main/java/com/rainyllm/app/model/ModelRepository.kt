package com.rainyllm.app.model

import android.util.Log
import java.io.File

/**
 * 本地模型仓库
 * 扫描、管理已下载的模型文件
 */
class ModelRepository(private val modelsDir: File) {

    companion object {
        private const val TAG = "ModelRepository"
        private const val EXT_LITERTLM = ".litertlm"
    }

    /**
     * 扫描已下载的模型列表
     */
    fun scanDownloadedModels(): List<DownloadedModel> {
        if (!modelsDir.exists()) return emptyList()

        return modelsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXT_LITERTLM) }
            ?.mapNotNull { file ->
                val matchingPreset = ModelInfo.PRESET_MODELS.find { preset ->
                    file.nameWithoutExtension.contains(preset.id, ignoreCase = true)
                }
                DownloadedModel(
                    modelInfo = matchingPreset ?: ModelInfo(
                        id = file.nameWithoutExtension,
                        name = file.nameWithoutExtension,
                        sizeBytes = file.length(),
                        url = "",
                        sha256 = ""
                    ),
                    file = file,
                    isDownloaded = true
                )
            }
            ?: emptyList()
    }

    /**
     * 获取所有可用模型（包含未下载的预置模型）
     */
    fun getAllModels(): List<DownloadedModel> {
        val downloaded = scanDownloadedModels()
        val downloadedIds = downloaded.map { it.modelInfo.id }.toSet()

        val notDownloaded = ModelInfo.PRESET_MODELS
            .filter { it.id !in downloadedIds }
            .map { DownloadedModel(modelInfo = it, file = getModelFile(it.id), isDownloaded = false) }

        return downloaded + notDownloaded
    }

    /**
     * 判断模型是否已下载
     */
    fun isModelDownloaded(modelId: String): Boolean {
        return getModelFile(modelId).exists()
    }

    /**
     * 删除模型文件
     */
    fun deleteModel(modelId: String): Boolean {
        val file = getModelFile(modelId)
        return if (file.exists()) {
            file.delete().also {
                Log.i(TAG, "删除模型: $modelId — ${if (it) "成功" else "失败"}")
            }
        } else false
    }

    /**
     * 获取模型文件路径
     */
    fun getModelFile(modelId: String): File {
        return File(modelsDir, "$modelId$EXT_LITERTLM")
    }

    /**
     * 确保模型目录存在
     */
    fun ensureModelsDir() {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
    }

    /**
     * 从外部文件导入模型（复制到 modelsDir）
     * @return 导入后的模型文件，失败返回 null
     */
    fun importModel(sourceFile: File): File? {
        ensureModelsDir()
        val targetFile = File(modelsDir, sourceFile.name)
        return try {
            if (sourceFile.absolutePath == targetFile.absolutePath) {
                // 已经在目标目录中，无需复制
                if (targetFile.exists()) targetFile else null
            } else {
                sourceFile.copyTo(targetFile, overwrite = true)
                Log.i(TAG, "模型导入成功: ${sourceFile.name} → ${targetFile.absolutePath}")
                targetFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "模型导入失败: ${e.message}", e)
            null
        }
    }

    /**
     * 从内容 URI 输入流导入模型
     */
    fun importModelFromStream(inputStream: java.io.InputStream, fileName: String): File? {
        ensureModelsDir()
        val targetFile = File(modelsDir, fileName)
        return try {
            inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "模型流导入成功: $fileName")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "模型流导入失败: ${e.message}", e)
            try { targetFile.delete() } catch (_: Exception) {}
            null
        }
    }
}

/**
 * 已下载/可下载模型条目
 */
data class DownloadedModel(
    val modelInfo: ModelInfo,
    val file: File,
    val isDownloaded: Boolean
)