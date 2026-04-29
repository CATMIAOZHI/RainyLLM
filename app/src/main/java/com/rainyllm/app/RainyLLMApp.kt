package com.rainyllm.app

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.rainyllm.app.service.KeepAliveService
import kotlinx.coroutines.*

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
        // 延迟读取 DataStore 后启动（首次启动需等 DataStore 初始化）
        kotlinx.coroutines.MainScope().launch {
            kotlinx.coroutines.delay(500L)
            val prefs = com.rainyllm.app.data.AppPreferences(this@RainyLLMApp)
            prefs.keepAlive.collect { enabled ->
                if (enabled) {
                    val intent = Intent(this@RainyLLMApp, KeepAliveService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
                // 只执行一次
                throw kotlinx.coroutines.CancellationException()
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