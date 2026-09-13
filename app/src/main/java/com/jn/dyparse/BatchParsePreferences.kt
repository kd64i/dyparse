package com.jn.dyparse

import android.content.Context

object BatchParsePreferences {
    private const val PREF_NAME = "dyparse_batch_parse"
    private const val KEY_WORK_INTERVAL_MS = "work_interval_ms"
    private const val KEY_AUTHOR_PAGE_INTERVAL_MS = "author_page_interval_ms"
    private const val KEY_CONSECUTIVE_FAILURE_STOP_COUNT = "consecutive_failure_stop_count"

    const val DEFAULT_WORK_INTERVAL_MS = 800
    const val DEFAULT_AUTHOR_PAGE_INTERVAL_MS = 500
    const val DEFAULT_CONSECUTIVE_FAILURE_STOP_COUNT = 1
    const val RECOMMENDED_WORK_INTERVAL_MS = 800
    const val RECOMMENDED_AUTHOR_PAGE_INTERVAL_MS = 500
    const val RECOMMENDED_CONSECUTIVE_FAILURE_STOP_COUNT = 1

    private const val MIN_INTERVAL_MS = 0
    private const val MAX_INTERVAL_MS = 60_000
    private const val MIN_FAILURE_STOP_COUNT = 0
    private const val MAX_FAILURE_STOP_COUNT = 100

    data class Settings(
        val workIntervalMs: Int,
        val authorPageIntervalMs: Int,
        val consecutiveFailureStopCount: Int
    )

    fun getSettings(context: Context): Settings {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return Settings(
            workIntervalMs = normalizeInterval(
                prefs.getInt(KEY_WORK_INTERVAL_MS, DEFAULT_WORK_INTERVAL_MS),
                DEFAULT_WORK_INTERVAL_MS
            ),
            authorPageIntervalMs = normalizeInterval(
                prefs.getInt(KEY_AUTHOR_PAGE_INTERVAL_MS, DEFAULT_AUTHOR_PAGE_INTERVAL_MS),
                DEFAULT_AUTHOR_PAGE_INTERVAL_MS
            ),
            consecutiveFailureStopCount = normalizeFailureStopCount(
                prefs.getInt(
                    KEY_CONSECUTIVE_FAILURE_STOP_COUNT,
                    DEFAULT_CONSECUTIVE_FAILURE_STOP_COUNT
                )
            )
        )
    }

    fun saveSettings(
        context: Context,
        workIntervalMs: Int,
        authorPageIntervalMs: Int,
        consecutiveFailureStopCount: Int
    ) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_WORK_INTERVAL_MS, normalizeInterval(workIntervalMs, DEFAULT_WORK_INTERVAL_MS))
            .putInt(
                KEY_AUTHOR_PAGE_INTERVAL_MS,
                normalizeInterval(authorPageIntervalMs, DEFAULT_AUTHOR_PAGE_INTERVAL_MS)
            )
            .putInt(
                KEY_CONSECUTIVE_FAILURE_STOP_COUNT,
                normalizeFailureStopCount(consecutiveFailureStopCount)
            )
            .apply()
    }

    fun reset(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_WORK_INTERVAL_MS)
            .remove(KEY_AUTHOR_PAGE_INTERVAL_MS)
            .remove(KEY_CONSECUTIVE_FAILURE_STOP_COUNT)
            .apply()
    }

    private fun normalizeInterval(value: Int, fallback: Int): Int {
        return value.takeIf { it in MIN_INTERVAL_MS..MAX_INTERVAL_MS } ?: fallback
    }

    private fun normalizeFailureStopCount(value: Int): Int {
        return value.coerceIn(MIN_FAILURE_STOP_COUNT, MAX_FAILURE_STOP_COUNT)
    }
}
