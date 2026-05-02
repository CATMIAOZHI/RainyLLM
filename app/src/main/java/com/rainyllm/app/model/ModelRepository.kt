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

        /** 标准化名称：移除连字符、下划线、空格，统一小写，用于模糊匹配 */
        fun normalizeName(name: String): String =
            name.replace('-', ' ').replace('_', ' ')
               .replace(Regex("\\s+"), " ").trim().lowercase()
    }

    /**
     * 扫描已下载的模型列表
     * 匹配规则：精确匹配预设ID 或 标准化精确匹配，都不匹配则视为自定义模型
     */
    fun scanDownloadedModels(): List<DownloadedModel> {
        if (!modelsDir.exists()) return emptyList()

        return modelsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXT_LITERTLM) }
            ?.map { file ->
                val nameNoExt = file.nameWithoutExtension

                // 精确匹配（文件名与预设ID完全一致）
                val exactMatch = ModelInfo.PRESET_MODELS.find { preset ->
                    nameNoExt.equals(preset.id, ignoreCase = true)
                }
                if (exactMatch != null) return@map DownloadedModel(
                    modelInfo = exactMatch, file = file, isDownloaded = true
                )

                // 标准化精确匹配（处理连字符/下划线差异，如 gemma-4-E2B-it ↔ gemma4-e2b）
                val normalized = normalizeName(nameNoExt)
                val fuzzyMatch = ModelInfo.PRESET_MODELS.find { preset ->
                    normalizeName(preset.id) == normalized
                }
                if (fuzzyMatch != null) return@map DownloadedModel(
                    modelInfo = fuzzyMatch, file = file, isDownloaded = true
                )

                // 不匹配任何预设 → 视为自定义模型
                DownloadedModel(
                    modelInfo = ModelInfo(
                        id = nameNoExt,
                        name = nameNoExt,
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
     * 智能查找模型文件
     * 匹配策略：精确文件名 → 大小写不敏感精确 → 标准化精确 → 标准化匹配预设ID → 穷举搜索
     */
    fun findModelFile(modelId: String): File? {
        val exact = getModelFile(modelId)
        if (exact.exists()) return exact

        val targetNorm = normalizeName(modelId)
        val candidates = modelsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXT_LITERTLM) }
            ?: return null

        // ① 大小写不敏感精确匹配
        candidates.find { it.nameWithoutExtension.equals(modelId, ignoreCase = true) }
            ?.let { return it }

        // ② 标准化精确匹配（处理连字符/下划线差异）
        candidates.find { normalizeName(it.nameWithoutExtension) == targetNorm }
            ?.let { return it }

        // ③ 标准化匹配预设ID（处理文件名中的额外标记，如 -it 后缀）
        val matchingPreset = ModelInfo.PRESET_MODELS.find { normalizeName(it.id) == targetNorm }
        if (matchingPreset != null) {
            val presetNorm = normalizeName(matchingPreset.id)
            candidates.find { normalizeName(it.nameWithoutExtension).contains(presetNorm) }
                ?.let { return it }
        }

        // ④ 终局兜底：穷举搜索（为自定义导入模型提供保障）
        // 当 modelsDir 路径变更导致精确路径失效时，遍历所有 .litertlm 文件
        // 匹配条件：标准化后的 modelId 是文件名的子串 或 文件名是 modelId 的子串
        candidates.find { candidate ->
            val candNorm = normalizeName(candidate.nameWithoutExtension)
            candNorm.contains(targetNorm) || targetNorm.contains(candNorm)
        }?.let { return it }

        return null
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
        return findModelFile(modelId) != null
    }

    /**
     * 删除模型文件
     */
    fun deleteModel(modelId: String): Boolean {
        // 先用精确路径，再用智能查找（处理文件名变体）
        val file = getModelFile(modelId).takeIf { it.exists() }
            ?: findModelFile(modelId)
            ?: return false
        return file.delete().also {
            Log.i(TAG, "删除模型: $modelId — ${if (it) "成功" else "失败"}")
        }
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