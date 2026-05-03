package com.rainyllm.app.server

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 格式请求体解析器
 */
object RequestParser {

    /**
     * 解析 /v1/chat/completions 的 JSON 请求体
     * @return 解析后的 Map，包含 model, messages, temperature, max_tokens, stream, top_p 等字段
     */
    fun parseChatCompletionRequest(bodyJson: String): Map<String, Any> {
        val root = JSONObject(bodyJson)
        val result = mutableMapOf<String, Any>()

        // 模型名
        val model = root.optString("model", "")
        if (model.isNotEmpty()) result["model"] = model

        // 消息列表
        val messagesArray = root.optJSONArray("messages")
        if (messagesArray != null) {
            val messages = parseMessages(messagesArray)
            result["messages"] = messages
        }

        // 可选参数
        if (root.has("temperature")) result["temperature"] = root.optDouble("temperature", 0.7)
        if (root.has("max_tokens")) result["max_tokens"] = root.optInt("max_tokens", 4096)
        if (root.has("top_p")) result["top_p"] = root.optDouble("top_p", 1.0)
        if (root.has("stream")) result["stream"] = root.optBoolean("stream", false)
        if (root.has("user")) result["user"] = root.optString("user", "")
        if (root.has("tools")) result["tools"] = parseTools(root.optJSONArray("tools"))
        if (root.has("tool_choice")) result["tool_choice"] = root.optString("tool_choice", "auto")

        return result
    }

    private fun parseMessages(array: JSONArray): List<Map<String, Any>> {
        val messages = mutableListOf<Map<String, Any>>()
        for (i in 0 until array.length()) {
            val msg = array.getJSONObject(i)
            val role = msg.optString("role", "user")

            val map = mutableMapOf<String, Any>()
            map["role"] = role

            // 保留 tool_calls（assistant 消息）和 tool_call_id（tool 消息）
            if (msg.has("tool_calls")) map["tool_calls"] = msg.get("tool_calls")
            if (msg.has("tool_call_id")) map["tool_call_id"] = msg.optString("tool_call_id", "")

            // 保留原始 content 结构：字符串或数组（多模态）
            if (msg.has("content")) {
                val rawContent = msg.get("content")
                when (rawContent) {
                    is JSONArray -> {
                        // 多模态 content 数组：完整保留 image_url / input_audio 等信息
                        val parts = mutableListOf<Map<String, Any>>()
                        for (j in 0 until rawContent.length()) {
                            val partJo = rawContent.getJSONObject(j)
                            val part = mutableMapOf<String, Any>()
                            part["type"] = partJo.optString("type", "")
                            when (part["type"]) {
                                "text" -> part["text"] = partJo.optString("text", "")
                                "image_url" -> {
                                    part["image_url"] = mapOf(
                                        "url" to (partJo.optJSONObject("image_url")?.optString("url", "")
                                            ?: partJo.optString("image_url", ""))
                                    )
                                }
                                "input_audio" -> {
                                    part["input_audio"] = mapOf(
                                        "data" to (partJo.optJSONObject("input_audio")?.optString("data", "")
                                            ?: "")
                                    )
                                }
                            }
                            parts.add(part)
                        }
                        map["content"] = parts
                    }
                    else -> {
                        map["content"] = rawContent.toString()
                    }
                }
            } else {
                map["content"] = ""
            }

            messages.add(map)
        }
        return messages
    }

    private fun parseTools(array: JSONArray?): List<Map<String, Any>> {
        if (array == null) return emptyList()
        val tools = mutableListOf<Map<String, Any>>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val fn = obj.optJSONObject("function") ?: continue
            tools.add(mapOf(
                "type" to obj.optString("type", "function"),
                "function" to mapOf(
                    "name" to fn.optString("name", ""),
                    "description" to fn.optString("description", ""),
                    "parameters" to (fn.optJSONObject("parameters")?.toString() ?: "{}")
                )
            ))
        }
        return tools
    }
}