package org.telecloud.photos.ui

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telecloud.photos.data.AppDatabase
import org.telecloud.photos.data.BackupStatus
import org.telecloud.photos.data.MediaItem
import org.telecloud.photos.data.UploadTask
import org.telecloud.photos.data.CloudMedia
import org.telecloud.photos.network.NetworkClient
import org.telecloud.photos.uploader.UploadWorker
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class PhotosViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "PhotosViewModel"
    private val context = application.applicationContext
    private val db = AppDatabase.getDatabase(context)
    private val networkClient = NetworkClient(context)

    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems: StateFlow<List<MediaItem>> = _mediaItems.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _uploadTasks = MutableStateFlow<List<UploadTask>>(emptyList())
    val uploadTasks: StateFlow<List<UploadTask>> = _uploadTasks.asStateFlow()

    init {
        loadTasks()
    }

    fun loadTasks() {
        viewModelScope.launch {
            db.uploadTaskDao().getAllTasksFlow().collect { tasks ->
                _uploadTasks.value = tasks
                // Synchronize backup status with media items
                updateMediaItemsStatus(tasks)

                // If auto-backup is enabled, check if we need to scan & queue the next batch of 20
                if (networkClient.isAutoBackupEnabled()) {
                    val activeCount = tasks.count { it.uploadState == "QUEUED" || it.uploadState == "UPLOADING" }
                    if (activeCount == 0) {
                        val activeMediaIds = tasks
                            .filter { it.uploadState == "QUEUED" || it.uploadState == "UPLOADING" || it.uploadState == "COMPLETED" }
                            .map { it.mediaId }
                            .toSet()
                        val hasRemaining = _mediaItems.value.any { it.id !in activeMediaIds }
                        if (hasRemaining) {
                            triggerAutoSyncAll()
                        }
                    }
                }
            }
        }
    }

    private val _autoBackupEnabled = MutableStateFlow(networkClient.isAutoBackupEnabled())
    val autoBackupEnabled: StateFlow<Boolean> = _autoBackupEnabled.asStateFlow()

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            networkClient.setAutoBackupEnabled(enabled)
            _autoBackupEnabled.value = enabled
            if (enabled) {
                triggerAutoSyncAll()
            } else {
                cancelAutoSync()
            }
        }
    }

    private var isSyncing = false

    fun triggerAutoSyncAll() {
        if (isSyncing) return
        isSyncing = true
        viewModelScope.launch {
            try {
                val localItems = _mediaItems.value
            if (localItems.isEmpty()) return@launch
            
            // Get already queued or completed tasks to avoid duplicates
            val activeMediaIds = withContext(Dispatchers.IO) {
                db.uploadTaskDao().getAllTasks()
                    .filter { it.uploadState == "QUEUED" || it.uploadState == "UPLOADING" || it.uploadState == "COMPLETED" }
                    .map { it.mediaId }
                    .toSet()
            }

            // Filter items that are not in tasks list, and take a maximum of 20 items for this batch
            val pendingItems = localItems.filter { it.id !in activeMediaIds }.take(20)
            if (pendingItems.isEmpty()) {
                triggerWorkManagerBackup()
                return@launch
            }

            withContext(Dispatchers.IO) {
                pendingItems.forEach { item ->
                    val fingerprint = try {
                        calculateSha256(context, item.uri)
                    } catch (e: Exception) {
                        null
                    }
                    if (fingerprint != null) {
                        val taskId = UUID.randomUUID().toString()
                        val task = UploadTask(
                            id = taskId,
                            mediaId = item.id,
                            contentUri = item.contentUri,
                            filename = item.displayName,
                            totalSize = item.size,
                            fingerprint = fingerprint,
                            uploadState = "QUEUED"
                        )
                        db.uploadTaskDao().insertTask(task)
                    }
                }
            }
            triggerWorkManagerBackup()
        } finally {
            isSyncing = false
        }
    }
}

    fun cancelAutoSync() {
        viewModelScope.launch {
            WorkManager.getInstance(context).cancelUniqueWork("TeleCloudUploadJob")
            withContext(Dispatchers.IO) {
                val activeTasks = db.uploadTaskDao().getAllTasks()
                    .filter { it.uploadState == "QUEUED" || it.uploadState == "UPLOADING" }
                activeTasks.forEach { 
                    db.uploadTaskDao().deleteTask(it)
                }
            }
            loadTasks()
        }
    }

    fun clearAllTasks() {
        viewModelScope.launch {
            db.uploadTaskDao().clearAll()
        }
    }

    fun getNetworkClient(): NetworkClient = networkClient

    fun loginAndConnect(url: String, username: String, password: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Checking
            val client = NetworkClient(context)
            client.setServerUrl(url)
            val api = client.getApi()
            if (api == null) {
                _connectionState.value = ConnectionState.Failed("Invalid URL format")
                onComplete(false)
                return@launch
            }

            try {
                // Step 1: Test Connection status
                val response = api.getSystemStatus()
                if (!response.isSuccessful || response.body()?.running != true) {
                    _connectionState.value = ConnectionState.Failed("Server is unreachable or not ready")
                    onComplete(false)
                    return@launch
                }

                // Step 2: Login to server
                val loginResponse = api.login(username, password)
                if (loginResponse.isSuccessful && loginResponse.body()?.status == "success") {
                    _connectionState.value = ConnectionState.Connected
                    networkClient.setServerUrl(url) // Persist the successful URL
                    networkClient.saveCredentials(username, password) // Persist Credentials
                    onComplete(true)
                } else {
                    val errorDetail = loginResponse.body()?.error ?: "Invalid username or password"
                    _connectionState.value = ConnectionState.Failed(errorDetail)
                    onComplete(false)
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Failed(e.localizedMessage ?: "Connection failed")
                onComplete(false)
            }
        }
    }

    fun loadLocalMedia() {
        viewModelScope.launch {
            val localItems = queryMediaStore()
            // Step 1 & 2: Immediately load cached Room metadata and display
            val cachedCloud = withContext(Dispatchers.IO) {
                try {
                    db.cloudMediaDao().getAllCloudMedia()
                } catch (e: Exception) {
                    emptyList<CloudMedia>()
                }
            }
            val mergedItems = mergeLocalAndRoomCloud(localItems, cachedCloud)
            _mediaItems.value = mergedItems
            
            loadTasks() // Re-trigger task mapping
            if (networkClient.isAutoBackupEnabled()) {
                triggerAutoSyncAll()
            }

            // Step 4 & 5: Synchronize cloud metadata in background and update asynchronously
            syncCloudMediaInBackground(localItems)
        }
    }

    private fun mergeLocalAndRoomCloud(
        localItems: List<MediaItem>,
        roomCloudItems: List<CloudMedia>
    ): List<MediaItem> {
        val result = localItems.toMutableList()
        val localByName = localItems.associateBy { it.displayName.lowercase(java.util.Locale.ROOT) }

        roomCloudItems.forEach { cloudMedia ->
            if (cloudMedia.isFolder) return@forEach
            val key = cloudMedia.filename.lowercase(java.util.Locale.ROOT)
            val matchedLocal = localByName[key]
            Log.d("PhotosViewModel", "Matching cloud file: '$key' (Size: ${cloudMedia.size}), match found: ${matchedLocal != null} (Local name: '${matchedLocal?.displayName}', Local size: ${matchedLocal?.size})")
            if (matchedLocal != null) {
                val idx = result.indexOfFirst { it.id == matchedLocal.id }
                if (idx != -1) {
                    result[idx] = result[idx].copy(
                        cloudFileId = cloudMedia.id,
                        backupStatus = BackupStatus.BACKED_UP
                    )
                }
            } else {
                val mime = cloudMedia.mimeType ?: "image/*"
                val dateMod = parseRfc3339ToEpoch(cloudMedia.createdAt)
                result.add(
                    MediaItem(
                        id = -cloudMedia.id,
                        contentUri = "cloud://${cloudMedia.id}",
                        displayName = cloudMedia.filename,
                        relativePath = cloudMedia.path,
                        mimeType = mime,
                        size = cloudMedia.size,
                        dateModified = dateMod,
                        cloudFileId = cloudMedia.id,
                        backupStatus = BackupStatus.BACKED_UP
                    )
                )
            }
        }
        result.sortByDescending { it.dateModified }
        return result
    }

    private fun syncCloudMediaInBackground(localItems: List<MediaItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            val api = networkClient.getApi() ?: return@launch
            try {
                val allFetchedMedia = mutableListOf<org.telecloud.photos.network.CloudMediaItem>()
                var cursor: Long? = null
                var hasMore = true
                var iterations = 0

                while (hasMore && iterations < 100) {
                    val response = api.getCloudMedia(limit = 100, cursor = cursor)
                    if (response.isSuccessful) {
                        val body = response.body()
                        val mediaList = body?.media ?: emptyList()
                        allFetchedMedia.addAll(mediaList)
                        
                        val next = body?.nextCursor
                        if (next != null && next != cursor && mediaList.isNotEmpty()) {
                            cursor = next
                        } else {
                            hasMore = false
                        }
                    } else {
                        hasMore = false
                    }
                    iterations++
                }

                if (allFetchedMedia.isNotEmpty() || iterations > 0) {
                    val cloudEntities = allFetchedMedia.map { item ->
                        CloudMedia(
                            id = item.cloudMediaId,
                            filename = item.filename,
                            path = "/Backups",
                            size = item.originalSize,
                            mimeType = item.mimeType,
                            isFolder = false,
                            createdAt = item.dateCreated
                        )
                    }

                    val dao = db.cloudMediaDao()
                    dao.clearAll()
                    dao.insertAll(cloudEntities)

                    val updatedMerged = mergeLocalAndRoomCloud(localItems, cloudEntities)
                    withContext(Dispatchers.Main) {
                        _mediaItems.value = updatedMerged
                        loadTasks()
                    }
                }
            } catch (e: Exception) {
                Log.e("PhotosViewModel", "Error syncing cloud media", e)
            }
        }
    }

    private fun parseRfc3339ToEpoch(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                java.time.Instant.parse(dateStr).epochSecond
            } else {
                val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                format.parse(dateStr.replace("Z", ""))?.time?.div(1000) ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private suspend fun queryMediaStore(): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )

        // Query Images
        val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            imageUri,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(imageUri, id).toString()
                items.add(
                    MediaItem(
                        id = id,
                        contentUri = contentUri,
                        displayName = cursor.getString(nameCol) ?: "image_$id",
                        relativePath = cursor.getString(pathCol) ?: "",
                        mimeType = cursor.getString(mimeCol) ?: "image/*",
                        size = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol),
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol)
                    )
                )
            }
        }

        // Query Videos
        val videoProjection = projection + arrayOf(MediaStore.Video.VideoColumns.DURATION)
        val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            videoUri,
            videoProjection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(videoUri, id).toString()
                items.add(
                    MediaItem(
                        id = id,
                        contentUri = contentUri,
                        displayName = cursor.getString(nameCol) ?: "video_$id",
                        relativePath = cursor.getString(pathCol) ?: "",
                        mimeType = cursor.getString(mimeCol) ?: "video/*",
                        size = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol),
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol),
                        duration = cursor.getLong(durationCol)
                    )
                )
            }
        }

        // Sort both by date modified descending
        items.sortByDescending { it.dateModified }
        items
    }

    private fun updateMediaItemsStatus(tasks: List<UploadTask>) {
        val updated = _mediaItems.value.map { item ->
            val matchingTask = tasks.find { it.mediaId == item.id }
            if (matchingTask != null) {
                val status = when (matchingTask.uploadState) {
                    "QUEUED" -> BackupStatus.QUEUED
                    "UPLOADING" -> BackupStatus.UPLOADING
                    "COMPLETED" -> BackupStatus.BACKED_UP
                    "FAILED" -> BackupStatus.FAILED
                    else -> BackupStatus.NOT_BACKED_UP
                }
                item.copy(backupStatus = status)
            } else {
                item
            }
        }
        _mediaItems.value = updated
    }

    fun uploadItem(item: MediaItem) {
        uploadItems(listOf(item))
    }

    fun uploadItems(itemsToUpload: List<MediaItem>) {
        if (itemsToUpload.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                itemsToUpload.forEach { item ->
                    // Check if already queued/uploading/completed to avoid duplicates
                    val existing = db.uploadTaskDao().getTaskByMediaId(item.id)
                    if (existing != null && (
                        existing.uploadState == "QUEUED" ||
                        existing.uploadState == "UPLOADING" ||
                        existing.uploadState == "COMPLETED"
                    )) {
                        return@forEach
                    }

                    val fingerprint = try {
                        calculateSha256(context, item.uri)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to calculate SHA-256 for ${item.displayName}", e)
                        null
                    }
                    if (fingerprint != null) {
                        val taskId = UUID.randomUUID().toString()
                        val task = UploadTask(
                            id = taskId,
                            mediaId = item.id,
                            contentUri = item.contentUri,
                            filename = item.displayName,
                            totalSize = item.size,
                            fingerprint = fingerprint,
                            uploadState = "QUEUED"
                        )
                        db.uploadTaskDao().insertTask(task)
                    }
                }
            }
            triggerWorkManagerBackup()
        }
    }

    fun triggerWorkManagerBackup() {
        val wifiOnly = networkClient.isWifiOnly()
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val uploadWorkRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "TeleCloudUploadJob",
            ExistingWorkPolicy.KEEP,
            uploadWorkRequest
        )
    }

    private fun calculateSha256(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException()
        pfd.use { parcelFd ->
            val fis = FileInputStream(parcelFd.fileDescriptor)
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

sealed class ConnectionState {
    object Idle : ConnectionState()
    object Checking : ConnectionState()
    object Connected : ConnectionState()
    data class Failed(val error: String) : ConnectionState()
}
