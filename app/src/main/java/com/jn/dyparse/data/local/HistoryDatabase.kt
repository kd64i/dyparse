package com.jn.dyparse.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(
    tableName = "parse_history",
    primaryKeys = ["videoId", "parseTimestamp"]
)
data class ParseHistoryEntity(
    val videoId: String,
    val parseTimestamp: Long,
    val payloadJson: String,
    // 轻量投影列（列表页避免反序列化完整 payloadJson）
    val title: String? = null,
    val author: String? = null,
    val cover: String? = null,
    val type: String? = null
)

@Entity(tableName = "batch_history")
data class BatchHistoryEntity(
    @androidx.room.PrimaryKey val batchId: String,
    val parseTimestamp: Long,
    val summaryJson: String
)

@Entity(
    tableName = "batch_history_works",
    primaryKeys = ["batchId", "videoId", "parseTimestamp"],
    foreignKeys = [
        ForeignKey(
            entity = BatchHistoryEntity::class,
            parentColumns = ["batchId"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("batchId")]
)
data class BatchHistoryWorkEntity(
    val batchId: String,
    val videoId: String,
    val parseTimestamp: Long,
    val position: Int,
    val payloadJson: String
)

/** 轻量历史投影（列表页用，不含 payloadJson） */
data class ParseHistoryLightEntity(
    val videoId: String,
    val parseTimestamp: Long,
    val title: String?,
    val author: String?,
    val cover: String?,
    val type: String?
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM parse_history ORDER BY parseTimestamp DESC")
    suspend fun getParseHistory(): List<ParseHistoryEntity>

    // 轻量投影：列表页只取展示字段，不读 payloadJson（避免大 JSON 反序列化）
    @Query(
        "SELECT videoId, parseTimestamp, title, author, cover, type " +
            "FROM parse_history ORDER BY parseTimestamp DESC"
    )
    suspend fun getParseHistoryLight(): List<ParseHistoryLightEntity>

    // 按主键取完整记录（详情页用）
    @Query(
        "SELECT * FROM parse_history WHERE videoId = :videoId AND parseTimestamp = :parseTimestamp LIMIT 1"
    )
    suspend fun getParseHistoryDetail(videoId: String, parseTimestamp: Long): ParseHistoryEntity?

    // 回填缺失的投影列（旧数据首次迁移后）
    @Query(
        "UPDATE parse_history SET title = :title, author = :author, cover = :cover, type = :type " +
            "WHERE videoId = :videoId AND parseTimestamp = :parseTimestamp"
    )
    suspend fun updateLightColumns(
        videoId: String,
        parseTimestamp: Long,
        title: String?,
        author: String?,
        cover: String?,
        type: String?
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParseHistory(entity: ParseHistoryEntity)

    @Query("DELETE FROM parse_history WHERE videoId = :videoId")
    suspend fun deleteParseHistoryByVideoId(videoId: String)

    @Query("DELETE FROM parse_history WHERE videoId = :videoId AND parseTimestamp = :parseTimestamp")
    suspend fun deleteParseHistory(videoId: String, parseTimestamp: Long)

    @Query(
        "DELETE FROM parse_history WHERE rowid NOT IN " +
            "(SELECT rowid FROM parse_history ORDER BY parseTimestamp DESC LIMIT :limit)"
    )
    suspend fun trimParseHistory(limit: Int)

    @Query("SELECT COUNT(*) FROM parse_history")
    suspend fun getParseHistoryCount(): Int

    @Query("SELECT * FROM batch_history ORDER BY parseTimestamp DESC")
    suspend fun getBatchHistory(): List<BatchHistoryEntity>

    @Query("SELECT COUNT(*) FROM batch_history")
    suspend fun getBatchHistoryCount(): Int

    @Query("SELECT * FROM batch_history_works WHERE batchId = :batchId ORDER BY position ASC")
    suspend fun getBatchHistoryWorks(batchId: String): List<BatchHistoryWorkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatchHistory(entity: BatchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatchHistoryWorks(entities: List<BatchHistoryWorkEntity>)

    @Query("DELETE FROM batch_history_works WHERE batchId = :batchId")
    suspend fun deleteBatchHistoryWorks(batchId: String)

    @Query("DELETE FROM batch_history WHERE batchId = :batchId")
    suspend fun deleteBatchHistory(batchId: String)

    @Transaction
    suspend fun replaceBatchHistory(
        entity: BatchHistoryEntity,
        works: List<BatchHistoryWorkEntity>
    ) {
        insertBatchHistory(entity)
        deleteBatchHistoryWorks(entity.batchId)
        if (works.isNotEmpty()) {
            insertBatchHistoryWorks(works)
        }
    }
}

@Database(
    entities = [
        ParseHistoryEntity::class,
        BatchHistoryEntity::class,
        BatchHistoryWorkEntity::class
    ],
    version = 2,
    // Schema export is disabled because this app has no external migration
    // tooling; Room still validates the schema at compile time.
    exportSchema = false
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var instance: HistoryDatabase? = null

        fun get(context: Context): HistoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HistoryDatabase::class.java,
                    "dyparse_history.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }

        /** v1 → v2：parse_history 增加轻量投影列（title/author/cover/type） */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE parse_history ADD COLUMN title TEXT"
                )
                db.execSQL(
                    "ALTER TABLE parse_history ADD COLUMN author TEXT"
                )
                db.execSQL(
                    "ALTER TABLE parse_history ADD COLUMN cover TEXT"
                )
                db.execSQL(
                    "ALTER TABLE parse_history ADD COLUMN type TEXT"
                )
            }
        }
    }
}
