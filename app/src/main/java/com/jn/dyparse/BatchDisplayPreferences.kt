package com.jn.dyparse

import android.content.Context

object BatchDisplayPreferences {
    private const val PREF_NAME = "dyparse_ui"
    private const val KEY_BATCH_GRID_COLUMNS = "batch_grid_columns"
    private const val DEFAULT_BATCH_GRID_COLUMNS = 4
    val supportedGridColumns = listOf(3, 4, 5, 6)

    fun getBatchGridColumns(context: Context): Int {
        val stored = context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_BATCH_GRID_COLUMNS, DEFAULT_BATCH_GRID_COLUMNS)
        return stored.takeIf { it in supportedGridColumns } ?: DEFAULT_BATCH_GRID_COLUMNS
    }

    fun setBatchGridColumns(context: Context, columns: Int) {
        val normalized = columns.takeIf { it in supportedGridColumns } ?: DEFAULT_BATCH_GRID_COLUMNS
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BATCH_GRID_COLUMNS, normalized)
            .apply()
    }
}
