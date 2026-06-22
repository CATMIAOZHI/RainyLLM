package com.rainyllm.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.rainyllm.app.RainyLLMApp
import com.rainyllm.app.data.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 通知栏按钮动作接收器
 * 处理「开关悬浮窗」和「退出进程」两个 Action。
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifyAction"
        const val ACTION_TOGGLE_FLOATING = "com.rainyllm.app.TOGGLE_FLOATING"
        const val ACTION_EXIT_APP = "com.rainyllm.app.EXIT_APP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_FLOATING -> toggleFloating(context)
            ACTION_EXIT_APP -> exitApp(context)
        }
    }

    private fun toggleFloating(context: Context) {
        val app = context.applicationContext as? RainyLLMApp ?: return
        val prefs = AppPreferences(app)
        val current = try {
            runBlocking { prefs.floatingWindowEnabled.first() }
        } catch (_: Exception) { true }
        val newVal = !current
        runBlocking { prefs.setFloatingWindow(newVal) }
        app.syncFloatingWindow(newVal)
        Log.i(TAG, "悬浮窗: ${if (newVal) "开启" else "关闭"}")
    }

    private fun exitApp(context: Context) {
        Log.i(TAG, "退出进程")
        // 停止所有服务
        context.stopService(Intent(context, LlmServerService::class.java))
        context.stopService(Intent(context, KeepAliveService::class.java))
        // 延迟一下确保服务停止，然后杀进程
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
        }, 500L)
    }
}