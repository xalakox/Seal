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
import com.tencent.mmkv.MMKV
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

private const val TAG = "LinkShareUtil"
private const val CACHE_KEY_URL_PREFIX = "linkshare_cache_url:"
private const val CACHE_KEY_PATH_PREFIX = "linkshare_cache_path:"

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
    private val kv: MMKV by lazy { MMKV.defaultMMKV() }

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

    fun canonicalizeSourceUrl(rawUrl: String?): String? {
        val cleaned = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withoutHash = cleaned.substringBefore("#")
        val schemeSplit = withoutHash.split("://", limit = 2)
        if (schemeSplit.size != 2) return cleaned
        val scheme = schemeSplit[0].lowercase()
        if (scheme != "http" && scheme != "https") return cleaned
        val rest = schemeSplit[1]
        val hostPart = rest.substringBefore("/").substringBefore("?").lowercase().removePrefix("www.")
        val afterHost = rest.substringAfter("/", "")
        val pathPart = "/" + afterHost.substringBefore("?").trimEnd('/')
        val igHosts = setOf("instagram.com", "m.instagram.com", "ddinstagram.com", "instagr.am")
        if (hostPart in igHosts) {
            val segments = pathPart.split("/").filter { it.isNotEmpty() }
            if (segments.size >= 2 && segments[0] in setOf("reel", "reels", "p", "tv")) {
                val kind = if (segments[0] == "reels") "reel" else segments[0]
                return "https://instagram.com/$kind/${segments[1]}"
            }
        }
        return "$scheme://$hostPart$pathPart"
    }

    fun parseUploadResponse(body: String): LinkShareResponse =
        json.decodeFromString(LinkShareResponse.serializer(), body)

    fun isNotExpired(expiresAt: String?, nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        if (expiresAt.isNullOrBlank()) return true
        return runCatching { Instant.parse(expiresAt).toEpochMilli() > nowEpochMillis + 60_000L }
            .getOrDefault(true)
    }

    private fun cacheKeys(sourceUrl: String?, filePath: String?): List<String> = buildList {
        canonicalizeSourceUrl(sourceUrl)?.let { add(CACHE_KEY_URL_PREFIX + it) }
        sourceUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { add(CACHE_KEY_URL_PREFIX + it) }
        filePath?.trim()?.takeIf { it.isNotEmpty() }?.let { add(CACHE_KEY_PATH_PREFIX + it) }
    }

    fun getCachedShare(sourceUrl: String?, filePath: String?): LinkShareResponse? {
        for (key in cacheKeys(sourceUrl, filePath)) {
            val raw = kv.decodeString(key) ?: continue
            val parsed = runCatching { parseUploadResponse(raw) }.getOrNull() ?: continue
            if (isNotExpired(parsed.expiresAt)) {
                return parsed
            }
            kv.removeValueForKey(key)
        }
        return null
    }

    fun saveCachedShare(sourceUrl: String?, filePath: String?, response: LinkShareResponse) {
        val encoded = runCatching { json.encodeToString(response) }.getOrNull() ?: return
        for (key in cacheKeys(sourceUrl, filePath)) {
            kv.encode(key, encoded)
        }
    }

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

    suspend fun lookupExistingBySourceUrl(sourceUrl: String?): LinkShareResponse? =
        withContext(Dispatchers.IO) {
            val cleaned = sourceUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
            val baseUrl = normalizeServerUrl(LINKSHARE_SERVER_URL.getString()) ?: return@withContext null
            val token = LINKSHARE_API_TOKEN.getString().trim()
            if (token.isEmpty()) return@withContext null

            runCatching {
                    val encoded = URLEncoder.encode(cleaned, "UTF-8")
                    val request =
                        Request.Builder()
                            .url("$baseUrl/api/shares/lookup?source_url=$encoded")
                            .header("Authorization", "Bearer $token")
                            .get()
                            .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val body = response.body?.string().orEmpty()
                        if (body.isBlank()) return@use null
                        parseUploadResponse(body)
                    }
                }
                .getOrNull()
        }

    suspend fun verifyShareStillActive(shareId: String): LinkShareResponse? =
        withContext(Dispatchers.IO) {
            val baseUrl = normalizeServerUrl(LINKSHARE_SERVER_URL.getString()) ?: return@withContext null
            val token = LINKSHARE_API_TOKEN.getString().trim()
            if (token.isEmpty()) return@withContext null
            runCatching {
                    val request =
                        Request.Builder()
                            .url("$baseUrl/api/shares/$shareId")
                            .header("Authorization", "Bearer $token")
                            .get()
                            .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val body = response.body?.string().orEmpty()
                        if (body.isBlank()) return@use null
                        parseUploadResponse(body)
                    }
                }
                .getOrNull()
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

    private suspend fun findExistingShare(
        sourceUrl: String?,
        filePath: String?,
    ): LinkShareResponse? {
        val cached = getCachedShare(sourceUrl, filePath)
        if (cached != null) {
            val verified = verifyShareStillActive(cached.id)
            if (verified != null) {
                saveCachedShare(sourceUrl, filePath, verified)
                return verified
            }
        }
        val fromServer = lookupExistingBySourceUrl(sourceUrl)
        if (fromServer != null) {
            saveCachedShare(sourceUrl, filePath, fromServer)
            return fromServer
        }
        return null
    }

    suspend fun uploadAndNotify(
        context: Context,
        filePath: String?,
        title: String?,
        sourceUrl: String?,
        notificationId: Int,
        openShareSheetImmediately: Boolean = false,
    ): Result<LinkShareResponse> {
        val displayTitle =
            title?.takeIf { it.isNotBlank() }
                ?: filePath?.let { File(it).nameWithoutExtension.takeIf { n -> n.isNotBlank() } }
                ?: context.getString(R.string.linkshare)
        val uploadNotificationId = notificationId xor 0x5A5A

        // 1. Reuse existing active LinkShare link if already uploaded (no re-upload!)
        val existing = findExistingShare(sourceUrl = sourceUrl, filePath = filePath)
        val result: Result<LinkShareResponse> =
            if (existing != null) {
                Log.d(TAG, "Reusing existing LinkShare URL: ${existing.shareUrl}")
                Result.success(existing)
            } else if (filePath.isNullOrBlank() || !File(filePath).exists()) {
                Result.failure(IOException("No active LinkShare link found and local file is missing"))
            } else {
                NotificationUtil.notifyLinkShareProgress(
                    notificationId = uploadNotificationId,
                    title = displayTitle,
                    text = context.getString(R.string.linkshare_uploading),
                )
                uploadFile(filePath = filePath, title = displayTitle, sourceUrl = sourceUrl)
                    .onSuccess { created ->
                        saveCachedShare(sourceUrl, filePath, created)
                        if (LINKSHARE_DELETE_LOCAL_AFTER_UPLOAD.getBoolean()) {
                            runCatching { File(filePath).delete() }
                        }
                    }
            }

        result
            .onSuccess { share ->
                Log.d(TAG, "LinkShare URL ready: ${share.shareUrl}")

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
                Log.e(TAG, "LinkShare lookup/upload failed", throwable)
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
