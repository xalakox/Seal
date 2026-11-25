package com.junkfood.seal.util

import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

private const val TAG = "LinkShareUtil"

@Serializable
data class LinkShareResponse(
    val id: String,
    @SerialName("share_url") val shareUrl: String,
    @SerialName("media_url") val mediaUrl: String? = null,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    val title: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

object LinkShareUtil {
    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .readTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    fun normalizeServerUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
        return trimmed
    }

    fun parseUploadResponse(body: String): LinkShareResponse =
        json.decodeFromString(LinkShareResponse.serializer(), body)

    fun isConfigured(): Boolean {
        if (!LINKSHARE_ENABLED.getBoolean()) return false
        val serverUrl = normalizeServerUrl(LINKSHARE_SERVER_URL.getString()) ?: return false
        val token = LINKSHARE_API_TOKEN.getString().trim()
        return serverUrl.isNotEmpty() && token.isNotEmpty()
    }

    fun shouldAutoUpload(): Boolean = isConfigured() && LINKSHARE_AUTO_UPLOAD.getBoolean()

    fun getTtlDays(): Int = LINKSHARE_TTL_DAYS.getInt().coerceIn(1, 90)

    fun createShareTextIntent(shareUrl: String, title: String? = null): Intent {
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareUrl)
                if (!title.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_SUBJECT, title)
                }
            }
        return Intent.createChooser(sendIntent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun testConnection(serverUrlRaw: String, apiTokenRaw: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val baseUrl =
                    normalizeServerUrl(serverUrlRaw)
                        ?: throw IllegalArgumentException(
                            "Server URL must start with https:// or http://"
                        )
                val token = apiTokenRaw.trim()
                if (token.isEmpty()) {
                    throw IllegalArgumentException("API token is required")
                }

                val request =
                    Request.Builder()
                        .url("$baseUrl/api/shares?limit=1")
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string().orEmpty().take(200)
                        throw IOException("HTTP ${response.code}: ${errBody.ifBlank { response.message }}")
                    }
                }
            }
        }

    suspend fun uploadFile(
        filePath: String,
        title: String? = null,
        sourceUrl: String? = null,
        ttlDays: Int = getTtlDays(),
    ): Result<LinkShareResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val baseUrl =
                    normalizeServerUrl(LINKSHARE_SERVER_URL.getString())
                        ?: throw IllegalStateException("LinkShare server URL is not configured")
                val token = LINKSHARE_API_TOKEN.getString().trim()
                if (token.isEmpty()) {
                    throw IllegalStateException("LinkShare API token is not configured")
                }

                val file = File(filePath)
                if (!file.exists() || !file.isFile || file.length() == 0L) {
                    throw IOException("Downloaded file not found: $filePath")
                }

                val mediaType =
                    when (file.extension.lowercase()) {
                        "mp4",
                        "m4v" -> "video/mp4"
                        "webm" -> "video/webm"
                        "mkv" -> "video/x-matroska"
                        "mov" -> "video/quicktime"
                        else -> "application/octet-stream"
                    }.toMediaType()

                val multipartBuilder =
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
                        .addFormDataPart("ttl_days", ttlDays.coerceIn(1, 90).toString())

                if (!title.isNullOrBlank()) {
                    multipartBuilder.addFormDataPart("title", title.trim().take(200))
                }
                if (!sourceUrl.isNullOrBlank()) {
                    multipartBuilder.addFormDataPart("source_url", sourceUrl.trim())
                }

                val request =
                    Request.Builder()
                        .url("$baseUrl/api/shares/upload")
                        .header("Authorization", "Bearer $token")
                        .post(multipartBuilder.build())
                        .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException(
                            "Upload failed (HTTP ${response.code}): ${responseBody.take(300)}"
                        )
                    }
                    parseUploadResponse(responseBody)
                }
            }
        }

    suspend fun uploadAndNotify(
        context: Context,
        filePath: String,
        title: String?,
        sourceUrl: String?,
        notificationId: Int,
        openShareSheetImmediately: Boolean = false,
    ): Result<LinkShareResponse> {
        val displayTitle =
            title?.takeIf { it.isNotBlank() } ?: File(filePath).nameWithoutExtension.ifBlank {
                context.getString(R.string.linkshare)
            }
        val uploadNotificationId = notificationId xor 0x5A5A

        NotificationUtil.notifyLinkShareProgress(
            notificationId = uploadNotificationId,
            title = displayTitle,
            text = context.getString(R.string.linkshare_uploading),
        )

        val result = uploadFile(filePath = filePath, title = displayTitle, sourceUrl = sourceUrl)

        result
            .onSuccess { share ->
                Log.d(TAG, "Uploaded to LinkShare: ${share.shareUrl}")

                if (LINKSHARE_AUTO_COPY.getBoolean()) {
                    withContext(Dispatchers.Main) {
                        App.clipboard.setPrimaryClip(
                            ClipData.newPlainText("LinkShare URL", share.shareUrl)
                        )
                    }
                    ToastUtil.makeToastSuspend(context.getString(R.string.linkshare_link_copied))
                } else {
                    ToastUtil.makeToastSuspend(context.getString(R.string.linkshare_upload_success))
                }

                if (LINKSHARE_DELETE_LOCAL_AFTER_UPLOAD.getBoolean()) {
                    runCatching { File(filePath).delete() }
                }

                val shareIntent = createShareTextIntent(share.shareUrl, share.title ?: displayTitle)
                val sharePendingIntent =
                    PendingIntent.getActivity(
                        context,
                        uploadNotificationId,
                        shareIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )

                NotificationUtil.finishLinkShareNotification(
                    notificationId = uploadNotificationId,
                    title = share.title ?: displayTitle,
                    shareUrl = share.shareUrl,
                    shareIntent = sharePendingIntent,
                )

                if (openShareSheetImmediately) {
                    withContext(Dispatchers.Main) {
                        runCatching { context.startActivity(shareIntent) }
                    }
                }
            }
            .onFailure { throwable ->
                Log.e(TAG, "LinkShare upload failed", throwable)
                NotificationUtil.notifyError(
                    title = displayTitle,
                    textId = R.string.linkshare_upload_failed,
                    notificationId = uploadNotificationId,
                    report = throwable.stackTraceToString(),
                )
                ToastUtil.makeToastSuspend(context.getString(R.string.linkshare_upload_failed))
            }

        return result
    }
}
