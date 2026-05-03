package com.rainyllm.app.server

import org.json.JSONArray
import org.json.JSONObject
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
        val deltaObj = JSONObject()
        when {
            role != null -> deltaObj.put("role", role)
            content != null -> deltaObj.put("content", content)
            else -> deltaObj.put("content", "")
        }

        val choices = JSONArray().put(JSONObject().apply {
            put("index", 0)
            put("delta", deltaObj)
            put("finish_reason", JSONObject.NULL)
        })

        val json = JSONObject().apply {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", model)
            put("choices", choices)
        }.toString()

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
        val json = JSONObject().apply {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("delta", JSONObject())
                put("finish_reason", "stop")
            }))
            put("usage", JSONObject().apply {
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTokens)
                put("total_tokens", promptTokens + completionTokens)
            })
        }.toString()

        return "data: $json\n\ndata: [DONE]\n\n"
    }

    /**
     * 构建流式 tool_calls SSE chunk
     * 将完整 tool_calls 列表作为单个 SSE 帧发送
     */
    fun buildSseToolCalls(
        id: String,
        model: String,
        created: Long,
        toolCalls: List<com.google.ai.edge.litertlm.ToolCall>
    ): String {
        val tcArray = JSONArray()
        toolCalls.forEachIndexed { idx, tc ->
            tcArray.put(JSONObject().apply {
                put("index", idx)
                put("id", "call_${idx}")
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tc.name)
                    put("arguments", (tc.arguments as? Map<*, *>)?.let { JSONObject(it).toString() } ?: "{}")
                })
            })
        }
        val json = JSONObject().apply {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("delta", JSONObject().apply {
                    put("tool_calls", tcArray)
                })
                put("finish_reason", "tool_calls")
            }))
        }.toString()
        return "data: $json\n\n"
    }
}