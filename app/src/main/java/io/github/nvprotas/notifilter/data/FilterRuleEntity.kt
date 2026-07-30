package io.github.nvprotas.notifilter.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.nvprotas.notifilter.domain.FilterRule
import io.github.nvprotas.notifilter.domain.MatchTarget
import io.github.nvprotas.notifilter.domain.RuleAction

@Entity(
    tableName = "filter_rules",
    indices = [Index("enabled"), Index("packageName")],
)
data class FilterRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String?,
    val pattern: String,
    val target: String,
    val action: String,
    val ignoreCase: Boolean,
    val enabled: Boolean,
    val createdAt: Long,
)

fun FilterRuleEntity.toDomain(): FilterRule = FilterRule(
    id = id,
    packageName = packageName?.takeIf(String::isNotBlank),
    pattern = pattern,
    target = enumValueOrDefault(target, MatchTarget.ALL_TEXT),
    action = enumValueOrDefault(action, RuleAction.BLOCK),
    ignoreCase = ignoreCase,
    enabled = enabled,
    createdAt = createdAt,
)

fun FilterRule.toEntity(): FilterRuleEntity = FilterRuleEntity(
    id = id,
    packageName = packageName?.trim()?.takeIf(String::isNotEmpty),
    pattern = pattern,
    target = target.name,
    action = action.name,
    ignoreCase = ignoreCase,
    enabled = enabled,
    createdAt = createdAt,
)

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default

