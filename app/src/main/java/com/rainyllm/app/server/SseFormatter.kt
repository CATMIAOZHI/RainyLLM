package com.rainyllm.app.server

import java.time.Instant

/**
 * SSE (Server-Sent Events) 流式输出格式化器
 * 符合 OpenAI 的 SSE 格式规范
 */
object SseFormatter {

    /**
     * 构建单个 token 的 SSE chunk
     *
     * 格式：
     * data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","created":123,"model":"gemma4","choices":[{"index":0,"delta":{"role":"assistant"}}]}
     *
     * data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","created":123,"model":"gemma4","choices":[{"index":0,"delta":{"content":"你好"}}]}
     */
    fun buildSseChunk(
        id: String,
        model: String,
        created: Long,
        role: String? = null,
        content: String? = null
    ): String {
        val delta = when {
            role != null -> """{"role":"$role"}"""
            content != null -> """{"content":${escapeJsonString(content)}}"""
            else -> """{"content":""}"""
        }

        val json = """
        {
          "id": "$id",
          "object": "chat.completion.chunk",
          "created": $created,
          "model": "$model",
          "choices": [{
            "index": 0,
            "delta": $delta,
            "finish_reason": null
          }]
        }
        """.trimIndent().replace("\n", "")

        return "data: $json\n\n"
    }

    /**
     * 构建最后的 [DONE] 信号，包含 usage 统计
     */
    fun buildSseDone(
        id: String,
        model: String,
        created: Long,
        promptTokens: Int,
        completionTokens: Int
    ): String {
        val json = """
        {
          "id": "$id",
          "object": "chat.completion.chunk",
          "created": $created,
          "model": "$model",
          "choices": [{
            "index": 0,
            "delta": {},
            "finish_reason": "stop"
          }],
          "usage": {
            "prompt_tokens": $promptTokens,
            "completion_tokens": $completionTokens,
            "total_tokens": ${promptTokens + completionTokens}
          }
        }
        """.trimIndent().replace("\n", "")

        return "data: $json\n\ndata: [DONE]\n\n"
    }

    private fun escapeJsonString(text: String): String {
        return "\"${text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")}\""
    }
}