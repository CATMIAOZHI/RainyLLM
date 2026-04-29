package com.rainyllm.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 推理统计记录
 */
class StatsRepository {

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
        val avgDurationMs: Long = 0,
        val recentRecords: List<InferenceRecord> = emptyList()
    )

    private val _records = MutableStateFlow<List<InferenceRecord>>(emptyList())
    val records: StateFlow<List<InferenceRecord>> = _records.asStateFlow()

    fun addRecord(record: InferenceRecord) {
        _records.value = (_records.value + record).takeLast(500)
    }

    fun getSummary(): StatsSummary {
        val list = _records.value
        if (list.isEmpty()) return StatsSummary()

        return StatsSummary(
            totalRequests = list.size,
            totalPromptTokens = list.sumOf { it.promptTokens.toLong() },
            totalCompletionTokens = list.sumOf { it.completionTokens.toLong() },
            avgDurationMs = list.map { it.durationMs }.average().toLong(),
            recentRecords = list.takeLast(20)
        )
    }

    fun clear() {
        _records.value = emptyList()
    }
}