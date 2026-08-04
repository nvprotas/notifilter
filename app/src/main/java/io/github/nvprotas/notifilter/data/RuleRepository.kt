package io.github.nvprotas.notifilter.data

import io.github.nvprotas.notifilter.domain.FilterRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RuleRepository(
    private val dao: FilterRuleDao,
) {
    val rules: Flow<List<FilterRule>> = dao.observeAll().map { entities ->
        entities.map(FilterRuleEntity::toDomain)
    }

    suspend fun save(rule: FilterRule) {
        dao.save(rule.toEntity())
    }

    suspend fun delete(rule: FilterRule) {
        dao.delete(rule.toEntity())
    }

    suspend fun exportRules(): List<FilterRule> =
        dao.getAll().map(FilterRuleEntity::toDomain)

    suspend fun addImportedRules(rules: List<FilterRule>): RuleImportResult {
        val entities = importedEntities(rules)
        val imported = dao.insertUnique(entities)
        return RuleImportResult(
            imported = imported,
            skipped = rules.size - imported,
        )
    }

    suspend fun replaceRules(rules: List<FilterRule>): RuleImportResult {
        dao.replaceAll(importedEntities(rules))
        return RuleImportResult(imported = rules.size, skipped = 0)
    }

    private fun importedEntities(rules: List<FilterRule>): List<FilterRuleEntity> {
        val newestTimestamp = System.currentTimeMillis()
        return rules.mapIndexed { index, rule ->
            rule.copy(
                id = 0,
                createdAt = newestTimestamp - index,
            ).toEntity()
        }
    }
}

data class RuleImportResult(
    val imported: Int,
    val skipped: Int,
)
