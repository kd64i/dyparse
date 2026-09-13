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

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 当前生效的服务器地址与密钥：App 内配置优先，其次才是构建时注入的默认值 */
    private fun config(): ServerConfigStore.Config = ServerConfigStore.getConfig()

    /** 未配置服务端时的统一提示（分发的 APK 默认是占位地址） */
    private const val NOT_CONFIGURED_HINT =
        "未配置服务器：请到「设置 → 服务器配置」填写你自己的服务端地址与密钥（部署方法见 README）"

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
        val cfg = config()
        if (ServerConfigStore.isPlaceholder(cfg.apiBase)) {
            return@withContext ParseResult.Error(NOT_CONFIGURED_HINT)
        }
        try {
            // 直接拼接查询串，避免 HttpUrl 解析 base 复杂化
            val encodedInput = java.net.URLEncoder.encode(input, "UTF-8")
            val modeParam = if (useCookie) "&mode=cookie" else ""
            val originalParam = if (original) "&original=1" else ""
            val highestParam = if (highest) "&highest=1" else ""
            val fullUrl = cfg.apiBase + "?url=" + encodedInput + modeParam + originalParam + highestParam

            val timeMs = System.currentTimeMillis().toString()
            // 签名串 = token + time + 原始编码url + mode + original + highest后缀（与服务器一致）
            val signPayload = cfg.token + timeMs + encodedInput + modeParam + originalParam + highestParam
            val sign = hmacSha256(signPayload, cfg.hmacKey)

            val request = Request.Builder()
                .url(fullUrl)
                .header("X-Token", cfg.token)
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

    /**
     * 测试服务器配置是否可用（供「设置 → 服务器配置」使用）。
     *
     * 做两件事：
     *  1. 访问 `data.php?diag=1`（服务端不鉴权）——确认地址可达、确实是本项目的服务端，
     *     并顺带告诉使用者服务端有没有配好抖音 Cookie；
     *  2. 带签名请求一次空 `url=` —— 服务端是「先鉴权、再校验参数」，
     *     所以返回 unauthorized / bad signature 就说明 token 或 HMAC 密钥不匹配。
     */
    suspend fun testConnection(
        apiBase: String,
        token: String,
        hmacKey: String
    ): String = withContext(Dispatchers.IO) {
        val normalized = ServerConfigStore.normalizeUrl(apiBase)
            ?: return@withContext "❌ 地址格式不对：必须以 http:// 或 https:// 开头"

        val diagReport = try {
            val diagUrl = if (normalized.contains("?")) "$normalized&diag=1" else "$normalized?diag=1"
            client.newCall(Request.Builder().url(diagUrl).get().build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext "❌ 连接失败：HTTP ${resp.code}\n请确认地址正确，且服务端已按 server/README.md 部署"
                }
                val root = runCatching {
                    com.google.gson.JsonParser.parseString(body).asJsonObject
                }.getOrNull()
                    ?: return@withContext "⚠️ 能连上，但响应不是 JSON —— 这个地址不是本项目的 data.php"
                val version = root.strOrNull("version")
                    ?: return@withContext "⚠️ 能连上，但响应缺少 version 字段，可能不是本项目的 data.php"
                val hasCookie = root.strOrNull("douyin_cookie_has_sessionid") == "yes"
                buildString {
                    append("✅ 服务端可达（").append(version).append("）")
                    append("\n抖音 Cookie：")
                    append(if (hasCookie) "已配置" else "未配置（批量解析 / 原画质 / 最高画质会受限）")
                }
            }
        } catch (e: Exception) {
            return@withContext "❌ 连接失败：${e.javaClass.simpleName}: ${e.message}"
        }

        val authReport = try {
            val timeMs = System.currentTimeMillis().toString()
            val sign = hmacSha256(token + timeMs, hmacKey)
            val probeUrl = if (normalized.contains("?")) "$normalized&url=" else "$normalized?url="
            client.newCall(
                Request.Builder()
                    .url(probeUrl)
                    .header("X-Token", token)
                    .header("X-Time", timeMs)
                    .header("X-Sign", sign)
                    .get()
                    .build()
            ).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val err = runCatching {
                    com.google.gson.JsonParser.parseString(body).asJsonObject.strOrNull("error")
                }.getOrNull().orEmpty()
                when {
                    err.contains("unauthorized") ->
                        "❌ 鉴权失败：API Token 与服务端 config.php 里的 API_TOKEN 不一致"
                    err.contains("bad signature") ->
                        "❌ 鉴权失败：HMAC 密钥与服务端 config.php 里的 API_HMAC_KEY 不一致"
                    err.contains("expired") ->
                        "❌ 鉴权失败：服务器与手机时间相差超过 5 分钟，请先校准时间"
                    else ->
                        "✅ 鉴权通过（Token 与 HMAC 密钥都正确）"
                }
            }
        } catch (e: Exception) {
            "⚠️ 鉴权检测未完成：${e.javaClass.simpleName}: ${e.message}"
        }

        "$diagReport\n$authReport"
    }

    private fun com.google.gson.JsonObject.strOrNull(key: String): String? =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()

    // ========== 响应模型 ==========

    private data class ServerError(val error: String? = null)
}
