package io.github.nvprotas.notifilter.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.nvprotas.notifilter.domain.FilterRule
import kotlinx.coroutines.flow.Flow

@Dao
interface FilterRuleDao {
    @Query("SELECT * FROM filter_rules ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<FilterRuleEntity>>

    @Query("SELECT * FROM filter_rules WHERE enabled = 1 ORDER BY createdAt DESC, id DESC")
    fun observeEnabled(): Flow<List<FilterRuleEntity>>

    @Query("SELECT * FROM filter_rules WHERE enabled = 1 ORDER BY createdAt DESC, id DESC")
    suspend fun getEnabled(): List<FilterRuleEntity>

    @Query("SELECT * FROM filter_rules ORDER BY createdAt DESC, id DESC")
    suspend fun getAll(): List<FilterRuleEntity>

    @Upsert
    suspend fun save(rule: FilterRuleEntity)

    @Delete
    suspend fun delete(rule: FilterRuleEntity)

    @Insert
    suspend fun insertAll(rules: List<FilterRuleEntity>)

    @Query("DELETE FROM filter_rules")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(rules: List<FilterRuleEntity>) {
        deleteAll()
        insertAll(rules)
    }

    @Transaction
    suspend fun insertUnique(rules: List<FilterRuleEntity>): Int {
        val existingKeys = getAll().mapTo(mutableSetOf()) { it.functionalKey() }
        val uniqueRules = rules.filter { existingKeys.add(it.functionalKey()) }
        insertAll(uniqueRules)
        return uniqueRules.size
    }
}

internal data class FilterRuleFunctionalKey(
    val packageName: String?,
    val pattern: String,
    val target: String,
    val action: String,
    val ignoreCase: Boolean,
    val enabled: Boolean,
)

internal fun FilterRuleEntity.functionalKey() = FilterRuleFunctionalKey(
    packageName = packageName,
    pattern = pattern,
    target = target,
    action = action,
    ignoreCase = ignoreCase,
    enabled = enabled,
)

internal fun FilterRule.functionalKey() = FilterRuleFunctionalKey(
    packageName = packageName,
    pattern = pattern,
    target = target.name,
    action = action.name,
    ignoreCase = ignoreCase,
    enabled = enabled,
)
