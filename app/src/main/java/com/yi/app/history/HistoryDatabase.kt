package com.yi.app.history

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "translations")
data class TranslationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val sourceLang: String,
    val targetLang: String,
    val sourceText: String,
    val outputText: String,
)

@Dao
interface HistoryDao {
    @Insert suspend fun insert(r: TranslationRecord): Long
    @Delete suspend fun delete(r: TranslationRecord)
    @Query("DELETE FROM translations") suspend fun clear()

    @Query("SELECT * FROM translations ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<TranslationRecord>>

    @Query("SELECT COUNT(*) FROM translations") suspend fun count(): Int

    @Query("DELETE FROM translations WHERE id NOT IN (SELECT id FROM translations ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 50)
}

@Database(entities = [TranslationRecord::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): HistoryDao

    companion object {
        @Volatile private var INSTANCE: HistoryDatabase? = null
        fun get(context: Context): HistoryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HistoryDatabase::class.java,
                    "yi-history.db",
                ).build().also { INSTANCE = it }
            }
    }
}
