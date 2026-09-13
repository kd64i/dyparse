package com.jn.dyparse

import com.jn.dyparse.data.GalleryMedia
import com.jn.dyparse.data.ParseResult
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 服务器解析 API 客户端：App 只通过本类调用服务器解析，本地不再直连抖音解析。
 *
 * 鉴权：X-Token（与 server/config.php 的 API_TOKEN 一致）
 *
 * 服务器地址 / token / HMAC 密钥不再硬编码在源码里，而是在构建时通过
 * local.properties（或环境变量 / gradle 属性）注入到 BuildConfig，
 * 详见 README 的「配置服务器」章节。
 */
object ServerApiClient {
    private const val TAG = "ServerApiClient"

    // 构建时注入，见 app/build.gradle.kts 的 buildConfigField
    private val API_BASE = BuildConfig.SERVER_API_BASE
    private val TOKEN = BuildConfig.SERVER_API_TOKEN
    private val HMAC_KEY = BuildConfig.SERVER_HMAC_KEY

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 解析单个作品（单条解析/批量/作者主页的作品解析/保存都走这里）。
     * @param input 分享链接或作品 ID
     * @param useCookie true=服务器用其 cookie 解析（批量/保存原画质/最高画质）；false=匿名解析（单条）
     * @param original 是否请求原画质地址（保存时用，配合 useCookie=true，ratio=default）
     * @param highest 是否请求最高画质（保存时用，优先级高于 original，服务器自动判断）
     * @param batchId 批量解析时传入 batchId（供结果标记）
     */
    suspend fun parse(
        input: String,
        useCookie: Boolean = false,
        original: Boolean = false,
        highest: Boolean = false,
        batchId: String? = null
    ): ParseResult = withContext(Dispatchers.IO) {
        try {
            // 直接拼接查询串，避免 HttpUrl 解析 base 复杂化
            val encodedInput = java.net.URLEncoder.encode(input, "UTF-8")
            val modeParam = if (useCookie) "&mode=cookie" else ""
            val originalParam = if (original) "&original=1" else ""
            val highestParam = if (highest) "&highest=1" else ""
            val fullUrl = API_BASE + "?url=" + encodedInput + modeParam + originalParam + highestParam

            val timeMs = System.currentTimeMillis().toString()
            // 签名串 = token + time + 原始编码url + mode + original + highest后缀（与服务器一致）
            val signPayload = TOKEN + timeMs + encodedInput + modeParam + originalParam + highestParam
            val sign = hmacSha256(signPayload, HMAC_KEY)

            val request = Request.Builder()
                .url(fullUrl)
                .header("X-Token", TOKEN)
                .header("X-Time", timeMs)
                .header("X-Sign", sign)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // 服务器返回错误：尝试解析 JSON 里的 error 字段
                    val err = runCatching { gson.fromJson(body, ServerError::class.java) }.getOrNull()
                    val detail = err?.error?.takeIf { it.isNotBlank() }
                        ?: "HTTP ${response.code}"
                    return@withContext ParseResult.Error("解析失败($detail)")
                }
                if (body.isBlank()) {
                    return@withContext ParseResult.Error("解析失败(服务器返回空响应)")
                }
                // 用 JsonParser 手动解析，避免 Gson 对 Kotlin data class 的映射坑
                val result = parseServerResponse(body, input, batchId)
                return@withContext result
            }
        } catch (e: Exception) {
            ParseResult.Error("解析失败(${e.javaClass.simpleName}: ${e.message})")
        }
    }

    /** 手动解析服务器 JSON（不依赖 Gson data class 反序列化） */
    private fun parseServerResponse(body: String, input: String, batchId: String?): ParseResult {
        val root = runCatching {
            com.google.gson.JsonParser.parseString(body).asJsonObject
        }.getOrNull()
            ?: return ParseResult.Error("解析失败(响应格式错误: ${body.take(80)})")

        val success = runCatching { root.get("success").asBoolean }.getOrDefault(false)
        if (!success) {
            val errMsg = runCatching { root.get("error").asString }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "未知错误(body=${body.take(80)})"
            return ParseResult.Error("解析失败($errMsg)")
        }

        fun str(key: String): String? = runCatching {
            root.get(key)?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()

        fun long(key: String): Long? = runCatching {
            root.get(key)?.takeIf { !it.isJsonNull }?.asLong
        }.getOrNull()

        fun double(key: String): Double? = runCatching {
            root.get(key)?.takeIf { !it.isJsonNull }?.asDouble
        }.getOrNull()

        val galleryMedia = runCatching {
            root.get("gallery_media")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { el ->
                val obj = el.asJsonObject
                GalleryMedia(
                    index = runCatching { obj.get("index").asInt }.getOrDefault(0),
                    imageUrl = runCatching {
                        obj.get("image_url")?.takeIf { !it.isJsonNull }?.asString
                    }.getOrNull(),
                    livePhotoRawUrl = runCatching {
                        obj.get("live_photo_raw_url")?.takeIf { !it.isJsonNull }?.asString
                    }.getOrNull()
                )
            }
        }.getOrNull()

        val images = runCatching {
            root.get("images")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { el ->
                if (el.isJsonNull) null else el.asString
            }
        }.getOrNull()

        return ParseResult.Success(
            author = str("author") ?: "未知作者",
            authorUid = str("author_uid"),
            authorSecUid = str("author_sec_uid"),
            title = str("title") ?: "无标题",
            type = str("type") ?: if (images.isNullOrEmpty()) "video" else "image",
            playUrl = str("play_url"),
            rawPlayUrl = str("raw_play_url"),
            images = images,
            galleryMedia = galleryMedia,
            timestamp = long("timestamp") ?: (System.currentTimeMillis() / 1000),
            videoId = str("video_id").orEmpty(),
            cover = str("cover"),
            inputUrl = input,
            resolvedUrl = str("resolved_url"),
            duration = double("duration") ?: 0.0,
            source = "server",
            batchId = batchId,
            originalPlayUrl = str("original_play_url")
        )
    }

    fun hmacSha256(data: String, key: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ========== 响应模型 ==========

    private data class ServerError(val error: String? = null)
}
