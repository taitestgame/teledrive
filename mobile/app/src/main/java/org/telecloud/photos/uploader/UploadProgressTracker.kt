package org.telecloud.photos.uploader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UploadProgressInfo(
    val taskId: String,
    val bytesUploaded: Long,
    val totalSize: Long,
    val speedBytesPerSec: Double,
    val etaSeconds: Long
)

object UploadProgressTracker {
    private val _progressMap = MutableStateFlow<Map<String, UploadProgressInfo>>(emptyMap())
    val progressMap: StateFlow<Map<String, UploadProgressInfo>> = _progressMap.asStateFlow()

    fun updateProgress(
        taskId: String,
        bytesUploaded: Long,
        totalSize: Long,
        speedBytesPerSec: Double,
        etaSeconds: Long
    ) {
        val current = _progressMap.value.toMutableMap()
        current[taskId] = UploadProgressInfo(taskId, bytesUploaded, totalSize, speedBytesPerSec, etaSeconds)
        _progressMap.value = current
    }

    fun removeProgress(taskId: String) {
        val current = _progressMap.value.toMutableMap()
        if (current.remove(taskId) != null) {
            _progressMap.value = current
        }
    }

    fun clearAll() {
        _progressMap.value = emptyMap()
    }
}
