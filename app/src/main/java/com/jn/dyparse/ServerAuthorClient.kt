package com.jn.dyparse

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 服务器作者列表客户端：批量解析时作者作品列表也走服务器（带 cookie + a_bogus），
 * 解决客户端匿名只能看到部分作品的问题。
 */
object ServerAuthorClient {
    // 与 ServerApiClient 一致，构建时从 BuildConfig 注入
    private val API_BASE = BuildConfig.SERVER_AUTHOR_API_BASE
    private val TOKEN = BuildConfig.SERVER_API_TOKEN
    private val HMAC_KEY = BuildConfig.SERVER_HMAC_KEY

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /** 作者列表页结果 */
    data class AuthorListPage(
        val items: List<Map<String, Any>>, // 每项含 aweme_id/desc/create_time/type/cover
        val hasMore: Boolean,
        val nextCursor: Long,
        val totalCount: Int?,
        val authorName: String?,
        val authorUid: String?,
        val authorSecUid: String?,
        val avatarUrl: String?
    )

    /** 递归把 JsonObject 转成 Map（嵌套对象/数组都转，供客户端 dig 使用） */
    private fun jsonToMap(obj: com.google.gson.JsonObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        obj.entrySet().forEach { (key, value) ->
            when {
                value.isJsonNull -> Unit // null 值跳过（避免 "null" 字符串污染）
                value.isJsonObject -> map[key] = jsonToMap(value.asJsonObject)
                value.isJsonArray -> map[key] = value.asJsonArray.map { el ->
                    when {
                        el.isJsonNull -> null // 保留 null 元素，保持与 flat_images 下标对应
                        el.isJsonObject -> jsonToMap(el.asJsonObject)
                        el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> el.asBoolean
                        el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asNumber.toLong()
                        else -> el.asString
                    }
                }
                value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> map[key] = value.asBoolean
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> map[key] = value.asNumber.toLong()
                value.isJsonPrimitive -> map[key] = value.asString
                else -> map[key] = value.toString()
            }
        }
        return map
    }

    /**
     * 请求作者作品列表（服务器 cookie 模式）。
     * @param input 作者主页链接/分享口令
     * @param maxCursor 分页游标
     * @param count 每页数量
     */
    suspend fun fetchAuthorList(
        input: String,
        maxCursor: Long = 0L,
        count: Int = 18
    ): AuthorListPage = withContext(Dispatchers.IO) {
        val encodedInput = URLEncoder.encode(input, "UTF-8")
        val cursorSuffix = if (maxCursor > 0) "&max_cursor=$maxCursor" else ""
        val countSuffix = if (count != 18) "&count=$count" else ""
        val fullUrl = API_BASE + "?url=" + encodedInput + cursorSuffix + countSuffix

        val timeMs = System.currentTimeMillis().toString()
        val signPayload = TOKEN + timeMs + encodedInput + cursorSuffix + countSuffix
        val sign = ServerApiClient.hmacSha256(signPayload, HMAC_KEY)

        val request = Request.Builder()
            .url(fullUrl)
            .header("X-Token", TOKEN)
            .header("X-Time", timeMs)
            .header("X-Sign", sign)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("作者列表服务器错误 (${response.code})")
            }
            val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                ?: throw IOException("作者列表响应格式错误")
            val success = runCatching { root.get("success").asBoolean }.getOrDefault(false)
            if (!success) {
                val err = runCatching { root.get("error").asString }.getOrNull() ?: "未知错误"
                throw IOException("作者列表解析失败($err)")
            }

            fun str(key: String): String? = runCatching {
                root.get(key)?.takeIf { !it.isJsonNull }?.asString
            }.getOrNull()

            val items = runCatching {
                root.get("items")?.asJsonArray?.mapNotNull { el ->
                    jsonToMap(el.asJsonObject)
                }
            }.getOrNull().orEmpty()

            AuthorListPage(
                items = items,
                hasMore = runCatching { root.get("has_more").asBoolean }.getOrDefault(false),
                nextCursor = runCatching { root.get("max_cursor").asLong }.getOrDefault(maxCursor),
                totalCount = runCatching { root.get("total_count").asInt }.getOrNull(),
                authorName = str("author_name"),
                authorUid = str("author_uid"),
                authorSecUid = str("author_sec_uid"),
                avatarUrl = str("avatar_url")
            )
        }
    }
}
