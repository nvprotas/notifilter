package io.github.nvprotas.notifilter.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FilterRuleDao {
    @Query("SELECT * FROM filter_rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FilterRuleEntity>>

    @Query("SELECT * FROM filter_rules WHERE enabled = 1 ORDER BY createdAt DESC")
    fun observeEnabled(): Flow<List<FilterRuleEntity>>

    @Query("SELECT * FROM filter_rules WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun getEnabled(): List<FilterRuleEntity>

    @Upsert
    suspend fun save(rule: FilterRuleEntity)

    @Delete
    suspend fun delete(rule: FilterRuleEntity)
}
