package com.jn.dyparse

import android.content.Context

/**
 * 匿名会话 cookie 持久化（ttwid / msToken / __ac_nonce 等）。
 *
 * 用途：未设置登录 Cookie 时，单条解析首次请求往往被风控拦截；
 * 通过 WebView 预热拿到浏览器侧生成的匿名会话 cookie 后持久化复用，
 * 后续解析直接携带，显著提高无 Cookie 模式的解析成功率。
 * 一旦用户手动设置了登录 Cookie，本存储的匿名会话会被忽略
 * （调用方总是优先使用 DouyinAuthStore 的已认证 Cookie）。
 */
object AnonymousSessionStore {
    private const val PREF_NAME = "dyparse_anon_session"
    private const val KEY_COOKIE = "anon_cookie"
    private const val KEY_UPDATED_AT_MS = "updated_at_ms"

    /** 匿名会话 cookie 的复用有效期：24 小时，过期自动作废重新预热。 */
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    fun getCookie(context: Context): String? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val cookie = prefs.getString(KEY_COOKIE, null)?.trim() ?: return null
        if (cookie.isBlank()) {
            return null
        }
        val updatedAt = prefs.getLong(KEY_UPDATED_AT_MS, 0L)
        if (updatedAt <= 0L || System.currentTimeMillis() - updatedAt > MAX_AGE_MS) {
            clear(context)
            return null
        }
        return cookie
    }

    fun saveCookie(context: Context, rawCookie: String) {
        // 仅保存对解析有实际价值的匿名会话字段，避免把无意义的噪声一并存下
        val usefulNames = setOf(
            "ttwid",
            "msToken",
            "__ac_nonce",
            "__ac_signature",
            "odin_tt",
            "passport_csrf_token",
            "home_csrf_token"
        )
        val normalized = DouyinAuthStore.normalizeCookieHeader(rawCookie)
            .split(";")
            .map { it.trim() }
            .filter { entry ->
                val name = entry.substringBefore("=").trim().lowercase()
                usefulNames.any(name::equals)
            }
            .joinToString("; ")
        if (normalized.isBlank()) {
            return
        }
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COOKIE, normalized)
            .putLong(KEY_UPDATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_COOKIE)
            .remove(KEY_UPDATED_AT_MS)
            .apply()
    }
}