package org.telecloud.photos.uploader

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.telecloud.photos.network.RangeItem
import org.telecloud.photos.network.TeleCloudApi
import java.io.FileInputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class AdaptiveUploader(
    private val context: Context,
    private val api: TeleCloudApi
) {
    private val tag = "AdaptiveUploader"

    // EWMA factors
    private var ewmaSpeed: Double = 2.0 * 1024 * 1024 // Initial guess: 2 MB/s
    private val alpha = 0.2 // Smoothing factor
    private var currentChunkSize = 2 * 1024 * 1024L // Start with 2MB chunks
    private val minChunkSize = 512 * 1024L // 512 KB
    private val maxChunkSize = 50 * 1024 * 1024L // 50 MB

    // Represents a byte range [start, end)
    data class ByteRange(val start: Long, val end: Long)

    suspend fun uploadFile(
        taskId: String,
        uriString: String,
        filename: String,
        destPath: String,
        totalSize: Long,
        onProgress: (bytesUploaded: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            Log.d(tag, "Starting upload for $filename (Size: $totalSize, Task: $taskId)")

            // Step 1: Query backend for already uploaded ranges
            val confirmedRanges = getConfirmedRanges(taskId)
            Log.d(tag, "Confirmed ranges for $taskId: $confirmedRanges")

            // Step 2: Compute missing ranges
            val missingRanges = calculateMissingRanges(totalSize, confirmedRanges)
            Log.d(tag, "Missing ranges to upload: $missingRanges")

            if (missingRanges.isEmpty()) {
                Log.d(tag, "File is already fully uploaded.")
                return@withContext true
            }

            // Open AssetFileDescriptor / ParcelFileDescriptor for content URI
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                Log.e(tag, "Failed to open content URI $uriString", e)
                return@withContext false
            } ?: return@withContext false

            pfd.use { parcelFd ->
                val fileDescriptor = parcelFd.fileDescriptor
                val fis = FileInputStream(fileDescriptor)
                val channel = fis.channel

                var totalBytesUploaded = confirmedRanges.sumOf { it.end - it.start }
                UploadProgressTracker.updateProgress(taskId, totalBytesUploaded, totalSize, 0.0, 0L)
                onProgress(totalBytesUploaded)

                for (range in missingRanges) {
                    var rangePosition = range.start

                    while (rangePosition < range.end) {
                        val bytesRemainingInRange = range.end - rangePosition
                        val uploadSize = min(currentChunkSize, bytesRemainingInRange)

                        Log.d(tag, "Uploading chunk: [$rangePosition, ${rangePosition + uploadSize}) of size $uploadSize")

                        // Read bytes seekable directly from channel
                        val chunkBuffer = ByteArray(uploadSize.toInt())
                        channel.position(rangePosition)
                        var bytesRead = 0
                        while (bytesRead < uploadSize) {
                            val read = fis.read(chunkBuffer, bytesRead, (uploadSize - bytesRead).toInt())
                            if (read == -1) break
                            bytesRead += read
                        }

                        if (bytesRead <= 0) {
                            Log.e(tag, "Read 0 or negative bytes from content URI channel")
                            return@withContext false
                        }

                        // Prepare Multipart request body
                        val requestBody = chunkBuffer.sliceArray(0 until bytesRead)
                            .toRequestBody("application/octet-stream".toMediaTypeOrNull())
                        val filePart = MultipartBody.Part.createFormData("file", filename, requestBody)

                        val startTime = SystemClock.elapsedRealtime()

                        val response = try {
                            api.uploadChunk(
                                taskId = taskId.toRequestBody("text/plain".toMediaTypeOrNull()),
                                filename = filename.toRequestBody("text/plain".toMediaTypeOrNull()),
                                path = destPath.toRequestBody("text/plain".toMediaTypeOrNull()),
                                startByte = rangePosition.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                                endByte = (rangePosition + bytesRead).toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                                totalSize = totalSize.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                                overwrite = "false".toRequestBody("text/plain".toMediaTypeOrNull()),
                                file = filePart
                            )
                        } catch (e: Exception) {
                            Log.e(tag, "Network error uploading chunk at $rangePosition", e)
                            // Reduce chunk size on failure
                            currentChunkSize = max(minChunkSize, currentChunkSize / 2)
                            return@withContext false
                        }

                        if (!response.isSuccessful || response.body()?.status != "range_received" && response.body()?.status != "processing_telegram") {
                            val errorMsg = response.errorBody()?.string() ?: response.body()?.error
                            Log.e(tag, "Server rejected chunk upload at $rangePosition: $errorMsg")
                            currentChunkSize = max(minChunkSize, currentChunkSize / 2)
                            return@withContext false
                        }

                        val duration = max(1L, SystemClock.elapsedRealtime() - startTime)
                        val speed = (bytesRead.toDouble() / (duration.toDouble() / 1000.0)) // Bytes per second
                        updateAdaptiveChunkSize(speed)

                        rangePosition += bytesRead
                        totalBytesUploaded += bytesRead

                        val eta = if (ewmaSpeed > 0) ((totalSize - totalBytesUploaded) / ewmaSpeed).toLong() else 0L
                        UploadProgressTracker.updateProgress(taskId, totalBytesUploaded, totalSize, ewmaSpeed, eta)

                        onProgress(totalBytesUploaded)
                    }
                }
            }

            Log.d(tag, "Upload task $taskId finished successfully")
            return@withContext true
        } finally {
            UploadProgressTracker.removeProgress(taskId)
        }
    }

    private suspend fun getConfirmedRanges(taskId: String): List<ByteRange> {
        return try {
            val response = api.checkUploadRanges(taskId)
            if (response.isSuccessful) {
                response.body()?.ranges?.map { ByteRange(it.start_byte, it.end_byte) } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun calculateMissingRanges(totalSize: Long, confirmedRanges: List<ByteRange>): List<ByteRange> {
        if (confirmedRanges.isEmpty()) {
            return listOf(ByteRange(0, totalSize))
        }

        // Sort ranges
        val sorted = confirmedRanges.sortedBy { it.start }
        val missing = mutableListOf<ByteRange>()

        var currentPos = 0L
        for (range in sorted) {
            if (range.start > currentPos) {
                missing.add(ByteRange(currentPos, range.start))
            }
            currentPos = max(currentPos, range.end)
        }

        if (currentPos < totalSize) {
            missing.add(ByteRange(currentPos, totalSize))
        }

        return missing
    }

    private fun updateAdaptiveChunkSize(speedBytesPerSec: Double) {
        ewmaSpeed = (1 - alpha) * ewmaSpeed + alpha * speedBytesPerSec
        // Aim for a target chunk upload time of ~1.5 seconds
        val targetChunkDurationSec = 1.5
        val recommendedSize = (ewmaSpeed * targetChunkDurationSec).toLong()

        // Clamp to min and max boundaries
        currentChunkSize = max(minChunkSize, min(maxChunkSize, recommendedSize))
        Log.d(tag, "Speed EWMA: ${ewmaSpeed / (1024*1024)} MB/s, new chunk size: ${currentChunkSize / (1024*1024)} MB")
    }
}
