package com.rainyllm.app.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.*
import com.rainyllm.app.MainActivity
import com.rainyllm.app.RainyLLMApp
import com.rainyllm.app.data.AppPreferences
import com.rainyllm.app.data.StatsRepository
import com.rainyllm.app.model.ModelRepository
import com.rainyllm.app.server.OpenAIServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局悬浮窗管理器（进程级单例）
 * 支持完整模式 ↔ 最小化圆形图标模式切换。
 */
object FloatingWindowManager {

    private const val TAG = "FloatingWindow"
    private const val REFRESH_MS = 1000L
    private const val DOUBLE_CONFIRM_TIMEOUT_MS = 3000L

    private var windowManager: WindowManager? = null
    private var fullView: LinearLayout? = null       // 完整悬浮窗
    private var miniView: FrameLayout? = null        // 最小化圆形图标
    private var currentView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var isMinimized = false
    private var userEnabled = true

    private var statusText: TextView? = null
    private var modelText: TextView? = null
    private var infoText: TextView? = null
    private var statsText: TextView? = null
    private var mainBtn: Button? = null
    private var actionBtn: Button? = null

    private var mainBtnPending = false
    private var actionBtnPending = false
    private var pendingWhat = ""
    private var isStartingServer = false
    private var isStoppingServer = false

    private val handler = Handler(Looper.getMainLooper())
    private val confirmResetRunnable = Runnable { resetAllConfirmStates() }
    private var touchSlop = 0

    // RainyLLM 元气猫系粉色调 🐱💖
    private val bgColor = Color.parseColor("#E83D262B")       // 暗粉底，半透明
    private val titleColor = Color.parseColor("#FFFF85A2")     // 草莓粉
    private val statusRunningColor = Color.parseColor("#FFFFA5B5") // 浅樱粉
    private val statusStoppedColor = Color.parseColor("#FFFF6B8E") // 深粉红
    private val btnNormalBg = Color.parseColor("#40FFFFFF")
    private val btnConfirmBg = Color.parseColor("#FFFF8C00")
    private val btnStartBg = Color.parseColor("#FFFF85A2")     // 草莓粉
    private val btnStopBg = Color.parseColor("#FFFF6B8E")      // 粉红
    private val btnDisabledBg = Color.parseColor("#20FFFFFF")
    private val dividerColor = Color.parseColor("#40FFA5B5")   // 浅粉半透明
    private val textWhite = Color.WHITE
    private val textDim = Color.parseColor("#CCBBBBBB")
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    }

    fun setEnabled(enabled: Boolean) {
        userEnabled = enabled
        if (enabled && canDrawOverlays()) show() else if (!enabled) hide()
    }

    fun canDrawOverlays(): Boolean {
        val ctx = RainyLLMApp.instance
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(ctx)
        } else true
    }

    fun requestOverlayPermission() {
        val ctx = RainyLLMApp.instance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ctx.startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    // ── 显示 / 最小化 / 还原 ────────────────────────────

    fun show() {
        if (isShowing || !userEnabled || !canDrawOverlays()) return
        val wm = windowManager ?: return
        val ctx = RainyLLMApp.instance
        val density = ctx.resources.displayMetrics.density

        // 加载 App 图标
        val iconRes = ctx.resources.getIdentifier("ic_launcher", "mipmap", ctx.packageName)
        val appIcon: Drawable? = if (iconRes != 0) {
            ctx.resources.getDrawable(iconRes, ctx.theme)
        } else null

        val dp8 = (8 * density).toInt()
        val dp6 = (6 * density).toInt()
        val dp4 = (4 * density).toInt()
        val dp2 = (2 * density).toInt()
        val dp80 = (80 * density).toInt()
        val dp36 = (36 * density).toInt()
        val screenWidth = ctx.resources.displayMetrics.widthPixels

        // ── 构建完整悬浮窗 ──
        fullView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp8, dp6, dp8, dp6)
            setBackgroundColor(bgColor)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp8.toFloat())
                }
            }
            elevation = dp4.toFloat()
        }

        // 标题行："雨晴LLM" + 最小化按钮
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp4 }
        }

        val titleText = TextView(ctx).apply {
            text = "☁️ 雨晴LLM"
            setTextColor(titleColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleText)

        val minimizeBtn = Button(ctx).apply {
            text = "−"
            setTextColor(textDim)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 12f
            setPadding(dp4, 0, dp4, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp6 * 3
            )
            setOnClickListener { minimize() }
        }
        titleRow.addView(minimizeBtn)
        fullView!!.addView(titleRow)

        // 分隔线
        val titleDivider = View(ctx).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp2
            ).apply { bottomMargin = dp4 }
        }
        fullView!!.addView(titleDivider)

        statusText = TextView(ctx).apply {
            setTextColor(textWhite); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp2); text = "● 就绪"
        }
        fullView!!.addView(statusText)

        modelText = TextView(ctx).apply {
            setTextColor(textDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, 0, dp2); text = "模型: —"
        }
        fullView!!.addView(modelText)

        infoText = TextView(ctx).apply {
            setTextColor(textDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, 0, dp2); text = "⏱ —"
        }
        fullView!!.addView(infoText)

        statsText = TextView(ctx).apply {
            setTextColor(textDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, 0, dp4); text = "📊 —"
        }
        fullView!!.addView(statsText)

        val divider = View(ctx).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp2
            ).apply { bottomMargin = dp4 }
        }
        fullView!!.addView(divider)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        mainBtn = Button(ctx).apply {
            text = "主界面"; setTextColor(textWhite); setBackgroundColor(btnNormalBg)
            textSize = 11f; setPadding(dp6, dp4, dp6, dp4)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, dp4, 0) }
            setOnClickListener { onMainButtonClick() }
        }
        btnRow.addView(mainBtn)
        actionBtn = Button(ctx).apply {
            text = "启动服务"; setTextColor(textWhite); setBackgroundColor(btnStartBg)
            textSize = 11f; setPadding(dp6, dp4, dp6, dp4)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onActionButtonClick() }
        }
        btnRow.addView(actionBtn)
        fullView!!.addView(btnRow)

        // ── 构建最小化圆形图标 ──
        miniView = FrameLayout(ctx).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            setBackgroundColor(bgColor)
            elevation = dp4.toFloat()
            setOnClickListener { restore() }

            val icon = ImageView(ctx).apply {
                setImageDrawable(appIcon ?: android.R.drawable.ic_dialog_info.let {
                    ctx.resources.getDrawable(it, ctx.theme)
                })
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(dp36, dp36, Gravity.CENTER)
                alpha = 0.8f
            }
            addView(icon)
        }

        // ── WindowManager 参数 ──
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        fullView!!.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val viewW = fullView!!.measuredWidth.coerceAtLeast((160 * density).toInt())

        layoutParams = WindowManager.LayoutParams(
            viewW,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - viewW - dp8).coerceAtLeast(0)
            y = dp80
        }

        currentView = if (isMinimized) miniView else fullView
        setupDrag(currentView!!, layoutParams!!)

        try {
            wm.addView(currentView, layoutParams)
            isShowing = true
            startRefreshLoop()
            Log.i(TAG, if (isMinimized) "最小化悬浮窗已显示" else "悬浮窗已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示悬浮窗失败: ${e.message}", e)
            isShowing = false
        }
    }

    /** 最小化：切换到圆形图标 */
    fun minimize() {
        if (!isShowing || isMinimized) return
        val wm = windowManager ?: return
        isMinimized = true
        stopRefreshLoop()

        val ctx = RainyLLMApp.instance
        val density = ctx.resources.displayMetrics.density
        val dp36 = (36 * density).toInt()
        val dp8 = (8 * density).toInt()

        // 保存当前位置
        val savedX = layoutParams?.x ?: 0
        val savedY = layoutParams?.y ?: 0

        try { fullView?.let { wm.removeView(it) } } catch (_: Exception) {}

        val size = dp36 + dp8 * 2
        layoutParams?.width = size
        layoutParams?.height = size
        layoutParams?.x = savedX
        layoutParams?.y = savedY

        currentView = miniView
        setupDrag(miniView!!, layoutParams!!)
        try {
            wm.addView(miniView, layoutParams)
            // 最小化时不刷新
            Log.i(TAG, "→ 最小化")
        } catch (e: Exception) {
            Log.e(TAG, "最小化失败: ${e.message}", e)
            isMinimized = false; isShowing = false
        }
    }

    /** 还原：从圆形图标切回完整悬浮窗 */
    fun restore() {
        if (!isShowing || !isMinimized) return
        val wm = windowManager ?: return
        isMinimized = false

        val savedX = layoutParams?.x ?: 0
        val savedY = layoutParams?.y ?: 0

        try { miniView?.let { wm.removeView(it) } } catch (_: Exception) {}

        val ctx = RainyLLMApp.instance
        val density = ctx.resources.displayMetrics.density
        val dp8 = (8 * density).toInt()

        fullView!!.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val viewW = fullView!!.measuredWidth.coerceAtLeast((160 * density).toInt())
        val screenWidth = ctx.resources.displayMetrics.widthPixels

        layoutParams?.width = viewW
        layoutParams?.height = LinearLayout.LayoutParams.WRAP_CONTENT
        layoutParams?.x = savedX.coerceIn(0, (screenWidth - viewW).coerceAtLeast(0))
        layoutParams?.y = savedY

        currentView = fullView
        setupDrag(fullView!!, layoutParams!!)
        try {
            wm.addView(fullView, layoutParams)
            updateContent()
            startRefreshLoop()
            Log.i(TAG, "← 还原")
        } catch (e: Exception) {
            Log.e(TAG, "还原失败: ${e.message}", e)
            isMinimized = true; isShowing = false
        }
    }

    fun hide() {
        if (!isShowing) return
        stopRefreshLoop()
        try { currentView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        fullView = null; miniView = null; currentView = null
        statusText = null; modelText = null; infoText = null; statsText = null
        mainBtn = null; actionBtn = null
        isShowing = false; isMinimized = false; isStartingServer = false
        resetAllConfirmStates()
        Log.i(TAG, "悬浮窗已隐藏")
    }

    // ── 刷新循环 ────────────────────────────────────────

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isShowing && !isMinimized) { updateContent(); handler.postDelayed(this, REFRESH_MS) }
        }
    }

    private fun startRefreshLoop() {
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    private fun stopRefreshLoop() {
        handler.removeCallbacks(refreshRunnable)
    }

    private fun updateContent() {
        if (isMinimized) return
        val server = OpenAIServer.currentInstance
        val isRunning = server?.isServerRunning == true

        // 同步全局初始化/停止状态
        if (LlmServerService.isInitializing && !isRunning && !isStartingServer) {
            isStartingServer = true
            actionBtn?.apply { text = "启动中…"; setBackgroundColor(btnDisabledBg); isEnabled = false }
        }
        if (LlmServerService.isStopping && isRunning && !isStoppingServer) {
            isStoppingServer = true
            actionBtn?.apply { text = "停止中…"; setBackgroundColor(btnDisabledBg); isEnabled = false }
        }

        val ctx = RainyLLMApp.instance

        val modelId = try {
            runBlocking { AppPreferences(ctx).selectedModel.first() }
        } catch (_: Exception) { "gemma4-e2b" }

        val port = server?.serverPort?.toString() ?: try {
            runBlocking { AppPreferences(ctx).serverPort.first().toString() }
        } catch (_: Exception) { "8080" }

        if (isRunning) {
            if (isStartingServer && !LlmServerService.isInitializing) { isStartingServer = false; actionBtn?.isEnabled = true }
            if (isStoppingServer && !LlmServerService.isStopping) { isStoppingServer = false; actionBtn?.isEnabled = true }
            val stats = server!!.getStats()
            val uptimeSec = (System.currentTimeMillis() - stats.startTime) / 1000
            statusText?.apply {
                text = "🟢 运行中 | :$port"; setTextColor(statusRunningColor)
            }
            infoText?.text = "⏱ ${formatDuration(uptimeSec)}"
            val lastReqTime = server.getRequestLog().lastOrNull()?.timestamp
            statsText?.text = "最近: " + if (lastReqTime != null)
                timeFormatter.format(Date(lastReqTime)) else "暂无请求"
            if (!actionBtnPending && !isStartingServer && !isStoppingServer) {
                actionBtn?.apply { text = "停止服务"; setBackgroundColor(btnStopBg); isEnabled = true }
            }
            pendingWhat = "stop"
        } else {
            // isStartingServer 只在服务确实启动后才清除（不是这里！）
            if (isStoppingServer && !LlmServerService.isStopping) { isStoppingServer = false; actionBtn?.isEnabled = true }
            statusText?.apply {
                text = "🔴 已停止"; setTextColor(statusStoppedColor)
            }
            infoText?.text = "⏱ —"
            val lastRecord = StatsRepository.instance?.records?.value?.lastOrNull()
            statsText?.text = "最近: " + if (lastRecord != null)
                timeFormatter.format(Date(lastRecord.timestamp)) else "暂无统计"
            if (!actionBtnPending && !isStartingServer && !isStoppingServer) {
                actionBtn?.apply { text = "启动服务"; setBackgroundColor(btnStartBg); isEnabled = true }
            }
            pendingWhat = "start"
        }
        modelText?.text = "模型: $modelId"
    }

    // ── 按钮事件 ────────────────────────────────────────

    private fun onMainButtonClick() {
        if (mainBtnPending) {
            resetAllConfirmStates()
            RainyLLMApp.instance.startActivity(
                Intent(RainyLLMApp.instance, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
        } else {
            mainBtnPending = true
            mainBtn?.apply { text = "再点确认"; setBackgroundColor(btnConfirmBg) }
            handler.removeCallbacks(confirmResetRunnable)
            handler.postDelayed(confirmResetRunnable, DOUBLE_CONFIRM_TIMEOUT_MS)
        }
    }

    private fun onActionButtonClick() {
        if (isStartingServer || isStoppingServer) return
        if (actionBtnPending) {
            resetAllConfirmStates()
            // 二次确认后检查：状态是否已被主界面等操作改变？
            val isRunning = OpenAIServer.currentInstance?.isServerRunning == true
            if (pendingWhat == "start" && isRunning) {
                // 服务已在运行（被主界面启动），刷新即可
                handler.post { updateContent() }
                return
            }
            if (pendingWhat == "stop" && !isRunning) {
                // 服务已停止（被主界面停止），刷新即可
                handler.post { updateContent() }
                return
            }
            when (pendingWhat) { "start" -> startServer(); "stop" -> stopServer() }
        } else {
            actionBtnPending = true
            actionBtn?.apply { text = "再点确认"; setBackgroundColor(btnConfirmBg) }
            handler.removeCallbacks(confirmResetRunnable)
            handler.postDelayed(confirmResetRunnable, DOUBLE_CONFIRM_TIMEOUT_MS)
        }
    }

    private fun startServer() {
        val ctx = RainyLLMApp.instance
        isStartingServer = true
        actionBtn?.apply { text = "启动中…"; setBackgroundColor(btnDisabledBg); isEnabled = false }
        actionBtnPending = false
        handler.removeCallbacks(confirmResetRunnable)

        val prefs = AppPreferences(ctx)
        val modelId: String; val port: Int
        try {
            modelId = runBlocking { prefs.selectedModel.first() }
            port = runBlocking { prefs.serverPort.first() }
        } catch (e: Exception) {
            isStartingServer = false; actionBtn?.isEnabled = true
            handler.post { updateContent() }; return
        }
        val repo = ModelRepository(RainyLLMApp.instance.modelsDir)
        val modelFile = repo.findModelFile(modelId)
            ?: java.io.File("${RainyLLMApp.instance.modelsDir}/${modelId}.litertlm").takeIf { it.exists() }
            ?: repo.scanDownloadedModels().firstOrNull()?.file
            ?: java.io.File("${RainyLLMApp.instance.modelsDir}/gemma4-e2b.litertlm")
        ctx.startForegroundService(Intent(ctx, LlmServerService::class.java).apply {
            action = LlmServerService.ACTION_START_SERVER
            putExtra(LlmServerService.EXTRA_MODEL_PATH, modelFile.absolutePath)
            putExtra(LlmServerService.EXTRA_CACHE_DIR, ctx.cacheDir.path)
            putExtra(LlmServerService.EXTRA_PORT, port)
            putExtra(LlmServerService.EXTRA_MODEL_ID, modelId)
        })
        handler.post { updateContent() }
    }

    private fun stopServer() {
        isStoppingServer = true
        actionBtn?.apply { text = "停止中…"; setBackgroundColor(btnDisabledBg); isEnabled = false }
        actionBtnPending = false
        handler.removeCallbacks(confirmResetRunnable)
        RainyLLMApp.instance.startService(
            Intent(RainyLLMApp.instance, LlmServerService::class.java).apply {
                action = LlmServerService.ACTION_STOP_SERVER
            })
        handler.post { updateContent() }
    }

    private fun resetAllConfirmStates() {
        mainBtnPending = false; actionBtnPending = false
        handler.removeCallbacks(confirmResetRunnable)
        val running = OpenAIServer.currentInstance?.isServerRunning == true
        mainBtn?.apply { text = "主界面"; setBackgroundColor(btnNormalBg) }
        if (!isStartingServer && !isStoppingServer) {
            actionBtn?.apply {
                text = if (running) "停止服务" else "启动服务"
                setBackgroundColor(if (running) btnStopBg else btnStartBg)
                isEnabled = true
            }
        }
    }

    // ── 拖动 ────────────────────────────────────────────

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var ix = 0; var iy = 0
        var tx = 0f; var ty = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = params.x; iy = params.y
                    tx = event.rawX; ty = event.rawY
                    dragging = false; false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - tx; val dy = event.rawY - ty
                    if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop))
                        dragging = true
                    if (dragging) {
                        params.x = ix + dx.toInt(); params.y = iy + dy.toInt()
                        try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) { dragging = false; true } else false
                }
                else -> false
            }
        }
    }

    // ── 格式化 ──────────────────────────────────────────

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }
}