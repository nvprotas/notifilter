package io.github.nvprotas.notifilter.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.nvprotas.notifilter.domain.HistoryExclusion
import io.github.nvprotas.notifilter.domain.MatchTarget

@Entity(
    tableName = "history_exclusions",
    indices = [Index("enabled"), Index("packageName")],
)
data class HistoryExclusionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String?,
    val pattern: String?,
    val target: String,
    val ignoreCase: Boolean,
    val enabled: Boolean,
    val createdAt: Long,
)

fun HistoryExclusionEntity.toDomain(): HistoryExclusion = HistoryExclusion(
    id = id,
    packageName = packageName?.trim()?.takeIf(String::isNotEmpty),
    pattern = pattern?.trim()?.takeIf(String::isNotEmpty),
    target = enumValues<MatchTarget>().firstOrNull { it.name == target } ?: MatchTarget.ALL_TEXT,
    ignoreCase = ignoreCase,
    enabled = enabled,
    createdAt = createdAt,
)

fun HistoryExclusion.toEntity(): HistoryExclusionEntity = HistoryExclusionEntity(
    id = id,
    packageName = packageName?.trim()?.takeIf(String::isNotEmpty),
    pattern = pattern?.trim()?.takeIf(String::isNotEmpty),
    target = target.name,
    ignoreCase = ignoreCase,
    enabled = enabled,
    createdAt = createdAt,
)
