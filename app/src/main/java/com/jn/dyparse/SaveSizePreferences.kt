package com.jn.dyparse

import android.content.Context

/**
 * 保存视频大小提示设置：单条保存视频前探测大小，
 * 若超过阈值则弹窗询问是否继续保存。
 * 阈值单位 MB，0 表示关闭该功能（默认 100MB）。
 */
object SaveSizePreferences {
    private const val PREF_NAME = "dyparse_save_size"
    private const val KEY_VIDEO_SIZE_LIMIT_MB = "video_size_limit_mb"

    const val DEFAULT_LIMIT_MB = 100

    fun getLimitMb(context: Context): Int {
        return context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_VIDEO_SIZE_LIMIT_MB, DEFAULT_LIMIT_MB)
            .coerceAtLeast(0)
    }

    fun setLimitMb(context: Context, mb: Int) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VIDEO_SIZE_LIMIT_MB, mb.coerceAtLeast(0))
            .apply()
    }
}