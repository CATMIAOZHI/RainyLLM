package com.rainyllm.app

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.rainyllm.app.service.KeepAliveService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * RainyLLM Application 类
 * 应用级初始化
 */
class RainyLLMApp : Application() {

    companion object {
        private const val TAG = "RainyLLM"
        lateinit var instance: RainyLLMApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "🐱☁️ RainyLLM 应用启动")
        startKeepAliveIfNeeded()
    }

    /** 根据 DataStore 设置启动/停止保活服务 */
    fun syncKeepAlive(enabled: Boolean) {
        if (enabled) {
            val intent = Intent(this, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            stopService(Intent(this, KeepAliveService::class.java))
        }
    }

    private fun startKeepAliveIfNeeded() {
        // 使用 GlobalScope 一次性读取（应用级协程，无生命周期绑定）
        // 修复：使用 first() 替代 collect + 异常中断，避免资源泄漏
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(500L)
            try {
                val prefs = com.rainyllm.app.data.AppPreferences(this@RainyLLMApp)
                val enabled = prefs.keepAlive.first()
                if (enabled) {
                    val intent = Intent(this@RainyLLMApp, KeepAliveService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            } catch (_: Exception) {
                // DataStore 可能尚未初始化，静默失败
            }
        }
    }

    /** 模型存储目录（外部应用专属目录，DownloadManager 可直接写入） */
    val modelsDir: java.io.File
        get() {
            val externalDir = getExternalFilesDir(null)
                ?: filesDir
            return java.io.File(externalDir, "models").also { it.mkdirs() }
        }
}