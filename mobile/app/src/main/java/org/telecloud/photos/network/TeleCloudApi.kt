package org.telecloud.photos.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface TeleCloudApi {

    @GET("api/system/status")
    suspend fun getSystemStatus(): Response<SystemStatusResponse>

    @FormUrlEncoded
    @POST("login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String
    ): Response<LoginResponse>

    @GET("api/upload/check/{task_id}")
    suspend fun checkUploadRanges(
        @Path("task_id") taskId: String
    ): Response<UploadCheckResponse>

    @Multipart
    @POST("api/upload")
    suspend fun uploadChunk(
        @Part("task_id") taskId: RequestBody,
        @Part("filename") filename: RequestBody,
        @Part("path") path: RequestBody,
        @Part("start_byte") startByte: RequestBody,
        @Part("end_byte") endByte: RequestBody,
        @Part("total_size") totalSize: RequestBody,
        @Part("overwrite") overwrite: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<UploadChunkResponse>

    @GET("api/files")
    suspend fun listFiles(
        @Query("path") path: String
    ): Response<FileListResponse>

    @GET("api/media")
    suspend fun getCloudMedia(
        @Query("limit") limit: Int,
        @Query("cursor") cursor: Long?
    ): Response<CloudMediaListResponse>
}

data class SystemStatusResponse(
    val running: Boolean,
    val ready: Boolean,
    val authorized: Boolean
)

data class LoginResponse(
    val status: String,
    val error: String? = null
)

data class RangeItem(
    val start_byte: Long,
    val end_byte: Long
)

data class UploadCheckResponse(
    val chunks: List<Int>,
    val ranges: List<RangeItem>?
)

data class UploadChunkResponse(
    val status: String,
    val error: String? = null
)

data class CloudFile(
    val id: Long,
    val filename: String,
    val path: String,
    val size: Long,
    val mime_type: String?,
    val is_folder: Boolean,
    val created_at: String? = null
)

data class FileListResponse(
    val files: List<CloudFile>?
)

data class CloudMediaItem(
    val cloudMediaId: Long,
    val fingerprint: String,
    val filename: String,
    val mimeType: String,
    val originalSize: Long,
    val thumbnailId: Long,
    val previewId: Long,
    val originalFileId: Long,
    val width: Int,
    val height: Int,
    val duration: Long,
    val dateCreated: String,
    val dateModified: String,
    val uploadedAt: String
)

data class CloudMediaListResponse(
    val media: List<CloudMediaItem>?,
    val nextCursor: Long?
)
