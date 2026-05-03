package com.rainyllm.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 推理统计记录（持久化单例）
 */
class StatsRepository(private val file: java.io.File) {

    companion object {
        private const val TAG = "StatsRepo"
        private const val FILE_NAME = "inference_stats.json"
        private const val MAX_RECORDS = 500

        @Volatile
        var instance: StatsRepository? = null
            private set

        fun init(context: Context) {
            if (instance == null) {
                instance = StatsRepository(java.io.File(context.filesDir, FILE_NAME))
            }
        }
    }

    data class InferenceRecord(
        val timestamp: Long,
        val model: String,
        val promptTokens: Int,
        val completionTokens: Int,
        val durationMs: Long
    )

    data class StatsSummary(
        val totalRequests: Int = 0,
        val totalPromptTokens: Long = 0,
        val totalCompletionTokens: Long = 0,
        val totalTokens: Long = 0,
        val avgDurationMs: Long = 0
    )

    private val _records = MutableStateFlow<List<InferenceRecord>>(emptyList())
    val records: StateFlow<List<InferenceRecord>> = _records.asStateFlow()

    init {
        loadFromFile()
    }

    @Synchronized
    fun addRecord(record: InferenceRecord) {
        _records.value = (_records.value + record).takeLast(MAX_RECORDS)
        saveToFile()
    }

    fun getSummary(): StatsSummary {
        val list = _records.value
        if (list.isEmpty()) return StatsSummary()

        return StatsSummary(
            totalRequests = list.size,
            totalPromptTokens = list.sumOf { it.promptTokens.toLong() },
            totalCompletionTokens = list.sumOf { it.completionTokens.toLong() },
            totalTokens = list.sumOf { (it.promptTokens + it.completionTokens).toLong() },
            avgDurationMs = list.map { it.durationMs }.average().toLong()
        )
    }

    @Synchronized
    fun clear() {
        _records.value = emptyList()
        try { file.delete() } catch (_: Exception) {}
    }

    // ── 持久化 ──

    private fun saveToFile() {
        try {
            val arr = JSONArray()
            _records.value.forEach { r ->
                arr.put(JSONObject().apply {
                    put("timestamp", r.timestamp)
                    put("model", r.model)
                    put("promptTokens", r.promptTokens)
                    put("completionTokens", r.completionTokens)
                    put("durationMs", r.durationMs)
                })
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "保存统计失败: ${e.message}")
        }
    }

    private fun loadFromFile() {
        try {
            if (!file.exists()) return
            val json = file.readText()
            val arr = JSONArray(json)
            val loaded = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                InferenceRecord(
                    timestamp = obj.getLong("timestamp"),
                    model = obj.optString("model", ""),
                    promptTokens = obj.getInt("promptTokens"),
                    completionTokens = obj.getInt("completionTokens"),
                    durationMs = obj.getLong("durationMs")
                )
            }
            _records.value = loaded.takeLast(MAX_RECORDS)
        } catch (e: Exception) {
            Log.w(TAG, "加载历史统计失败: ${e.message}")
        }
    }
}