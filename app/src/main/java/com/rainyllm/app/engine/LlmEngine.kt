package com.rainyllm.app.engine

import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * LiteRT-LM 推理引擎封装
 * 依据官方 API（v0.10.x）：EngineConfig / ConversationConfig / SamplerConfig 均为 data class
 */
class LlmEngine(
    private val modelPath: String,
    private val cacheDir: String,
    private val visionBackend: Backend? = null,
    private val audioBackend: Backend? = null,
    /** KV Cache 最大 token 容量，null 则使用模型默认值 */
    val maxNumTokens: Int? = 4096
) {

    companion object {
        private const val TAG = "LlmEngine"
    }

    private var engine: Engine? = null

    val isInitialized: Boolean get() = engine != null

    /**
     * 初始化引擎（必须在后台线程调用，耗时约 10 秒）
     */
    suspend fun initialize(backend: Backend = Backend.CPU()) {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始初始化引擎... modelPath=$modelPath, backend=$backend")
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    cacheDir = cacheDir,
                    visionBackend = visionBackend,
                    audioBackend = audioBackend,
                    maxNumTokens = maxNumTokens
                )
                engine = Engine(config).also { it.initialize() }
                Log.i(TAG, "引擎初始化成功 ✓")
            } catch (e: LiteRtLmJniException) {
                Log.e(TAG, "引擎初始化失败: ${e.message}", e)
                throw EngineInitException("模型加载失败：${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "引擎初始化异常: ${e.message}", e)
                throw EngineInitException("引擎初始化异常：${e.message}", e)
            }
        }
    }

    /**
     * 创建新对话
     * @param config 对话配置（可为 null，使用默认配置）
     */
    fun createConversation(config: ConversationConfig? = null): Conversation {
        return requireEngine().createConversation(config ?: ConversationConfig())
    }

    /**
     * 同步推理（适用于非流式场景）
     */
    suspend fun generateResponse(prompt: String): String {
        val eng = requireEngine()
        return withContext(Dispatchers.IO) {
            try {
                eng.createConversation().use { conversation ->
                    conversation.sendMessage(prompt).toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "同步推理失败: ${e.message}", e)
                throw InferenceException("推理失败：${e.message}", e)
            }
        }
    }

    /**
     * 异步流式推理（适用于 SSE 流式输出）
     * @param conversation 已有对话上下文
     * @param prompt 用户输入
     * @return Flow<String> 逐 token 输出
     */
    fun generateResponseAsync(
        conversation: Conversation,
        prompt: String
    ): Flow<String> {
        return flow {
            try {
                conversation.sendMessageAsync(prompt).collect { message ->
                    emit(message.toString())
                }
            } catch (e: IOException) {
                // 客户端断开（Broken pipe）是正常行为，不报错
                Log.d(TAG, "客户端断开连接（流式输出中断）")
            } catch (e: Exception) {
                Log.e(TAG, "流式推理失败: ${e.message}", e)
                throw InferenceException("流式推理失败：${e.message}", e)
            }
        }
    }

    /**
     * 多模态消息流式推理
     * @param contents LiteRT-LM Contents（可包含 Text / ImageBytes / AudioBytes 等）
     */
    fun generateResponseAsync(
        conversation: Conversation,
        contents: Contents
    ): Flow<String> {
        return flow {
            try {
                conversation.sendMessageAsync(contents).collect { message ->
                    emit(message.toString())
                }
            } catch (e: IOException) {
                Log.d(TAG, "客户端断开连接（流式输出中断）")
            } catch (e: Exception) {
                Log.e(TAG, "流式推理失败: ${e.message}", e)
                throw InferenceException("流式推理失败：${e.message}", e)
            }
        }
    }

    fun getRawEngine(): Engine = requireEngine()

    fun close() {
        try {
            engine?.close()
            Log.i(TAG, "引擎已关闭")
        } catch (e: Exception) {
            Log.w(TAG, "关闭引擎时出现警告: ${e.message}")
        } finally {
            engine = null
        }
    }

    private fun requireEngine(): Engine {
        return engine ?: throw IllegalStateException("引擎尚未初始化，请先调用 initialize()")
    }
}

class EngineInitException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class InferenceException(message: String, cause: Throwable? = null) :
    Exception(message, cause)