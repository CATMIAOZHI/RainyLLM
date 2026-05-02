package com.rainyllm.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.rainyllm.app.engine.ConversationPool
import com.rainyllm.app.engine.LlmEngine
import com.rainyllm.app.server.OpenAIServer
import com.rainyllm.app.data.AppPreferences
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.first

/**
 * LLM 推理服务器前台服务
 * 保活引擎和 HTTP 服务器，显示常驻通知
 */
class LlmServerService : Service() {

    companion object {
        private const val TAG = "LlmServerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "rainyllm_server"
        private const val CHANNEL_NAME = "RainyLLM 服务器"

        const val ACTION_START_SERVER = "com.rainyllm.app.START_SERVER"
        const val ACTION_STOP_SERVER = "com.rainyllm.app.STOP_SERVER"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_CACHE_DIR = "cache_dir"
        const val EXTRA_PORT = "port"
        const val EXTRA_MODEL_ID = "model_id"

        /** 最近一次初始化错误信息，供 UI 读取（成功时自动清空） */
        @Volatile
        var lastInitError: String? = null
            private set

        private fun setLastInitError(msg: String?) {
            lastInitError = msg
        }
    }

    private var llmEngine: LlmEngine? = null
    private var openAIServer: OpenAIServer? = null
    private var conversationPool: ConversationPool? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** 修复：持有初始化线程引用，支持 stopAll() 中断 */
    @Volatile private var initThread: Thread? = null
    /** 修复：原子标记防止重复初始化 */
    @Volatile private var isInitializing: Boolean = false

    // 公开可查询
    var isEngineReady: Boolean = false
        private set
    var serverPort: Int = 8080
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "服务创建")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: return START_NOT_STICKY
                val cacheDirPath = intent.getStringExtra(EXTRA_CACHE_DIR) ?: cacheDir.path
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: "gemma4-e2b"

                startForegroundNotification()
                initializeEngine(modelPath, cacheDirPath, port, modelId)
            }
            ACTION_STOP_SERVER -> {
                stopAll()
                stopSelf()
            }
            null -> {
                // 服务被系统重建但无 intent，不自动重启引擎
                Log.w(TAG, "服务重建但未收到指令，等待显式启动")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
        Log.i(TAG, "服务销毁")
    }

    private fun initializeEngine(modelPath: String, cacheDir: String, port: Int, modelId: String) {
        // 修复：防重入 — 如果正在初始化则忽略后续请求
        if (isInitializing) {
            Log.w(TAG, "引擎已在初始化中，忽略重复启动请求")
            return
        }
        isInitializing = true
        // 清空上次错误
        setLastInitError(null)
        // 修复：先中断旧线程防止重复初始化
        initThread?.interrupt()
        val thread = Thread {
            try {
                updateNotification("正在加载模型…")

                // 获取 WakeLock
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "RainyLLM:Inference"
                ).apply {
                    acquire(10 * 60 * 1000L) // 10 分钟超时
                }

                // 启用 benchmark 以获取真实 token 计数
                @OptIn(ExperimentalApi::class)
                ExperimentalFlags.enableBenchmark = true

                // 从设置读取空闲超时与推理参数（必须在引擎创建前）
                val prefs = AppPreferences(this@LlmServerService)
                val timeoutMin = kotlinx.coroutines.runBlocking { prefs.idleTimeoutMin.first() }
                val timeoutMs = timeoutMin * 60 * 1000L
                val maxTokens = kotlinx.coroutines.runBlocking { prefs.maxTokens.first() }

                val samplerConfig = SamplerConfig(
                    temperature = kotlinx.coroutines.runBlocking { prefs.temperature.first() }.toDouble(),
                    topK = kotlinx.coroutines.runBlocking { prefs.topK.first() },
                    topP = kotlinx.coroutines.runBlocking { prefs.topP.first() }.toDouble()
                )

                // 从设置读取推理后端（CPU/GPU）
                val backendStr = kotlinx.coroutines.runBlocking { prefs.backend.first() }
                val backend = when (backendStr.lowercase()) {
                    "gpu" -> Backend.GPU()
                    else -> Backend.CPU()
                }
                Log.i(TAG, "推理后端: $backendStr → $backend")

                // 检查是否已被中断
                if (Thread.currentThread().isInterrupted) return@Thread

                // 初始化引擎（initialize 是 suspend 函数，需 runBlocking 桥接）
                val engine = LlmEngine(
                    modelPath, cacheDir,
                    visionBackend = Backend.GPU(),
                    audioBackend = Backend.CPU(),
                    maxNumTokens = maxTokens
                )

                // ★ 关键修复：必须调用 initialize() 才能真正加载 JNI 引擎
                kotlinx.coroutines.runBlocking {
                    engine.initialize(backend = backend)
                }
                llmEngine = engine
                Log.i(TAG, "引擎 initialize() 完成，模型已加载")

                // 再次检查中断
                if (Thread.currentThread().isInterrupted) return@Thread

                conversationPool = ConversationPool(engine, idleTimeoutMs = timeoutMs, samplerConfig = samplerConfig)
                conversationPool?.startAutoCleanup()

                // 启动 HTTP 服务器，传入动态 samplerConfig 供应商以支持运行时设置变更
                val server = OpenAIServer(port, engine, modelId, samplerConfig,
                    samplerConfigSupplier = {
                        val p = AppPreferences(this@LlmServerService)
                        kotlinx.coroutines.runBlocking {
                            SamplerConfig(
                                temperature = p.temperature.first().toDouble(),
                                topK = p.topK.first(),
                                topP = p.topP.first().toDouble()
                            )
                        }
                    }
                )
                server.start()
                openAIServer = server

                serverPort = port
                isEngineReady = true

                updateNotification("🤖 RainyLLM 运行中 | 端口: $port")
                Log.i(TAG, "✅ 引擎 + 服务器初始化完成")

            } catch (e: InterruptedException) {
                Log.i(TAG, "初始化线程被中断")
            } catch (e: Exception) {
                Log.e(TAG, "初始化失败: ${e.message}", e)
                isEngineReady = false
                val errMsg = e.message ?: "未知错误"
                setLastInitError(errMsg)
                updateNotification("⚠️ 加载失败: $errMsg")
                // ★ Bug修复：引擎初始化失败后停止服务，避免僵尸状态
                stopAll()
                stopSelf()
            } finally {
                isInitializing = false
            }
        }
        initThread = thread
        thread.start()
    }

    private fun stopAll() {
        try {
            isInitializing = false
            // 修复：中断初始化线程，防止泄漏
            initThread?.interrupt()
            initThread = null
            openAIServer?.stop()
            conversationPool?.shutdown()
            llmEngine?.close()
            wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "停止时异常: ${e.message}")
        } finally {
            openAIServer = null
            conversationPool = null
            llmEngine = null
            wakeLock = null
            isEngineReady = false
        }
    }

    // ── 通知管理 ──────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "RainyLLM 推理服务器运行状态"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notification = buildNotification("🤖 RainyLLM 启动中…")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = Intent(this, com.rainyllm.app.MainActivity::class.java).let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("RainyLLM")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("RainyLLM")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}