package com.rainyllm.app.model

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

/**
 * 模型下载管理器
 * 使用 Android DownloadManager 实现断点续传下载
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
    }

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /**
     * 开始下载模型
     * @param modelInfo 模型信息
     * @param destination 目标文件
     * @param downloadUrl 指定下载地址，不传则使用 modelInfo.url
     * @return 下载任务 ID
     */
    fun startDownload(modelInfo: ModelInfo, destination: File, downloadUrl: String = modelInfo.url): Long {
        val parentDir = destination.parentFile
        if (parentDir == null) {
            Log.e(TAG, "目标路径无效: $destination")
            return -1L
        }
        parentDir.mkdirs()

        if (destination.exists()) destination.delete()

        val fileName = destination.name
        val subPath = if (parentDir.name == "models") "models/$fileName" else fileName
        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setTitle("下载 ${modelInfo.name}")
            setDescription("${modelInfo.sizeGb} · RainyLLM")
            setDestinationInExternalFilesDir(context, null, subPath)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }

        val downloadId = downloadManager.enqueue(request)
        Log.i(TAG, "开始下载: ${modelInfo.name} (ID: $downloadId) → ${parentDir.name}/$fileName")
        return downloadId
    }

    /**
     * 查询下载进度
     * @return 进度百分比 (0-100)，失败返回 -1
     */
    fun queryProgress(downloadId: Long): Int {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)

        cursor.use {
            if (it.moveToFirst()) {
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> return 100
                    DownloadManager.STATUS_FAILED -> return -1
                    DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING -> {
                        val bytesDownloaded = it.getLong(
                            it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        )
                        val totalBytes = it.getLong(
                            it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        )
                        if (totalBytes > 0) {
                            return ((bytesDownloaded * 100) / totalBytes).toInt()
                        }
                    }
                }
            }
        }
        return 0
    }

    /**
     * 获取已下载文件的实际本地 URI
     */
    fun getDownloadedFileUri(downloadId: Long): Uri? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        cursor.use {
            if (it.moveToFirst()) {
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val uriStr = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    if (uriStr != null) return Uri.parse(uriStr)
                }
            }
        }
        return null
    }

    /**
     * 下载完成后规范化文件名（处理 Content-Disposition 覆盖问题）
     * @return 重命名后（如有需要）的目标文件
     */
    fun normalizeDownloadedFile(downloadId: Long, expectedFile: File): File? {
        val uri = getDownloadedFileUri(downloadId) ?: return null
        val downloadedFile = File(uri.path ?: return null)
        if (!downloadedFile.exists()) return null

        // 如果实际文件名与期望一致，直接返回
        if (downloadedFile.absolutePath == expectedFile.absolutePath) return downloadedFile
        if (downloadedFile.name == expectedFile.name) return downloadedFile

        // 文件名不一致（Content-Disposition 覆盖），重命名为期望文件名
        Log.w(TAG, "文件名不匹配，重命名: ${downloadedFile.name} → ${expectedFile.name}")
        return try {
            if (expectedFile.exists()) expectedFile.delete()
            downloadedFile.renameTo(expectedFile)
            if (expectedFile.exists()) expectedFile else downloadedFile
        } catch (e: Exception) {
            Log.e(TAG, "重命名失败: ${e.message}", e)
            downloadedFile  // 保留原始文件
        }
    }

    /**
     * 监听下载完成事件
     */
    fun onDownloadComplete(): Flow<Long> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (id != -1L) trySend(id)
            }
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_EXPORTED
        } else {
            0
        }
        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            flags
        )
        awaitClose { context.unregisterReceiver(receiver) }
    }

    /**
     * 移除下载任务（不删除文件）
     */
    fun removeDownload(downloadId: Long) {
        downloadManager.remove(downloadId)
    }

    /**
     * 检查存储空间是否充足
     * @return 可用空间字节数，不足返回 -1
     */
    fun checkStorageSpace(minimumBytes: Long): Long {
        val stat = android.os.StatFs(Environment.getDataDirectory().path)
        val availableBytes = stat.availableBytes
        return if (availableBytes >= minimumBytes) availableBytes else -1
    }
}