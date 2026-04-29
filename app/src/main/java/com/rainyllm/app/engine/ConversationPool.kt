package com.rainyllm.app.engine

import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 多客户端对话池管理（适配 LiteRT-LM v0.10.x API）
 * ConversationConfig / SamplerConfig 均为 data class
 */
class ConversationPool(
    private val llmEngine: LlmEngine,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS
) {

    companion object {
        private const val TAG = "ConversationPool"
        const val DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1000L // 5 分钟
    }

    data class ConversationEntry(
        val id: String,
        val conversation: Conversation,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var lastActiveAt: Long = System.currentTimeMillis()
    )

    private val sessions = ConcurrentHashMap<String, ConversationEntry>()
    private var cleanupJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun create(
        id: String,
        systemInstruction: String? = null,
        samplerConfig: SamplerConfig? = null
    ): ConversationEntry {
        // 注意：LiteRT-LM 只支持单一活跃会话
        // 如果 Server 正持有 Conversation，不要在 Pool 中创建新的
        val config = ConversationConfig(
            systemInstruction = if (systemInstruction != null) Contents.of(systemInstruction) else null,
            samplerConfig = samplerConfig ?: defaultSamplerConfig()
        )
        val conversation = llmEngine.createConversation(config)
        val entry = ConversationEntry(id = id, conversation = conversation)
        sessions[id] = entry
        Log.d(TAG, "创建会话: $id")
        return entry
    }

    fun find(id: String): ConversationEntry? {
        val entry = sessions[id]
        if (entry != null) {
            entry.lastActiveAt = System.currentTimeMillis()
        }
        return entry
    }

    fun getOrCreate(
        id: String,
        systemInstruction: String? = null,
        samplerConfig: SamplerConfig? = null
    ): ConversationEntry {
        return find(id) ?: create(id, systemInstruction, samplerConfig)
    }

    fun destroy(id: String) {
        sessions.remove(id)?.let { entry ->
            destroyConversation(entry)
            Log.d(TAG, "销毁会话: $id (剩余 ${sessions.size} 个)")
        }
    }

    fun startAutoCleanup(intervalMs: Long = 60_000L) {
        stopAutoCleanup()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                cleanupExpiredSessions()
            }
        }
        Log.d(TAG, "自动清理已启动，间隔: ${intervalMs}ms，超时: ${idleTimeoutMs}ms")
    }

    fun stopAutoCleanup() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        // 使用 toList() 创建快照避免并发修改
        val expiredIds = sessions.entries.toList()
            .filter { now - it.value.lastActiveAt > idleTimeoutMs }
            .map { it.key }

        expiredIds.forEach { id ->
            sessions.remove(id)?.let { entry ->
                destroyConversation(entry)
                Log.d(TAG, "超时清理会话: $id")
            }
        }

        if (expiredIds.isNotEmpty()) {
            Log.i(TAG, "清理了 ${expiredIds.size} 个超时会话 (剩余 ${sessions.size} 个)")
        }
    }

    fun destroyAll() {
        stopAutoCleanup()
        sessions.values.forEach { destroyConversation(it) }
        sessions.clear()
        Log.i(TAG, "所有会话已销毁")
    }

    fun shutdown() {
        destroyAll()
        scope.cancel()
    }

    val activeCount: Int get() = sessions.size
    val sessionIds: Set<String> get() = sessions.keys.toSet()

    private fun destroyConversation(entry: ConversationEntry) {
        try {
            entry.conversation.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭会话时出现警告: ${e.message}")
        }
    }
}

/**
 * 创建默认 SamplerConfig
 */
fun defaultSamplerConfig(
    temperature: Float = 0.7f,
    topK: Int = 40,
    topP: Float = 0.95f
): SamplerConfig {
    return SamplerConfig(
        temperature = temperature.toDouble(),
        topK = topK,
        topP = topP.toDouble()
    )
}