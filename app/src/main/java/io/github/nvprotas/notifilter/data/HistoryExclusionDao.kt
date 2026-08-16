package io.github.nvprotas.notifilter.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryExclusionDao {
    @Query("SELECT * FROM history_exclusions ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<HistoryExclusionEntity>>

    @Query("SELECT * FROM history_exclusions WHERE enabled = 1 ORDER BY createdAt DESC, id DESC")
    fun observeEnabled(): Flow<List<HistoryExclusionEntity>>

    @Query("SELECT * FROM history_exclusions ORDER BY createdAt DESC, id DESC")
    suspend fun getAll(): List<HistoryExclusionEntity>

    @Query("SELECT * FROM history_exclusions WHERE enabled = 1 ORDER BY createdAt DESC, id DESC")
    suspend fun getEnabled(): List<HistoryExclusionEntity>

    @Upsert
    suspend fun save(rule: HistoryExclusionEntity)

    @Delete
    suspend fun delete(rule: HistoryExclusionEntity)
}
