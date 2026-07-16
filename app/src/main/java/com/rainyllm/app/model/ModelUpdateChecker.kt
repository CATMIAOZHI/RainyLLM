package com.rainyllm.app.model

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型更新检测器
 *
 * 通过 HuggingFace API 查询模型仓库的最新 commit hash，
 * 与本地记录的下载时 commit hash 对比，判断是否有更新。
 *
 * HuggingFace API: GET https://huggingface.co/api/models/{repo}/revision/main
 * 返回 JSON 中 ._id 或 .sha 字段包含 commit hash。
 * 实际使用 refs/convert/parquet 或直接 /api/models/{repo} 中的 sha 字段。
 *
 * 更可靠的方案：GET https://huggingface.co/api/models/{repo}/revision/main
 * 返回的 JSON 中有 siblings 列表和 sha 字段（commit hash）。
 */
object ModelUpdateChecker {

    private const val TAG = "ModelUpdateChecker"
    private const val HF_API_BASE = "https://huggingface.co/api/models"
    private const val HF_MIRROR_API_BASE = "https://hf-mirror.com/api/models"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000

    data class UpdateCheckResult(
        val modelId: String,
        val hasUpdate: Boolean,
        val latestCommit: String?,
        val error: String? = null
    )

    /**
     * 从 HuggingFace URL 中提取仓库路径
     * 例：https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
     * → litert-community/gemma-4-E2B-it-litert-lm
     */
    private fun extractRepoPath(url: String): String? {
        return try {
            val afterHost = url.substringAfter("huggingface.co/")
                .substringAfter("hf-mirror.com/")
            // 去掉 resolve/main/... 部分
            val repoPath = afterHost.substringBefore("/resolve/")
            if (repoPath.contains("/")) repoPath else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 查询单个模型的最新 commit hash
     * @param modelInfo 模型信息
     * @param useMirror 是否使用镜像站
     * @return commit hash 字符串，失败返回 null
     */
    fun fetchLatestCommit(modelInfo: ModelInfo, useMirror: Boolean = false): String? {
        val baseUrl = if (useMirror) HF_MIRROR_API_BASE else HF_API_BASE
        val repoPath = extractRepoPath(modelInfo.url) ?: return null
        val apiUrl = "$baseUrl/$repoPath/revision/main"

        return try {
            Log.i(TAG, "查询模型更新: $repoPath")
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "RainyLLM/1.2.0")
            }

            try {
                if (conn.responseCode != 200) {
                    Log.w(TAG, "HuggingFace API 返回 ${conn.responseCode} for $repoPath")
                    // 如果主站失败，尝试镜像
                    if (!useMirror) {
                        Log.i(TAG, "主站失败，尝试镜像站...")
                        return fetchLatestCommit(modelInfo, useMirror = true)
                    }
                    return null
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                // HuggingFace API 返回的 sha 字段是当前 revision 的 commit hash
                val sha = json.optString("sha", "")
                if (sha.isNotEmpty()) {
                    Log.i(TAG, "✅ $repoPath 最新 commit: ${sha.take(12)}")
                    sha
                } else {
                    Log.w(TAG, "API 响应中无 sha 字段")
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询模型更新失败 ($repoPath): ${e.message}")
            // 主站异常时尝试镜像
            if (!useMirror) {
                Log.i(TAG, "主站异常，尝试镜像站...")
                return fetchLatestCommit(modelInfo, useMirror = true)
            }
            null
        }
    }

    /**
     * 批量检查多个模型的更新状态
     * @param models 要检查的模型列表
     * @param localCommitMap 本地记录的 commit hash 映射 (modelId → commitHash)
     * @return 检查结果列表
     */
    fun checkUpdates(
        models: List<ModelInfo>,
        localCommitMap: Map<String, String>
    ): List<UpdateCheckResult> {
        return models.map { modelInfo ->
            try {
                val latestCommit = fetchLatestCommit(modelInfo)
                if (latestCommit == null) {
                    UpdateCheckResult(modelInfo.id, false, null, "查询失败")
                } else {
                    val localCommit = localCommitMap[modelInfo.id]
                    val hasUpdate = localCommit != null && localCommit != latestCommit
                    if (hasUpdate) {
                        Log.i(TAG, "🔄 ${modelInfo.id} 有更新: $localCommit → $latestCommit")
                    }
                    UpdateCheckResult(modelInfo.id, hasUpdate, latestCommit, null)
                }
            } catch (e: Exception) {
                UpdateCheckResult(modelInfo.id, false, null, e.message)
            }
        }
    }
}