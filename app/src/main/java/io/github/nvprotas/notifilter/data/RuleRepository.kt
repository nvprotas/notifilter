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
}

