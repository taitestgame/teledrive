package org.telecloud.photos.uploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.telecloud.photos.R
import org.telecloud.photos.data.AppDatabase
import org.telecloud.photos.data.UploadTask
import org.telecloud.photos.network.NetworkClient

class UploadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val tag = "UploadWorker"
    private val notificationId = 8812
    private val channelId = "telecloud_upload_channel"
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val db = AppDatabase.getDatabase(context)
    private val networkClient = NetworkClient(context)

    override suspend fun doWork(): Result {
        Log.d(tag, "Upload background worker started.")

        val api = networkClient.getApi()
        if (api == null) {
            Log.e(tag, "No API instance available. Server URL might not be configured.")
            return Result.failure()
        }

        val uploader = AdaptiveUploader(applicationContext, api)

        // Find active/failed tasks to upload
        val activeTasks = db.uploadTaskDao().getAllTasks()
            .filter { it.uploadState == "QUEUED" || it.uploadState == "FAILED" }

        if (activeTasks.isEmpty()) {
            Log.d(tag, "No pending upload tasks.")
            return Result.success()
        }

        // Display notification
        val initNotification = createProgressNotification(activeTasks.size, 0, "Initializing...")
        try {
            setForeground(ForegroundInfo(notificationId, initNotification))
        } catch (e: Exception) {
            Log.e(tag, "Failed to set foreground worker", e)
        }

        var completedCount = 0
        var hasFailures = false

        for (task in activeTasks) {
            Log.d(tag, "Processing background task: ${task.filename}")

            // Update state in Room to UPLOADING
            task.uploadState = "UPLOADING"
            db.uploadTaskDao().updateTask(task)

            // Trigger upload
            val success = uploader.uploadFile(
                taskId = task.id,
                uriString = task.contentUri,
                filename = task.filename,
                destPath = networkClient.getBackupFolder(),
                totalSize = task.totalSize,
                onProgress = { bytesUploaded ->
                    val progressPercent = ((bytesUploaded.toDouble() / task.totalSize.toDouble()) * 100).toInt()
                    updateProgressNotification(
                        totalFiles = activeTasks.size,
                        currentFileIndex = completedCount + 1,
                        fileName = task.filename,
                        progressPercent = progressPercent
                    )
                }
            )

            if (success) {
                task.uploadState = "COMPLETED"
                db.uploadTaskDao().updateTask(task)
                completedCount++
            } else {
                task.uploadState = "FAILED"
                task.retryCount++
                task.lastError = "Upload failed or was interrupted"
                db.uploadTaskDao().updateTask(task)
                hasFailures = true
            }
        }

        notificationManager.cancel(notificationId)
        Log.d(tag, "Background work completed. Successes: $completedCount, Failures: $hasFailures")
        return if (hasFailures) Result.retry() else Result.success()
    }

    private fun createProgressNotification(totalFiles: Int, currentIndex: Int, status: String): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "TeleCloud Uploads",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("TeleCloud Photos Backup")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }



    private fun updateProgressNotification(
        totalFiles: Int,
        currentFileIndex: Int,
        fileName: String,
        progressPercent: Int
    ) {
        val statusText = "Uploading file $currentFileIndex/$totalFiles: $fileName ($progressPercent%)"
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Backup in Progress")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, progressPercent, false)
            .setOngoing(true)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
