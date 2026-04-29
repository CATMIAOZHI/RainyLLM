package com.rainyllm.app.server

import android.util.Base64
import android.util.Log
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.rainyllm.app.engine.LlmEngine
import com.rainyllm.app.engine.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.OutputStream
import java.io.PrintWriter
import java.time.Instant
import java.util.*
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.*
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method

/**
 * NanoHTTPd 服务器主体 — 在 127.0.0.1 上广播 OpenAI 兼容 API
 */
class OpenAIServer(
    private val port: Int,
    private val llmEngine: LlmEngine,
    private val modelId: String = "gemma4-e2b"
) : NanoHTTPD("127.0.0.1", port) {

    companion object {
        private const val TAG = "OpenAIServer"
        private const val SOCKET_READ_TIMEOUT = 5000

        /** 当前运行中的服务器实例（供 UI 读取状态） */
        @Volatile
        var currentInstance: OpenAIServer? = null
            private set
    }

    private var isRunning = false
    private val stats = ServerStats()
    @Volatile private var nextLogIndex = java.util.concurrent.atomic.AtomicInteger(0)
    private val requestLog = java.util.concurrent.CopyOnWriteArrayList<LogEntry>()

    /** 暂存响应摘要（handler 先于 logRequest 执行，需滞后写入） */
    @Volatile
    private var pendingResponseSummary: String? = null

    /** 最近一次推理错误的详细信息（含堆栈），供 UI 调试面板读取 */
    @Volatile
    var lastErrorDetail: String? = null
        private set

    val serverPort: Int get() = port
    val isServerRunning: Boolean get() = isRunning

    override fun start(): Unit {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            isRunning = true
            currentInstance = this
            Log.i(TAG, "✅ OpenAI API 服务器已启动: http://127.0.0.1:$port")
        } catch (e: Exception) {
            isRunning = false
            currentInstance = null
            Log.e(TAG, "❌ 服务器启动失败: ${e.message}", e)
            throw e
        }
    }

    override fun stop() {
        isRunning = false
        currentInstance = null
        super.stop()
        Log.i(TAG, "🛑 服务器已停止")
    }

    override fun serve(session: IHTTPSession): Response {
        val startTime = System.currentTimeMillis()
        val method = session.method
        val uri = session.uri

        // 不在此处 parseBody —— NanoHTTPd 的 parseBody 只能调用一次，
        // 交给具体 handler 调用，否则 handler 内部再调会读到空流导致 500

        return try {
            val response = when {
                method == Method.OPTIONS -> handleCorsPreflight()
                uri == "/health" || uri == "/" -> handleHealthCheck()
                uri == "/v1/models" && method == Method.GET -> handleListModels()
                uri == "/v1/chat/completions" && method == Method.POST -> {
                    handleChatCompletion(session)
                }
                else -> handleNotFound()
            }

            val elapsed = System.currentTimeMillis() - startTime
            val logIdx = logRequest(method.name, uri, response.status.requestStatus, elapsed)

            // 如果是 SSE 流式响应，注入 logIndex 以便 send() 完成后更新日志
            (response as? SseResponse)?.setLogIndex(logIdx)

            // handler 可能先于 logRequest 产生了摘要，滞后写入
            pendingResponseSummary?.let {
                setLogEntryAt(logIdx, it, elapsed)
                pendingResponseSummary = null
            }

            // CORS preflight 已在 handleCorsPreflight() 中自行添加 headers，无需重复
            if (method != Method.OPTIONS) {
                addCorsHeaders(response)
            }
            response
        } catch (e: Exception) {
            val detail = "请求异常 · 类型: ${e.javaClass.simpleName} · 消息: ${e.message}\n" +
                "堆栈: ${e.stackTraceToString().take(800)}"
            lastErrorDetail = detail
            Log.e(TAG, detail, e)
            val elapsed = System.currentTimeMillis() - startTime
            logRequest(method.name, uri, 500, elapsed,
                responseSummary = "❌ $detail".take(500))
            jsonResponse(Response.Status.INTERNAL_ERROR,
                """{"error":{"message":"${e.message?.replace("\"", "\\\"")}"}}""")
        }
    }

    /**
     * 解码 data:...;base64,... URL 为字节数组
     */
    private fun decodeDataUrl(url: String): ByteArray? {
        return try {
            if (!url.startsWith("data:")) return null
            val commaIdx = url.indexOf(',')
            if (commaIdx < 0) return null
            val b64 = url.substring(commaIdx + 1)
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) { null }
    }

    // ── 端点处理 ──────────────────────────────────────────

    private fun handleHealthCheck(): Response {
        return jsonResponse(Response.Status.OK, """{"status":"ok","model":"$modelId"}""")
    }

    private fun handleListModels(): Response {
        val data = """{"object":"list","data":[{"id":"$modelId","object":"model","owned_by":"rainyllm"}]}"""
        return jsonResponse(Response.Status.OK, data)
    }

    private fun handleChatCompletion(session: IHTTPSession): Response {
        val bodyJson = parseBodyUtf8(session)
        if (bodyJson.isBlank())
            return jsonResponse(Response.Status.BAD_REQUEST,
                """{"error":{"message":"Empty request body"}}""")

        // 捕获请求体到日志（在 logRequest 之后通过 append 补充）
        appendLastLogRequestDetail(bodyJson.take(8000))

        val request = RequestParser.parseChatCompletionRequest(bodyJson)
        val isStream = request["stream"] as? Boolean ?: false

        val systemPrompt = (request["messages"] as? List<Map<String, Any>>)
            ?.firstOrNull { it["role"] == "system" }
            ?.get("content")?.toString()

        // 构建完整 prompt：优先检查多模态 content
        val userMessagesRaw = (request["messages"] as? List<Any>) ?: emptyList()
        // 取最后一条 user 消息
        val lastUserMsg = userMessagesRaw.lastOrNull {
            (it as? Map<*, *>)?.get("role") == "user"
        } as? Map<*, *>

        if (lastUserMsg == null) {
            return jsonResponse(Response.Status.BAD_REQUEST,
                """{"error":{"message":"No user message found"}}""")
        }

        var prompt: String
        val multimodalContents = mutableListOf<Content>()

        val contentField = lastUserMsg["content"]
        when (contentField) {
            is List<*> -> {
                // 多模态 content 数组
                val textParts = mutableListOf<String>()
                for (part in contentField) {
                    val partMap = part as? Map<*, *> ?: continue
                    when (partMap["type"]) {
                        "text" -> {
                            val txt = partMap["text"]?.toString() ?: ""
                            if (txt.isNotBlank()) textParts.add(txt)
                        }
                        "image_url" -> {
                            val imgUrl = (partMap["image_url"] as? Map<*, *>)?.get("url")?.toString()
                            if (imgUrl != null) {
                                val bytes = decodeDataUrl(imgUrl)
                                if (bytes != null) multimodalContents.add(Content.ImageBytes(bytes))
                                else if (!imgUrl.startsWith("data:")) {
                                    try {
                                        val file = java.io.File(imgUrl)
                                        if (file.exists()) multimodalContents.add(Content.ImageFile(imgUrl))
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        "input_audio" -> {
                            val audioData = partMap["input_audio"] as? Map<*, *>
                            val audioB64 = audioData?.get("data")?.toString()
                            if (audioB64 != null) {
                                val bytes = Base64.decode(audioB64, Base64.DEFAULT)
                                multimodalContents.add(Content.AudioBytes(bytes))
                            }
                        }
                    }
                }
                prompt = textParts.joinToString("\n")
                if (prompt.isBlank()) prompt = "请描述以下内容"
                multimodalContents.add(Content.Text(prompt))
            }
            is String -> {
                prompt = contentField
            }
            else -> {
                prompt = contentField?.toString() ?: ""
            }
        }
        val promptTokens = TokenEstimator.estimatePromptTokens(prompt)

        return if (isStream) {
            handleStreamResponse(prompt, systemPrompt, promptTokens, multimodalContents)
        } else {
            handleSyncResponse(prompt, systemPrompt, promptTokens, multimodalContents)
        }
    }

    private fun handleSyncResponse(
        prompt: String,
        systemPrompt: String?,
        promptTokens: Int,
        multimodalContents: List<Content>
    ): Response {
        return try {
            val config = ConversationConfig(
                systemInstruction = if (systemPrompt != null) Contents.of(systemPrompt) else null
            )
            val result = runBlocking(Dispatchers.IO) {
                llmEngine.createConversation(config).use { conversation ->
                    val responseMsg = if (multimodalContents.size > 1) {
                        // 多模态：Contents.of(Content.ImageBytes(...), Content.Text(...), ...)
                        conversation.sendMessage(Contents.of(multimodalContents))
                    } else {
                        conversation.sendMessage(prompt)
                    }
                    responseMsg.toString()
                }
            }
            val completionTokens = TokenEstimator.estimateCompletionTokens(result)

            val responseJson = buildChatResponseJson(
                content = result,
                model = modelId,
                promptTokens = promptTokens,
                completionTokens = completionTokens
            )

            stats.addRequest(promptTokens, completionTokens)
            pendingResponseSummary = responseJson.take(500)
            jsonResponse(Response.Status.OK, responseJson)
        } catch (e: Exception) {
            val detail = "推理失败 · 类型: ${e.javaClass.simpleName} · 消息: ${e.message}\n" +
                "堆栈: ${e.stackTraceToString().take(800)}"
            lastErrorDetail = detail
            Log.e(TAG, detail, e)
            pendingResponseSummary = "❌ $detail".take(500)
            jsonResponse(Response.Status.INTERNAL_ERROR,
                """{"error":{"message":"Inference failed: ${e.message?.replace("\"", "\\\"")}"}}""")
        }
}

    private fun handleStreamResponse(
        prompt: String,
        systemPrompt: String?,
        promptTokens: Int,
        multimodalContents: List<Content>
    ): Response {
        val config = ConversationConfig(
            systemInstruction = if (systemPrompt != null) Contents.of(systemPrompt) else null
        )
        val conversation = llmEngine.createConversation(config)
        val isMultimodal = multimodalContents.size > 1

        // logIndex 由 serve() 在 logRequest 返回后通过 setter 注入
        var sseLogIndex = -1

        return object : Response(
            Response.Status.OK, "text/event-stream", ByteArray(0).inputStream(), -1
        ), SseResponse {
            override fun setLogIndex(idx: Int) { sseLogIndex = idx }

            override fun send(outputStream: OutputStream) {
                val streamStart = System.currentTimeMillis()
                val pw = PrintWriter(outputStream, true)
                pw.print("HTTP/1.1 200 OK\r\n")
                pw.print("Content-Type: text/event-stream; charset=utf-8\r\n")
                pw.print("Transfer-Encoding: chunked\r\n")
                pw.print("Access-Control-Allow-Origin: *\r\n")
                pw.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                pw.print("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
                pw.print("Connection: keep-alive\r\n")
                pw.print("\r\n")
                pw.flush()

                try {
                    val id = "chatcmpl-${UUID.randomUUID().toString().take(8)}"
                    val created = Instant.now().epochSecond

                    writeChunk(outputStream,
                        SseFormatter.buildSseChunk(id, modelId, created, "assistant", null))

                    var totalTokens = 0
                    runBlocking(Dispatchers.IO) {
                        val flow = if (isMultimodal) {
                            llmEngine.generateResponseAsync(conversation, Contents.of(multimodalContents))
                        } else {
                            llmEngine.generateResponseAsync(conversation, prompt)
                        }
                        flow.collect { token ->
                            totalTokens++
                            writeChunk(outputStream,
                                SseFormatter.buildSseChunk(id, modelId, created, null, token))
                        }
                    }

                    // 最后 chunk：done
                    writeChunk(outputStream,
                        SseFormatter.buildSseDone(id, modelId, created, promptTokens, totalTokens))
                    writeFinalChunk(outputStream)

                    stats.addRequest(promptTokens, totalTokens)
                    val elapsed = System.currentTimeMillis() - streamStart
                    if (sseLogIndex >= 0) {
                        setLogEntryAt(sseLogIndex,
                            "SSE 流式输出 · ${totalTokens} tokens", elapsed)
                    }
                } catch (e: IOException) {
                    Log.d(TAG, "SSE 客户端断开连接")
                } catch (e: Exception) {
                    val detail = "SSE流式失败 · 类型: ${e.javaClass.simpleName} · ${e.message}\n" +
                        "堆栈: ${e.stackTraceToString().take(800)}"
                    lastErrorDetail = detail
                    Log.e(TAG, detail, e)
                } finally {
                    try { conversation.close() } catch (_: Exception) {}
                }
            }
        }
    }

    /** 写一个 chunk 帧: <hex size>\r\n<data>\r\n */
    private fun writeChunk(outputStream: OutputStream, data: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        val header = "${bytes.size.toString(16)}\r\n"
        outputStream.write(header.toByteArray())
        outputStream.write(bytes)
        outputStream.write("\r\n".toByteArray())
        outputStream.flush()
    }

    /** 写终止 chunk: 0\r\n\r\n */
    private fun writeFinalChunk(outputStream: OutputStream) {
        outputStream.write("0\r\n\r\n".toByteArray())
        outputStream.flush()
    }

    private fun handleCorsPreflight(): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }

    private fun handleNotFound(): Response {
        return jsonResponse(Response.Status.NOT_FOUND,
            """{"error":{"message":"Not found"}}""")
    }

    // ── 工具方法 ──────────────────────────────────────────

    private fun jsonResponse(status: Response.Status, json: String): Response {
        return newFixedLengthResponse(status, "application/json", json)
    }

    private fun addCorsHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }

    /**
     * 用 UTF-8 读取请求体（绕过 NanoHTTPd parseBody 的默认编码问题）。
     * 优先按 Content-Length 读，缺失则读取最多 1MB。
     */
    private fun parseBodyUtf8(session: IHTTPSession): String {
        return try {
            val cl = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (cl > 0) {
                val bytes = session.inputStream.readNBytes(cl.coerceAtMost(1_048_576))
                String(bytes, Charsets.UTF_8)
            } else {
                // 没有 Content-Length，读取所有可用字节（最多 1MB）
                val bytes = session.inputStream.readNBytes(1_048_576)
                if (bytes.isEmpty()) "" else String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w(TAG, "UTF-8 读取请求体失败: ${e.message}")
            ""
        }
    }

    private fun buildChatResponseJson(
        content: String,
        model: String,
        promptTokens: Int,
        completionTokens: Int
    ): String {
        val id = "chatcmpl-${UUID.randomUUID().toString().take(8)}"
        val created = Instant.now().epochSecond
        return """
        {
          "id": "$id",
          "object": "chat.completion",
          "created": $created,
          "model": "$model",
          "choices": [{
            "index": 0,
            "message": {
              "role": "assistant",
              "content": ${escapeJson(content)}
            },
            "finish_reason": "stop"
          }],
          "usage": {
            "prompt_tokens": $promptTokens,
            "completion_tokens": $completionTokens,
            "total_tokens": ${promptTokens + completionTokens}
          }
        }
        """.trimIndent()
    }

    private fun escapeJson(text: String): String {
        return "\"${text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
    }

    // ── 日志与统计 ────────────────────────────────────────

    data class LogEntry(
        val timestamp: Long,
        val method: String,
        val path: String,
        val statusCode: Int,
        val elapsedMs: Long,
        val requestBody: String = "",
        val responseSummary: String = ""
    )

    class ServerStats {
        @Volatile var totalRequests = 0
        @Volatile var totalPromptTokens = 0L
        @Volatile var totalCompletionTokens = 0L
        @Volatile var startTime = System.currentTimeMillis()

        @Synchronized
        fun addRequest(promptTokens: Int, completionTokens: Int) {
            totalRequests++
            totalPromptTokens += promptTokens
            totalCompletionTokens += completionTokens
        }

        @Synchronized
        fun reset() {
            totalRequests = 0
            totalPromptTokens = 0
            totalCompletionTokens = 0
            startTime = System.currentTimeMillis()
        }
    }

    fun getStats(): ServerStats = stats
    fun getRequestLog(): List<LogEntry> = requestLog.toList()
    fun getPort(): Int = port

    /**
     * 按索引精确更新日志条目（用于 SSE 等异步场景）
     */
    private fun setLogEntryAt(index: Int, summary: String, elapsedMs: Long) {
        if (index >= 0 && index < requestLog.size) {
            requestLog[index] = requestLog[index].copy(
                responseSummary = summary.take(500),
                elapsedMs = elapsedMs
            )
        }
    }

    /** handler 内部补写请求体到最近一条日志 */
    private fun appendLastLogRequestDetail(body: String) {
        if (requestLog.isNotEmpty()) {
            val last = requestLog.last()
            requestLog[requestLog.lastIndex] = last.copy(requestBody = body)
        }
    }

    /** 诊断信息（供 UI Debug 面板使用） */
    fun getDebugInfo(): String {
        val engineReady = llmEngine.isInitialized
        val error5xx = requestLog.count { it.statusCode >= 500 }
        val error4xx = requestLog.count { it.statusCode in 400..499 }
        return buildString {
            appendLine("=== 服务器诊断 ===")
            appendLine("运行状态: ${if (isRunning) "✅ 运行中" else "⚫ 已停止"}")
            appendLine("端口: $port")
            appendLine("模型ID: $modelId")
            appendLine("引擎就绪: ${if (engineReady) "✅" else "❌ 未初始化"}")
            appendLine("请求总数: ${stats.totalRequests}")
            appendLine("成功(2xx): ${requestLog.count { it.statusCode in 200..299 }}")
            appendLine("客户端错误(4xx): $error4xx")
            appendLine("服务端错误(5xx): $error5xx")
            appendLine()
            if (lastErrorDetail != null) {
                appendLine("=== 最近错误 ===")
                appendLine(lastErrorDetail)
            }
        }
    }

    private fun logRequest(
        method: String,
        path: String,
        statusCode: Int,
        elapsedMs: Long,
        requestBody: String = "",
        responseSummary: String = ""
    ): Int {
        val entry = LogEntry(System.currentTimeMillis(), method, path, statusCode, elapsedMs,
            requestBody = requestBody, responseSummary = responseSummary)
        requestLog.add(entry)
        if (requestLog.size > 200) requestLog.removeAt(0)
        return nextLogIndex.getAndIncrement()
    }

    /**
     * SSE 流式响应的标记接口，用于 serve() 注入 logIndex
     */
    private interface SseResponse {
        fun setLogIndex(idx: Int)
    }
}

private val Response.Status.requestStatus: Int
    get() = when (this) {
        Response.Status.OK -> 200
        Response.Status.CREATED -> 201
        Response.Status.ACCEPTED -> 202
        Response.Status.NO_CONTENT -> 204
        Response.Status.BAD_REQUEST -> 400
        Response.Status.UNAUTHORIZED -> 401
        Response.Status.NOT_FOUND -> 404
        Response.Status.INTERNAL_ERROR -> 500
        else -> 200
    }