package com.rainyllm.app.server

import android.util.Base64
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.rainyllm.app.data.StatsRepository
import com.rainyllm.app.engine.LlmEngine
import com.rainyllm.app.engine.TokenEstimator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
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
    private val modelId: String = "gemma4-e2b",
    private val defaultSamplerConfig: SamplerConfig? = null,
    /** 动态读取最新 SamplerConfig 的供应商（优先于 defaultSamplerConfig） */
    private val samplerConfigSupplier: (() -> SamplerConfig)? = null
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
    /** ★ 序列化锁：LiteRT-LM 只支持单一活跃会话，防止并发请求互相干扰 */
    private val inferenceLock = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var nextLogIndex = java.util.concurrent.atomic.AtomicInteger(0)
    private val requestLog = java.util.concurrent.CopyOnWriteArrayList<LogEntry>()

    /** 暂存响应摘要（handler 先于 logRequest 执行，需滞后写入） */
    @Volatile
    private var pendingResponseSummary: String? = null

    /** 暂存请求体（同上 — handler 中读取，serve() 中写入日志） */
    @Volatile
    private var pendingRequestBody: String? = null

    /** 暂存 token 计数（sync 路径 — serve() 中滞后写入） */
    @Volatile
    private var pendingPromptTokens: Int = 0
    @Volatile
    private var pendingCompletionTokens: Int = 0

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
                setLogEntryAt(logIdx, it, elapsed,
                    pendingPromptTokens, pendingCompletionTokens)
                pendingResponseSummary = null
                pendingPromptTokens = 0
                pendingCompletionTokens = 0
            }

            // 请求体同理 — handler 中暂存，此处滞后写入
            pendingRequestBody?.let {
                setLogEntryBody(logIdx, it)
                pendingRequestBody = null
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
        val data = """{"object":"list","data":[{"id":"$modelId","object":"model","owned_by":"rainyllm"},{"id":"yuqing","object":"model","owned_by":"rainyllm"}]}"""
        return jsonResponse(Response.Status.OK, data)
    }

    private fun handleChatCompletion(session: IHTTPSession): Response {
        val bodyJson = parseBodyUtf8(session)
        if (bodyJson.isBlank())
            return jsonResponse(Response.Status.BAD_REQUEST,
                """{"error":{"message":"Empty request body"}}""")

        pendingRequestBody = bodyJson

        val request = RequestParser.parseChatCompletionRequest(bodyJson)
        val isStream = request["stream"] as? Boolean ?: false

        val requestModel = (request["model"] as? String)?.takeUnless { it.isBlank() }
        val responseModel = requestModel ?: modelId

        val requestSamplerConfig = buildRequestSamplerConfig(request)

        // ★ 解析 tools → LiteRT-LM Tool 列表
        val toolsParam = request["tools"] as? List<Map<String, Any>>
        val lmTools: List<com.google.ai.edge.litertlm.ToolProvider>? = toolsParam?.mapNotNull { t ->
            val fn = t["function"] as? Map<*, *> ?: return@mapNotNull null
            ToolAdapter(
                name = fn["name"]?.toString() ?: return@mapNotNull null,
                description = fn["description"]?.toString() ?: "",
                parametersJson = fn["parameters"]?.toString() ?: "{}"
            ).let { tool(it) }
        }?.takeUnless { it.isEmpty() }

        // ── 解析 messages 数组 ──
        val allMessages = (request["messages"] as? List<Map<String, Any>>) ?: emptyList()
        if (allMessages.isEmpty())
            return jsonResponse(Response.Status.BAD_REQUEST,
                """{"error":{"message":"No messages provided"}}""")

        // system 消息
        val systemPrompt = allMessages
            .firstOrNull { it["role"] == "system" }
            ?.get("content")?.toString()

        // 非 system 消息
        val nonSystem = allMessages.filter { it["role"] != "system" }
        if (nonSystem.isEmpty())
            return jsonResponse(Response.Status.BAD_REQUEST,
                """{"error":{"message":"No user/system messages provided"}}""")

        // ★ 关键修复：无状态 API — 所有非 system 消息（含 tool）都是上下文
        // 最后一条消息之前的全部 → initialMessages（预填 KV Cache）
        // 最后一条消息 → 当前 prompt（sendMessage 输入）
        val historyRaw = nonSystem.dropLast(1)
        val currentMsg = nonSystem.last()

        // ── 解析当前 prompt ──
        // 最后一条非 system 消息不一定是 user（可能是 tool）
        var prompt: String
        val multimodalContents = mutableListOf<Content>()
        val contentField = currentMsg["content"]
        val currentRole = currentMsg["role"]?.toString() ?: "user"

        when {
            // 多模态 user 消息
            contentField is List<*> -> {
                val textParts = mutableListOf<String>()
                for (part in contentField) {
                    val partMap = part as? Map<*, *> ?: continue
                    when (partMap["type"]) {
                        "text" -> { val t = partMap["text"]?.toString() ?: ""; if (t.isNotBlank()) textParts.add(t) }
                        "image_url" -> {
                            val imgUrl = (partMap["image_url"] as? Map<*, *>)?.get("url")?.toString()
                            if (imgUrl != null) {
                                val bytes = decodeDataUrl(imgUrl)
                                if (bytes != null) multimodalContents.add(Content.ImageBytes(bytes))
                                else if (!imgUrl.startsWith("data:")) {
                                    try { val f = java.io.File(imgUrl); if (f.exists()) multimodalContents.add(Content.ImageFile(imgUrl)) } catch (_: Exception) {}
                                }
                            }
                        }
                        "input_audio" -> {
                            val d = (partMap["input_audio"] as? Map<*, *>)?.get("data")?.toString()
                            if (d != null) multimodalContents.add(Content.AudioBytes(Base64.decode(d, Base64.DEFAULT)))
                        }
                    }
                }
                prompt = textParts.joinToString("\n").ifBlank { "请描述以下内容" }
                multimodalContents.add(Content.Text(prompt))
            }
            // tool 消息 — 将工具返回结果作为文本继续推理
            currentRole == "tool" -> {
                prompt = contentField?.toString() ?: "继续"
            }
            // 普通文本（user / assistant）
            contentField is String -> prompt = contentField
            else -> prompt = contentField?.toString() ?: "继续"
        }

        // ── 构建 initialMessages ──
        val kvMax = llmEngine.maxNumTokens ?: 4096
        val historyLm = try {
            buildHistoryMessages(historyRaw, systemPrompt, kvMax)
        } catch (e: ContextOverflowException) {
            return jsonResponse(Response.Status.BAD_REQUEST,
                """{"error":{"message":"${e.message?.replace("\"", "\\\"")}"}}""")
        }

        val promptTokens = if (multimodalContents.size > 1) {
            val counts = TokenEstimator.countMultimodal(multimodalContents)
            TokenEstimator.estimateMultimodalPromptTokens(prompt, counts)
        } else {
            TokenEstimator.estimatePromptTokens(prompt)
        }

        return if (isStream) {
            handleStreamResponse(prompt, systemPrompt, promptTokens, multimodalContents,
                responseModel, requestSamplerConfig, historyLm, lmTools)
        } else {
            handleSyncResponse(prompt, systemPrompt, promptTokens, multimodalContents,
                responseModel, requestSamplerConfig, historyLm, lmTools)
        }
    }

    /** 将 client 发来的历史消息转为 LiteRT-LM Message */
    private fun buildHistoryMessages(
        history: List<Map<String, Any>>,
        systemPrompt: String?,
        kvMaxTokens: Int
    ): List<com.google.ai.edge.litertlm.Message> {
        if (history.isEmpty()) return emptyList()

        // 第一遍：收集 assistant 消息中的 tool_call id→name 映射
        val toolNameMap = mutableMapOf<String, String>()
        for (msg in history) {
            if (msg["role"] == "assistant") {
                val toolCalls = msg["tool_calls"] as? List<Map<String, Any>>
                if (toolCalls != null) {
                    for (tc in toolCalls) {
                        val id = tc["id"]?.toString() ?: continue
                        val name = (tc["function"] as? Map<*, *>)?.get("name")?.toString() ?: continue
                        toolNameMap[id] = name
                    }
                }
            }
        }

        // 第二遍：按序转为 LiteRT-LM Message
        val allLm = history.mapNotNull { msg ->
            val role = msg["role"]?.toString() ?: return@mapNotNull null
            when (role) {
                "user" -> {
                    val content = msg["content"]?.toString() ?: return@mapNotNull null
                    com.google.ai.edge.litertlm.Message.user(content)
                }
                "assistant" -> {
                    val content = msg["content"]?.toString()?.takeIf { it.isNotBlank() } ?: ""
                    // 如果有 tool_calls 但 content 为空，用简记标记（不影响 KV Cache 语义）
                    val hasToolCalls = msg["tool_calls"] != null
                    val effectiveContent = if (hasToolCalls && content.isEmpty()) "(tool)" else content
                    com.google.ai.edge.litertlm.Message.model(effectiveContent)
                }
                "tool" -> {
                    val toolCallId = msg["tool_call_id"]?.toString() ?: return@mapNotNull null
                    val toolName = toolNameMap[toolCallId] ?: toolCallId
                    val content = msg["content"]?.toString() ?: return@mapNotNull null
                    val response = Content.ToolResponse(toolName, content)
                    com.google.ai.edge.litertlm.Message.tool(Contents.of(listOf(response)))
                }
                else -> null
            }
        }

        // 估算总 token 数，超限直接报错
        var totalEst = systemPrompt?.let { TokenEstimator.estimatePromptTokens(it) } ?: 0
        for (m in allLm) totalEst += TokenEstimator.estimatePromptTokens(m.toString())

        if (totalEst > kvMaxTokens) {
            throw ContextOverflowException(
                "输入上下文过大：${"%.1f".format(totalEst / 1000.0)}K tokens，超过 KV Cache 上限 ${"%.1f".format(kvMaxTokens / 1000.0)}K。" +
                "请在设置中增大 max_tokens 或缩短对话历史。"
            )
        }

        return allLm
    }

    private class ContextOverflowException(message: String) : Exception(message)

    /**
     * 从请求参数构建 SamplerConfig，若客户端传了 temperature/top_p 则覆盖默认值。
     * 返回 null 表示客户端未传任何参数，使用当前动态配置。
     */
    private fun buildRequestSamplerConfig(request: Map<String, Any>): SamplerConfig? {
        val hasTemp = request.containsKey("temperature")
        val hasTopP = request.containsKey("top_p")
        if (!hasTemp && !hasTopP) return null

        val base = samplerConfigSupplier?.invoke() ?: defaultSamplerConfig
            ?: SamplerConfig(temperature = 0.7, topK = 40, topP = 0.95)
        return SamplerConfig(
            temperature = if (hasTemp) (request["temperature"] as? Number)?.toDouble() ?: base.temperature
                          else base.temperature,
            topK = base.topK,
            topP = if (hasTopP) (request["top_p"] as? Number)?.toDouble() ?: base.topP
                      else base.topP
        )
    }

private fun handleSyncResponse(
        prompt: String,
        systemPrompt: String?,
        promptTokens: Int,
        multimodalContents: List<Content>,
        responseModel: String = modelId,
        requestSamplerConfig: SamplerConfig? = null,
        historyMessages: List<com.google.ai.edge.litertlm.Message> = emptyList(),
        tools: List<com.google.ai.edge.litertlm.ToolProvider>? = null
    ): Response {
        val syncStart = System.currentTimeMillis()
        return try {
            val sampler = requestSamplerConfig ?: samplerConfigSupplier?.invoke() ?: defaultSamplerConfig
            val config = ConversationConfig(
                systemInstruction = if (systemPrompt != null) Contents.of(systemPrompt) else null,
                samplerConfig = sampler,
                initialMessages = historyMessages,
                tools = tools ?: emptyList(),
                automaticToolCalling = false  // 不自动执行 → 返回 tool_calls 给客户端
            )
            val isMultimodal = multimodalContents.size > 1
            val result: String
            val actualPromptTokens: Int
            val actualCompletionTokens: Int
            var toolCallsData: List<ToolCallData>? = null

            runBlocking {
                inferenceLock.lock()
                try {
                    llmEngine.createConversation(config).use { conversation ->
                        val responseMsg = if (isMultimodal) {
                            conversation.sendMessage(Contents.of(multimodalContents))
                        } else {
                            conversation.sendMessage(prompt)
                        }
                        result = responseMsg.toString()

                        // 检查 tool_calls
                        if (responseMsg.toolCalls.isNotEmpty()) {
                            toolCallsData = responseMsg.toolCalls.mapIndexed { idx, tc ->
                                ToolCallData(
                                    id = "call_${UUID.randomUUID().toString().take(8)}",
                                    name = tc.name,
                                    arguments = (tc.arguments as? Map<*, *>)?.let { JSONObject(it).toString() } ?: "{}"
                                )
                            }
                        }

                        // 尝试从引擎获取真实 token 计数（需要 benchmark 模式启用）
                        actualPromptTokens = getBenchmarkTokenCount(conversation) { it.lastPrefillTokenCount }
                        actualCompletionTokens = getBenchmarkTokenCount(conversation) { it.lastDecodeTokenCount }
                    }
                } finally {
                    inferenceLock.unlock()
                }
            }

            // 若 benchmark 不可用，回退到估算
            val finalPromptTokens = if (actualPromptTokens > 0)
                actualPromptTokens
            else if (isMultimodal) {
                val counts = TokenEstimator.countMultimodal(multimodalContents)
                TokenEstimator.estimateMultimodalPromptTokens(prompt, counts)
            } else promptTokens

            val finalCompletionTokens = if (actualCompletionTokens > 0)
                actualCompletionTokens
            else
                TokenEstimator.estimateCompletionTokens(result)

            val responseJson = buildChatResponseJson(
                content = if (toolCallsData != null) null else result,
                model = responseModel,
                promptTokens = finalPromptTokens,
                completionTokens = finalCompletionTokens,
                toolCalls = toolCallsData
            )

            val durationMs = System.currentTimeMillis() - syncStart
            stats.addRequest(finalPromptTokens, finalCompletionTokens)
            StatsRepository.instance?.addRecord(
                StatsRepository.InferenceRecord(
                    timestamp = System.currentTimeMillis(),
                    model = responseModel,
                    promptTokens = finalPromptTokens,
                    completionTokens = finalCompletionTokens,
                    durationMs = durationMs
                )
            )
            pendingResponseSummary = responseJson
            pendingPromptTokens = finalPromptTokens
            pendingCompletionTokens = finalCompletionTokens
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
        multimodalContents: List<Content>,
        responseModel: String = modelId,
        requestSamplerConfig: SamplerConfig? = null,
        historyMessages: List<com.google.ai.edge.litertlm.Message> = emptyList(),
        tools: List<com.google.ai.edge.litertlm.ToolProvider>? = null
    ): Response {
        val sampler = requestSamplerConfig ?: samplerConfigSupplier?.invoke() ?: defaultSamplerConfig
        val config = ConversationConfig(
            systemInstruction = if (systemPrompt != null) Contents.of(systemPrompt) else null,
            samplerConfig = sampler,
            initialMessages = historyMessages,
            tools = tools ?: emptyList(),
            automaticToolCalling = false
        )
        val conversation = llmEngine.createConversation(config)
        val isMultimodal = multimodalContents.size > 1
        var sseLogIndex = -1

        return object : Response(
            Response.Status.OK, "text/event-stream", ByteArray(0).inputStream(), -1
        ), SseResponse {
            override fun setLogIndex(idx: Int) { sseLogIndex = idx }

            override fun send(outputStream: OutputStream) {
                val streamStart = System.currentTimeMillis()
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/event-stream; charset=utf-8\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                    append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
                    append("Connection: keep-alive\r\n")
                    append("\r\n")
                }
                outputStream.write(header.toByteArray(Charsets.UTF_8))
                outputStream.flush()

                var totalResponseText = ""
                var lastMessage: com.google.ai.edge.litertlm.Message? = null

                try {
                    val id = "chatcmpl-${UUID.randomUUID().toString().take(8)}"
                    val created = Instant.now().epochSecond

                    writeSseFrame(outputStream,
                        SseFormatter.buildSseChunk(id, responseModel, created, "assistant", null))

                    inferenceLock.lock()
                    try {
                        runBlocking {
                            val rawFlow = if (isMultimodal) {
                                conversation.sendMessageAsync(Contents.of(multimodalContents))
                            } else {
                                conversation.sendMessageAsync(prompt)
                            }
                            rawFlow.collect { msg ->
                                lastMessage = msg
                                val text = msg.toString()
                                totalResponseText += text
                                writeSseFrame(outputStream,
                                    SseFormatter.buildSseChunk(id, responseModel, created, null, text))
                            }
                        }

                        // ★ 检测 tool_calls — 若有则在流中发送
                        val streamToolCalls = lastMessage?.toolCalls?.takeIf { it.isNotEmpty() }
                        if (streamToolCalls != null) {
                            writeSseFrame(outputStream,
                                SseFormatter.buildSseToolCalls(id, responseModel, created, streamToolCalls))
                        }

                        val actualDecode = getBenchmarkTokenCount(conversation) { it.lastDecodeTokenCount }
                        val actualPrefill = getBenchmarkTokenCount(conversation) { it.lastPrefillTokenCount }
                        val finalCompletionTokens = if (actualDecode > 0) actualDecode
                            else TokenEstimator.estimateCompletionTokens(totalResponseText)
                        val finalPromptTokens = if (actualPrefill > 0) actualPrefill else promptTokens

                        writeSseFrame(outputStream,
                            SseFormatter.buildSseDone(id, responseModel, created, finalPromptTokens, finalCompletionTokens))

                        stats.addRequest(finalPromptTokens, finalCompletionTokens)
                        val elapsed = System.currentTimeMillis() - streamStart
                        StatsRepository.instance?.addRecord(
                            StatsRepository.InferenceRecord(
                                timestamp = System.currentTimeMillis(),
                                model = responseModel,
                                promptTokens = finalPromptTokens,
                                completionTokens = finalCompletionTokens,
                                durationMs = elapsed
                            )
                        )
                        if (sseLogIndex >= 0) {
                            setLogEntryAt(sseLogIndex,
                                "SSE 流式 · ${finalCompletionTokens} tokens" +
                                    if (totalResponseText.isNotEmpty()) "\n\n$totalResponseText" else "",
                                elapsed, finalPromptTokens, finalCompletionTokens)
                        }
                    } finally {
                        inferenceLock.unlock()
                    }
                } catch (e: IOException) {
                    Log.d(TAG, "SSE 客户端断开连接")
                } catch (e: Exception) {
                    lastErrorDetail = "SSE流式失败 · ${e.javaClass.simpleName} · ${e.message}\n${e.stackTraceToString().take(800)}"
                    Log.e(TAG, lastErrorDetail, e)
                } finally {
                    try { conversation.close() } catch (_: Exception) {}
                }
            }
        }
    }

    /** 写 SSE 数据帧：直接裸写，SSE 自带 data:\\n\\n 帧格式，无需 HTTP chunked */
    private fun writeSseFrame(outputStream: OutputStream, data: String) {
        outputStream.write(data.toByteArray(Charsets.UTF_8))
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
                val bytes = session.inputStream.readNBytes(cl)
                String(bytes, Charsets.UTF_8)
            } else {
                val bytes = session.inputStream.readBytes()
                if (bytes.isEmpty()) "" else String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w(TAG, "UTF-8 读取请求体失败: ${e.message}")
            ""
        }
    }

    private data class ToolCallData(
        val id: String,
        val name: String,
        val arguments: String
    )

    private fun buildChatResponseJson(
        content: String?,
        model: String,
        promptTokens: Int,
        completionTokens: Int,
        toolCalls: List<ToolCallData>? = null
    ): String {
        val id = "chatcmpl-${UUID.randomUUID().toString().take(8)}"
        val created = Instant.now().epochSecond

        val messageObj = JSONObject().apply {
            put("role", "assistant")
            if (toolCalls != null) {
                put("content", JSONObject.NULL)
                put("tool_calls", JSONArray().apply {
                    toolCalls.forEachIndexed { idx, tc ->
                        put(JSONObject().apply {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tc.name)
                                put("arguments", tc.arguments)
                            })
                        })
                    }
                })
            } else {
                put("content", content ?: "")
            }
        }

        return JSONObject().apply {
            put("id", id)
            put("object", "chat.completion")
            put("created", created)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", messageObj)
                put("finish_reason", if (toolCalls != null) "tool_calls" else "stop")
            }))
            put("usage", JSONObject().apply {
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTokens)
                put("total_tokens", promptTokens + completionTokens)
            })
        }.toString()
    }

    // ── 日志与统计 ────────────────────────────────────────

    data class LogEntry(
        val timestamp: Long,
        val method: String,
        val path: String,
        val statusCode: Int,
        val elapsedMs: Long,
        val requestBody: String = "",
        val responseSummary: String = "",
        val promptTokens: Int = 0,
        val completionTokens: Int = 0
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
    private fun setLogEntryAt(
        index: Int, summary: String, elapsedMs: Long,
        promptTokens: Int = 0, completionTokens: Int = 0
    ) {
        if (index >= 0 && index < requestLog.size) {
            requestLog[index] = requestLog[index].copy(
                responseSummary = summary,
                elapsedMs = elapsedMs,
                promptTokens = promptTokens,
                completionTokens = completionTokens
            )
        }
    }

    /** handler 内部补写请求体到指定索引的日志条目 */
    private fun setLogEntryBody(index: Int, body: String) {
        if (index >= 0 && index < requestLog.size) {
            requestLog[index] = requestLog[index].copy(requestBody = body)
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
        if (requestLog.size > 1000) requestLog.removeAt(0)
        return nextLogIndex.getAndIncrement()
    }

    /** 清空请求日志 */
    fun clearRequestLog() {
        requestLog.clear()
    }

    /**
     * 安全获取 benchmark token 计数（需要 @OptIn ExperimentalApi）
     * 若 benchmark 未启用或调用失败则返回 0，调用方回退到估算。
     */
    @OptIn(ExperimentalApi::class)
    private fun getBenchmarkTokenCount(
        conversation: com.google.ai.edge.litertlm.Conversation,
        extract: (com.google.ai.edge.litertlm.BenchmarkInfo) -> Int
    ): Int {
        return try {
            extract(conversation.getBenchmarkInfo())
        } catch (_: Exception) {
            0
        }
    }

    /**
     * SSE 流式响应的标记接口，用于 serve() 注入 logIndex
     */
    private interface SseResponse {
        fun setLogIndex(idx: Int)
    }
}

/**
 * 将 OpenAI 格式的 function 定义转为 LiteRT-LM OpenApiTool。
 * 由于 automaticToolCalling = false，execute() 永远不会被调用。
 */
private class ToolAdapter(
    private val name: String,
    private val description: String,
    private val parametersJson: String
) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String {
        val escapedDesc = org.json.JSONObject.quote(description)
        return """{"name":${org.json.JSONObject.quote(name)},"description":$escapedDesc,"parameters":$parametersJson}"""
    }

    override fun execute(paramsJsonString: String): String = "{}"
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