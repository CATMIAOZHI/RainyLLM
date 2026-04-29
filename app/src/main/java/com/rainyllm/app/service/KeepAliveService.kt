package com.rainyllm.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.rainyllm.app.MainActivity
import com.rainyllm.app.server.OpenAIServer
import kotlinx.coroutines.*

/**
 * 保活通知服务 —— 常驻通知栏，显示服务器状态
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "rainyllm_keepalive"
        private const val CHANNEL_NAME = "RainyLLM 保活"
        private const val UPDATE_INTERVAL_MS = 5000L
    }

    private var updateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("🐱☁️ 雨晴LLM")
        startForeground(NOTIFICATION_ID, notification)
        startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        updateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (isActive) {
                update()
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun update() {
        val server = OpenAIServer.currentInstance
        val text = if (server?.isServerRunning == true) {
            "🐱 http://127.0.0.1:${server.serverPort}"
        } else {
            "🐱☁️ 雨晴LLM"
        }
        val notification = buildNotification(text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply { description = "RainyLLM 后台保活通知" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("RainyLLM")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("RainyLLM")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }
}