package com.jn.dyparse

import android.content.Context
import android.content.SharedPreferences

/**
 * 服务器解析 API 的运行时配置。
 *
 * 优先级：**App 内填写的值 > 构建时注入的 BuildConfig 默认值**。
 *
 * 为什么需要这一层：
 * 本项目的解析逻辑完全在服务端（见 README），而服务端需要使用者自己部署、
 * 并配置自己的抖音登录 Cookie —— 这两样都不可能随开源代码或 APK 分发。
 * 所以分发的 APK 里只能带占位地址，使用者在「设置 → 服务器配置」里填入
 * 自己的服务端信息即可，**无需重新编译**。
 */
object ServerConfigStore {
    private const val PREF_NAME = "dyparse_server_config"
    private const val KEY_API_BASE = "api_base"
    private const val KEY_AUTHOR_API_BASE = "author_api_base"
    private const val KEY_TOKEN = "api_token"
    private const val KEY_HMAC_KEY = "hmac_key"

    /** 与 server/config.example.php 对应的四个值 */
    data class Config(
        val apiBase: String,
        val authorApiBase: String,
        val token: String,
        val hmacKey: String
    )

    @Volatile
    private var appContext: Context? = null

    /** 在 Application.onCreate 里调用一次 */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 构建时注入的默认值（来自 local.properties / 环境变量 / CI Secrets） */
    fun defaults(): Config = Config(
        apiBase = BuildConfig.SERVER_API_BASE,
        authorApiBase = BuildConfig.SERVER_AUTHOR_API_BASE,
        token = BuildConfig.SERVER_API_TOKEN,
        hmacKey = BuildConfig.SERVER_HMAC_KEY
    )

    /** 当前生效配置：App 内填过的项优先，未填的项回落到默认值 */
    fun getConfig(): Config {
        val fallback = defaults()
        val prefs = prefs() ?: return fallback
        return Config(
            apiBase = prefs.stringOrNull(KEY_API_BASE) ?: fallback.apiBase,
            authorApiBase = prefs.stringOrNull(KEY_AUTHOR_API_BASE) ?: fallback.authorApiBase,
            token = prefs.stringOrNull(KEY_TOKEN) ?: fallback.token,
            hmacKey = prefs.stringOrNull(KEY_HMAC_KEY) ?: fallback.hmacKey
        )
    }

    /** 用户是否在 App 内配置过（仅用于设置页展示状态） */
    fun hasUserConfig(): Boolean = prefs()?.contains(KEY_API_BASE) == true

    fun save(config: Config) {
        prefs()?.edit()
            ?.putString(KEY_API_BASE, config.apiBase.trim())
            ?.putString(KEY_AUTHOR_API_BASE, config.authorApiBase.trim())
            ?.putString(KEY_TOKEN, config.token.trim())
            ?.putString(KEY_HMAC_KEY, config.hmacKey.trim())
            ?.apply()
    }

    /** 清除 App 内配置，回到构建时的默认值 */
    fun reset() {
        prefs()?.edit()?.clear()?.apply()
    }

    /** 规范化用户输入的地址；不合法（空 / 非 http(s)）返回 null */
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return trimmed
    }

    /**
     * 是否是"还没配置过"的占位地址。
     * 用于给使用者一个明确的引导，而不是抛一个看不懂的网络错误。
     */
    fun isPlaceholder(url: String): Boolean =
        url.isBlank() || url.contains("your-server.example.com", ignoreCase = true)

    private fun SharedPreferences.stringOrNull(key: String): String? =
        getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
}
