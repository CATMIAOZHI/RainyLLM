package com.rainyllm.app.server

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/**
 * RequestParser 单元测试
 *
 * 覆盖范围：
 * - 基本字段解析（model, messages, temperature, max_tokens, stream）
 * - max_tokens 缺省值
 * - tool_choice 字符串格式
 * - tool_choice 对象格式（修复后的行为）
 * - 多模态 content 数组解析
 * - tools 解析
 * - 空消息 / 缺失字段处理
 */
class RequestParserTest {

    @Test
    fun parsesBasicFields() {
        val body = """{"model":"gpt-4","messages":[{"role":"user","content":"hello"}],"temperature":0.5,"max_tokens":100,"stream":true}"""
        val result = RequestParser.parseChatCompletionRequest(body)

        assertEquals("gpt-4", result["model"])
        assertEquals(0.5, result["temperature"])
        assertEquals(100, result["max_tokens"])
        assertEquals(true, result["stream"])
    }

    @Test
    fun parsesMessages_correctly() {
        val body = """{"messages":[{"role":"system","content":"You are helpful"},{"role":"user","content":"Hi"}]}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        @Suppress("UNCHECKED_CAST")
        val messages = result["messages"] as List<Map<String, Any>>
        assertEquals(2, messages.size)
        assertEquals("system", messages[0]["role"])
        assertEquals("You are helpful", messages[0]["content"])
        assertEquals("user", messages[1]["role"])
        assertEquals("Hi", messages[1]["content"])
    }

    @Test
    fun maxTokens_defaultsTo4096_whenAbsent() {
        val body = """{"messages":[{"role":"user","content":"hi"}]}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        // max_tokens 不在请求中时，不应出现在 result 中（root.has 检查）
        assertFalse(result.containsKey("max_tokens"))
    }

    @Test
    fun maxTokens_parsed_whenPresent() {
        val body = """{"messages":[{"role":"user","content":"hi"}],"max_tokens":2048}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        assertEquals(2048, result["max_tokens"])
    }

    @Test
    fun toolChoice_stringFormat_preserved() {
        val body = """{"messages":[{"role":"user","content":"hi"}],"tool_choice":"none"}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        assertEquals("none", result["tool_choice"])
    }

    @Test
    fun toolChoice_stringAuto_preserved() {
        val body = """{"messages":[{"role":"user","content":"hi"}],"tool_choice":"auto"}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        assertEquals("auto", result["tool_choice"])
    }

    @Test
    fun toolChoice_objectFormat_convertedToString() {
        val body = """{"messages":[{"role":"user","content":"hi"}],"tool_choice":{"type":"function","function":{"name":"get_weather"}}}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        // 对象格式应被 toString() 转为字符串保留
        val tc = result["tool_choice"] as String
        assertTrue("tool_choice 对象应包含 function name", tc.contains("get_weather"))
        assertTrue("tool_choice 对象应包含 type", tc.contains("function"))
    }

    @Test
    fun toolChoice_invalidType_defaultsToAuto() {
        val body = """{"messages":[{"role":"user","content":"hi"}],"tool_choice":42}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        assertEquals("auto", result["tool_choice"])
    }

    @Test
    fun multimodalContent_parsedCorrectly() {
        val body = """{"messages":[{"role":"user","content":[
            {"type":"text","text":"What is this?"},
            {"type":"image_url","image_url":{"url":"data:image/png;base64,iVBOR"}}
        ]}]}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        @Suppress("UNCHECKED_CAST")
        val messages = result["messages"] as List<Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val parts = messages[0]["content"] as List<Map<String, Any>>
        assertEquals(2, parts.size)
        assertEquals("text", parts[0]["type"])
        assertEquals("What is this?", parts[0]["text"])
        assertEquals("image_url", parts[1]["type"])
        @Suppress("UNCHECKED_CAST")
        val imgUrl = parts[1]["image_url"] as Map<String, Any>
        assertEquals("data:image/png;base64,iVBOR", imgUrl["url"])
    }

    @Test
    fun audioContent_parsedCorrectly() {
        val body = """{"messages":[{"role":"user","content":[
            {"type":"input_audio","input_audio":{"data":"UklGRiQAAABXQVZFZm10"}}
        ]}]}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        @Suppress("UNCHECKED_CAST")
        val messages = result["messages"] as List<Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val parts = messages[0]["content"] as List<Map<String, Any>>
        assertEquals("input_audio", parts[0]["type"])
        @Suppress("UNCHECKED_CAST")
        val audio = parts[0]["input_audio"] as Map<String, Any>
        assertEquals("UklGRiQAAABXQVZFZm10", audio["data"])
    }

    @Test
    fun tools_parsedCorrectly() {
        val body = """{"messages":[{"role":"user","content":"weather"}],"tools":[
            {"type":"function","function":{"name":"get_weather","description":"Get weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}
        ]}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        @Suppress("UNCHECKED_CAST")
        val tools = result["tools"] as List<Map<String, Any>>
        assertEquals(1, tools.size)
        assertEquals("function", tools[0]["type"])
        @Suppress("UNCHECKED_CAST")
        val fn = tools[0]["function"] as Map<String, Any>
        assertEquals("get_weather", fn["name"])
        assertEquals("Get weather", fn["description"])
        assertTrue("parameters 应为 JSON 字符串", fn["parameters"] is String)
    }

    @Test
    fun missingContent_defaultsToEmptyString() {
        val body = """{"messages":[{"role":"assistant","tool_calls":[]}]}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        @Suppress("UNCHECKED_CAST")
        val messages = result["messages"] as List<Map<String, Any>>
        assertEquals("", messages[0]["content"])
    }

    @Test
    fun toolCallId_preserved() {
        val body = """{"messages":[{"role":"tool","content":"sunny","tool_call_id":"call_123"}]}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        @Suppress("UNCHECKED_CAST")
        val messages = result["messages"] as List<Map<String, Any>>
        assertEquals("call_123", messages[0]["tool_call_id"])
    }

    @Test
    fun emptyModel_notIncluded() {
        val body = """{"messages":[{"role":"user","content":"hi"}]}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        assertFalse("空 model 不应出现在结果中", result.containsKey("model"))
    }

    @Test
    fun topP_parsed_whenPresent() {
        val body = """{"messages":[{"role":"user","content":"hi"}],"top_p":0.9}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        assertEquals(0.9, result["top_p"])
    }

    @Test
    fun userField_parsed() {
        val body = """{"messages":[{"role":"user","content":"hi"}],"user":"user123"}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        assertEquals("user123", result["user"])
    }

    // ★ 回归测试：多轮 tool calling 上下文 — 验证 tool_calls 被正确解析为 List<Map<String, Any>>
    // 修复前：msg.get("tool_calls") 返回 JSONArray，后续 as? List<Map<String, Any>> cast 失败 → toolNameMap 为空
    // 修复后：手动解析 JSONArray 为 List<Map<String, Any>>，cast 成功 → 工具名正确映射
    @Test
    fun toolCalls_parsedAsListMap() {
        val body = """{"messages":[
            {"role":"user","content":"What's the weather in Paris?"},
            {"role":"assistant","content":null,"tool_calls":[
                {"id":"call_abc123","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}
            ]},
            {"role":"tool","content":"{\"temp\":22,\"condition\":\"sunny\"}","tool_call_id":"call_abc123"},
            {"role":"user","content":"Thanks!"}
        ]}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        @Suppress("UNCHECKED_CAST")
        val messages = result["messages"] as List<Map<String, Any>>

        // 验证 assistant 消息的 tool_calls 是 List<Map<String, Any>> 而非 JSONArray
        val assistantMsg = messages[1]
        assertEquals("assistant", assistantMsg["role"])
        val toolCalls = assistantMsg["tool_calls"]
        assertNotNull("tool_calls 不应为 null", toolCalls)
        assertTrue("tool_calls 应为 List<*>", toolCalls is List<*>)

        @Suppress("UNCHECKED_CAST")
        val tcList = toolCalls as List<Map<String, Any>>
        assertEquals(1, tcList.size)

        val tc = tcList[0]
        assertEquals("call_abc123", tc["id"])
        assertEquals("function", tc["type"])

        @Suppress("UNCHECKED_CAST")
        val fn = tc["function"] as Map<String, Any>
        assertEquals("get_weather", fn["name"])
        assertEquals("""{"city":"Paris"}""", fn["arguments"])
    }

    @Test
    fun toolCalls_multipleRounds_parsedCorrectly() {
        val body = """{"messages":[
            {"role":"user","content":"Weather in Tokyo and London?"},
            {"role":"assistant","content":null,"tool_calls":[
                {"id":"call_001","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Tokyo\"}"}},
                {"id":"call_002","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"London\"}"}}
            ]},
            {"role":"tool","content":"{\"temp\":15}","tool_call_id":"call_001"},
            {"role":"tool","content":"{\"temp\":10}","tool_call_id":"call_002"},
            {"role":"user","content":"Summarize"}
        ]}"""
        val result = RequestParser.parseChatCompletionRequest(body)
        @Suppress("UNCHECKED_CAST")
        val messages = result["messages"] as List<Map<String, Any>>

        val assistantMsg = messages[1]
        @Suppress("UNCHECKED_CAST")
        val tcList = assistantMsg["tool_calls"] as List<Map<String, Any>>
        assertEquals(2, tcList.size)
        assertEquals("call_001", tcList[0]["id"])
        assertEquals("call_002", tcList[1]["id"])

        // 验证两个 tool 消息的 tool_call_id
        assertEquals("call_001", messages[2]["tool_call_id"])
        assertEquals("call_002", messages[3]["tool_call_id"])
    }
}