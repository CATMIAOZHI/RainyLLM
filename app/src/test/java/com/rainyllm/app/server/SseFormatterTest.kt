package com.rainyllm.app.server

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/**
 * SseFormatter 单元测试
 *
 * 覆盖范围：
 * - SSE chunk 格式（data: 前缀 + \n\n 结尾）
 * - role delta 正确输出
 * - content delta 正确输出
 * - [DONE] 信号格式
 * - usage 字段（prompt_tokens / completion_tokens / total_tokens）
 * - finish_reason 传递
 * - model 字段在输出中正确
 */
class SseFormatterTest {

    @Test
    fun buildSseChunk_hasDataPrefix() {
        val chunk = SseFormatter.buildSseChunk("chatcmpl-1", "gemma4-e2b", 1700000000, role = "assistant")
        assertTrue("SSE chunk 应以 'data: ' 开头", chunk.startsWith("data: "))
        assertTrue("SSE chunk 应以 \\n\\n 结尾", chunk.endsWith("\n\n"))
    }

    @Test
    fun buildSseChunk_roleDelta_correctJson() {
        val chunk = SseFormatter.buildSseChunk("chatcmpl-1", "gemma4-e2b", 1700000000, role = "assistant")
        val json = JSONObject(chunk.removePrefix("data: ").trim())
        assertEquals("chatcmpl-1", json.getString("id"))
        assertEquals("chat.completion.chunk", json.getString("object"))
        assertEquals("gemma4-e2b", json.getString("model"))
        assertEquals(1700000000, json.getLong("created"))
        val choices = json.getJSONArray("choices")
        val delta = choices.getJSONObject(0).getJSONObject("delta")
        assertEquals("assistant", delta.getString("role"))
    }

    @Test
    fun buildSseChunk_contentDelta_correctJson() {
        val chunk = SseFormatter.buildSseChunk("chatcmpl-1", "gemma4-e2b", 1700000000, content = "你好")
        val json = JSONObject(chunk.removePrefix("data: ").trim())
        val delta = json.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
        assertEquals("你好", delta.getString("content"))
    }

    @Test
    fun buildSseChunk_emptyContent_whenBothNull() {
        val chunk = SseFormatter.buildSseChunk("chatcmpl-1", "gemma4-e2b", 1700000000)
        val json = JSONObject(chunk.removePrefix("data: ").trim())
        val delta = json.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
        assertEquals("", delta.getString("content"))
    }

    @Test
    fun buildSseChunk_finishReasonNull_forContentChunks() {
        val chunk = SseFormatter.buildSseChunk("chatcmpl-1", "gemma4-e2b", 1700000000, content = "test")
        val json = JSONObject(chunk.removePrefix("data: ").trim())
        val choice = json.getJSONArray("choices").getJSONObject(0)
        assertTrue("finish_reason 应为 null", choice.isNull("finish_reason"))
    }

    @Test
    fun buildSseDone_hasDoneSignal() {
        val done = SseFormatter.buildSseDone("chatcmpl-1", "gemma4-e2b", 1700000000, 10, 20)
        assertTrue("SSE done 应包含 data: ", done.contains("data: "))
        assertTrue("SSE done 应包含 [DONE]", done.contains("[DONE]"))
        assertTrue("SSE done 应以 \\n\\n 结尾", done.endsWith("\n\n"))
    }

    @Test
    fun buildSseDone_usageFields_correct() {
        val done = SseFormatter.buildSseDone("chatcmpl-1", "gemma4-e2b", 1700000000, 10, 20)
        // 解析第一个 data: 行（[DONE] 之前的那条）
        val dataLine = done.lines().first { it.startsWith("data: {") }.removePrefix("data: ")
        val json = JSONObject(dataLine)
        val usage = json.getJSONObject("usage")
        assertEquals(10, usage.getInt("prompt_tokens"))
        assertEquals(20, usage.getInt("completion_tokens"))
        assertEquals(30, usage.getInt("total_tokens"))
    }

    @Test
    fun buildSseDone_finishReason_defaultsToStop() {
        val done = SseFormatter.buildSseDone("chatcmpl-1", "gemma4-e2b", 1700000000, 10, 20)
        val dataLine = done.lines().first { it.startsWith("data: {") }.removePrefix("data: ")
        val json = JSONObject(dataLine)
        val choice = json.getJSONArray("choices").getJSONObject(0)
        assertEquals("stop", choice.getString("finish_reason"))
    }

    @Test
    fun buildSseDone_customFinishReason() {
        val done = SseFormatter.buildSseDone("chatcmpl-1", "gemma4-e2b", 1700000000, 10, 20, finishReason = "length")
        val dataLine = done.lines().first { it.startsWith("data: {") }.removePrefix("data: ")
        val json = JSONObject(dataLine)
        val choice = json.getJSONArray("choices").getJSONObject(0)
        assertEquals("length", choice.getString("finish_reason"))
    }

    @Test
    fun buildSseDone_modelField_correct() {
        val done = SseFormatter.buildSseDone("chatcmpl-1", "gemma4-e4b", 1700000000, 5, 5)
        val dataLine = done.lines().first { it.startsWith("data: {") }.removePrefix("data: ")
        val json = JSONObject(dataLine)
        assertEquals("gemma4-e4b", json.getString("model"))
    }
}