package com.jn.dyparse.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jn.dyparse.NativeLib
import com.jn.dyparse.data.local.BatchHistoryEntity
import com.jn.dyparse.data.local.BatchHistoryWorkEntity
import com.jn.dyparse.data.local.HistoryDatabase
import com.jn.dyparse.data.local.ParseHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Room-backed source of truth for single and batch parse history. */
class HistoryRepository(
    context: Context,
    private val gson: Gson = Gson()
) {
    private val appContext = context.applicationContext
    private val dao = HistoryDatabase.get(appContext).historyDao()
    private val migrationMutex = Mutex()
    private val migrationPreferences = appContext.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)

    suspend fun getParseHistory(): List<ParseResult.Success> = withContext(Dispatchers.IO) {
        migrateLegacyHistoryIfNeeded()
        readParseHistoryNoMigration()
    }

    suspend fun saveParseResult(result: ParseResult.Success): List<ParseResult.Success> =
        withContext(Dispatchers.IO) {
            migrateLegacyHistoryIfNeeded()
            dao.deleteParseHistoryByVideoId(result.videoId)
            dao.insertParseHistory(result.toEntity())
            dao.trimParseHistory(MAX_PARSE_HISTORY_SIZE)
            readParseHistoryNoMigration()
        }

    suspend fun updateParseResult(result: ParseResult.Success): List<ParseResult.Success> =
        withContext(Dispatchers.IO) {
            migrateLegacyHistoryIfNeeded()
            dao.insertParseHistory(result.toEntity())
            readParseHistoryNoMigration()
        }

    suspend fun deleteParseResult(result: ParseResult.Success): List<ParseResult.Success> =
        withContext(Dispatchers.IO) {
            migrateLegacyHistoryIfNeeded()
            dao.deleteParseHistory(result.videoId, result.parseTimestamp)
            readParseHistoryNoMigration()
        }

    suspend fun getBatchHistory(): List<BatchAuthorParseSummary> = withContext(Dispatchers.IO) {
        migrateLegacyHistoryIfNeeded()
        readBatchHistoryNoMigration()
    }

    suspend fun saveBatchHistory(
        summary: BatchAuthorParseSummary,
        works: List<ParseResult.Success>
    ): List<BatchAuthorParseSummary> = withContext(Dispatchers.IO) {
        migrateLegacyHistoryIfNeeded()
        dao.replaceBatchHistory(summary.toEntity(), works.toEntities(summary.batchId))
        readBatchHistoryNoMigration()
    }

    suspend fun getBatchHistoryWorks(batchId: String): List<ParseResult.Success> =
        withContext(Dispatchers.IO) {
            migrateLegacyHistoryIfNeeded()
            dao.getBatchHistoryWorks(batchId).mapNotNull { entity ->
                runCatching { gson.fromJson(entity.payloadJson, ParseResult.Success::class.java) }.getOrNull()
            }
        }

    suspend fun deleteBatchHistory(batchId: String): List<BatchAuthorParseSummary> =
        withContext(Dispatchers.IO) {
            migrateLegacyHistoryIfNeeded()
            dao.deleteBatchHistory(batchId)
            readBatchHistoryNoMigration()
        }

    suspend fun updateBatchWork(
        item: ParseResult.Success,
        transform: (ParseResult.Success) -> ParseResult.Success
    ) = withContext(Dispatchers.IO) {
        val batchId = item.batchId ?: return@withContext
        val works = getBatchHistoryWorks(batchId).toMutableList()
        val index = works.indexOfFirst {
            it.videoId == item.videoId && it.parseTimestamp == item.parseTimestamp
        }
        if (index < 0) return@withContext
        works[index] = transform(works[index])
        dao.deleteBatchHistoryWorks(batchId)
        dao.insertBatchHistoryWorks(works.toEntities(batchId))
    }

    private suspend fun migrateLegacyHistoryIfNeeded() = migrationMutex.withLock {
        if (migrationPreferences.getBoolean(KEY_MIGRATED, false)) return@withLock

        runCatching {
            if (dao.getParseHistoryCount() == 0) {
                readLegacyParseHistory().forEach { dao.insertParseHistory(it.toEntity()) }
                dao.trimParseHistory(MAX_PARSE_HISTORY_SIZE)
            }
            if (dao.getBatchHistoryCount() == 0) {
                readLegacyBatchHistory().forEach { summary ->
                    val works = readLegacyBatchWorks(summary.batchId)
                    dao.replaceBatchHistory(summary.toEntity(), works.toEntities(summary.batchId))
                }
            }
            migrationPreferences.edit().putBoolean(KEY_MIGRATED, true).apply()
        }.onFailure { error ->
            Log.w("HistoryRepository", "Legacy history migration failed", error)
        }
    }

    private suspend fun readParseHistoryNoMigration(): List<ParseResult.Success> =
        dao.getParseHistory().mapNotNull { entity ->
            runCatching { gson.fromJson(entity.payloadJson, ParseResult.Success::class.java) }.getOrNull()
        }

    private suspend fun readBatchHistoryNoMigration(): List<BatchAuthorParseSummary> =
        dao.getBatchHistory().mapNotNull { entity ->
            runCatching { gson.fromJson(entity.summaryJson, BatchAuthorParseSummary::class.java) }.getOrNull()
        }

    private fun readLegacyParseHistory(): List<ParseResult.Success> {
        val file = File(appContext.filesDir, NativeLib.getHistoryFileName())
        if (!file.isFile) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<ParseResult.Success>>() {}.type
            gson.fromJson<List<ParseResult.Success>>(file.readText(), type).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun readLegacyBatchHistory(): List<BatchAuthorParseSummary> {
        val file = File(appContext.filesDir, LEGACY_BATCH_INDEX_FILE)
        if (!file.isFile) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<BatchAuthorParseSummary>>() {}.type
            gson.fromJson<List<BatchAuthorParseSummary>>(file.readText(), type).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun readLegacyBatchWorks(batchId: String): List<ParseResult.Success> {
        val file = File(File(appContext.filesDir, LEGACY_BATCH_DIRECTORY), "$batchId.json")
        if (!file.isFile) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<ParseResult.Success>>() {}.type
            gson.fromJson<List<ParseResult.Success>>(file.readText(), type).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun ParseResult.Success.toEntity() = ParseHistoryEntity(
        videoId = videoId,
        parseTimestamp = parseTimestamp,
        payloadJson = gson.toJson(this),
        title = title,
        author = author,
        cover = cover,
        type = type
    )

    /**
     * 轻量历史列表：只读投影列（title/author/cover/type），
     * 供列表页使用，避免读取完整 payloadJson 的大 JSON。
     * 旧数据先回填投影列，回填前缺失的条目本次跳过（下次加载即有）。
     */
    suspend fun getParseHistoryLight(): List<ParseResult.Success> = withContext(Dispatchers.IO) {
        migrateLegacyHistoryIfNeeded()
        backfillProjectionColumnsIfNeeded()
        dao.getParseHistoryLight().mapNotNull { entity ->
            val title = entity.title ?: return@mapNotNull null
            ParseResult.Success(
                author = entity.author ?: "未知作者",
                title = title,
                type = entity.type ?: "video",
                playUrl = null,
                rawPlayUrl = null,
                images = null,
                timestamp = entity.parseTimestamp,
                videoId = entity.videoId,
                cover = entity.cover,
                inputUrl = ""
            )
        }
    }

    /** 详情按需加载：按主键读取完整 payloadJson */
    suspend fun getParseHistoryDetail(
        videoId: String,
        parseTimestamp: Long
    ): ParseResult.Success? = withContext(Dispatchers.IO) {
        dao.getParseHistoryDetail(videoId, parseTimestamp)?.let { entity ->
            runCatching {
                gson.fromJson(entity.payloadJson, ParseResult.Success::class.java)
            }.getOrNull()
        }
    }

    /** 旧数据投影列回填：仅在列缺失时从 payloadJson 解析一次 */
    private suspend fun backfillProjectionColumnsIfNeeded() {
        val needsBackfill = dao.getParseHistoryLight().any { it.title == null }
        if (!needsBackfill) return
        dao.getParseHistory().forEach { entity ->
            val parsed = runCatching {
                gson.fromJson(entity.payloadJson, ParseResult.Success::class.java)
            }.getOrNull() ?: return@forEach
            dao.updateLightColumns(
                videoId = entity.videoId,
                parseTimestamp = entity.parseTimestamp,
                title = parsed.title,
                author = parsed.author,
                cover = parsed.cover,
                type = parsed.type
            )
        }
    }

    private fun BatchAuthorParseSummary.toEntity() = BatchHistoryEntity(
        batchId = batchId,
        parseTimestamp = parseTimestamp,
        summaryJson = gson.toJson(this)
    )

    private fun List<ParseResult.Success>.toEntities(batchId: String) = mapIndexed { index, item ->
        BatchHistoryWorkEntity(
            batchId = batchId,
            videoId = item.videoId,
            parseTimestamp = item.parseTimestamp,
            position = index,
            payloadJson = gson.toJson(item)
        )
    }

    companion object {
        private const val MAX_PARSE_HISTORY_SIZE = 100
        private const val MIGRATION_PREFS = "history_room_migration"
        private const val KEY_MIGRATED = "legacy_json_migrated_v1"
        private const val LEGACY_BATCH_DIRECTORY = ".batch_author_history"
        private const val LEGACY_BATCH_INDEX_FILE = ".batch_author_history_index"
    }
}
