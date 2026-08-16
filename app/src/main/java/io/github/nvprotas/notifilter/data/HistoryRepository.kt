package io.github.nvprotas.notifilter.data

import androidx.room.withTransaction
import io.github.nvprotas.notifilter.domain.HistoryExclusion
import io.github.nvprotas.notifilter.domain.HistoryExclusionMatcher
import io.github.nvprotas.notifilter.domain.HistoryExclusionValidator
import io.github.nvprotas.notifilter.domain.NotificationContent
import io.github.nvprotas.notifilter.domain.RuleMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock

data class HistoryRecordRequest(
    val eventId: String,
    val sourceIdentity: String?,
    val content: NotificationContent,
    val postedAt: Long,
    val updatedAt: Long,
    val matchedRuleId: Long?,
    val matchedRulePattern: String?,
    val outcome: HistoryOutcome,
    val active: Boolean,
)

class HistoryRepository(
    private val database: AppDatabase,
    private val preferences: UserPreferences,
) {
    private val historyDao = database.notificationHistoryDao()
    private val exclusionDao = database.historyExclusionDao()

    val exclusions: Flow<List<HistoryExclusion>> = exclusionDao.observeAll()
        .map { entities -> entities.map(HistoryExclusionEntity::toDomain) }

    fun observeHistory(cutoff: Long): Flow<List<NotificationHistoryEntity>> =
        historyDao.observeSince(cutoff)

    suspend fun initializePolicy(): Boolean = HistoryOperationCoordinator.mutex.withLock {
        publishCurrentPolicy()
    }

    suspend fun observePolicyChanges(): Nothing {
        combine(preferences.historyEnabled, exclusionDao.observeEnabled()) { enabled, entities ->
            enabled to entities.map(HistoryExclusionEntity::toDomain)
        }.collect { (enabled, rules) ->
            HistoryOperationCoordinator.mutex.withLock {
                val matcher = HistoryExclusionMatcher.compile(rules).getOrElse {
                    HistoryOperationCoordinator.invalidate()
                    return@withLock
                }
                HistoryOperationCoordinator.publishPolicy(enabled, matcher)
            }
        }
        error("History policy observation completed unexpectedly")
    }

    suspend fun setHistoryEnabled(enabled: Boolean) {
        mutatePolicy(System.currentTimeMillis()) {
            check(preferences.setHistoryEnabled(enabled))
        }
    }

    suspend fun saveExclusion(rule: HistoryExclusion) {
        val normalized = HistoryExclusionValidator.normalize(rule)
        HistoryExclusionValidator.validationError(normalized)?.let { throw IllegalArgumentException(it) }
        mutatePolicy(System.currentTimeMillis()) {
            database.withTransaction {
                exclusionDao.save(normalized.toEntity())
                if (normalized.enabled) deleteMatches(normalized)
            }
        }
    }

    suspend fun deleteExclusion(rule: HistoryExclusion) {
        mutatePolicy(System.currentTimeMillis()) {
            exclusionDao.delete(rule.toEntity())
        }
    }

    suspend fun deleteHistoryEntry(entry: NotificationHistoryEntity) {
        HistoryOperationCoordinator.mutex.withLock { historyDao.delete(entry) }
    }

    suspend fun clearHistory(at: Long = System.currentTimeMillis()) {
        mutatePolicy(at) { historyDao.clearHistory(at) }
    }

    suspend fun pruneHistory(now: Long = System.currentTimeMillis()) {
        HistoryOperationCoordinator.mutex.withLock {
            historyDao.deleteOlderThan(now - historyRetentionMillis())
            historyDao.trimToSize(UserPreferences.HISTORY_MAX_ENTRIES)
        }
    }

    fun assessCapture(content: NotificationContent, eventTime: Long): HistoryCaptureAssessment =
        HistoryOperationCoordinator.assess(content, eventTime)

    suspend fun record(request: HistoryRecordRequest, token: HistoryWriteToken): Boolean =
        HistoryOperationCoordinator.mutex.withLock {
            if (!HistoryOperationCoordinator.canWrite(token)) return@withLock false
            val policy = HistoryOperationCoordinator.currentPolicy() ?: return@withLock false
            if (policy.matcher.matches(request.content)) return@withLock false
            historyDao.upsertAndPrune(
                entry = NotificationHistoryEntity(
                    eventId = request.eventId,
                    sourceIdentity = request.sourceIdentity,
                    packageName = request.content.packageName,
                    title = request.content.title,
                    body = request.content.body,
                    postedAt = request.postedAt,
                    updatedAt = request.updatedAt,
                    matchedRuleId = request.matchedRuleId,
                    matchedRulePattern = request.matchedRulePattern
                        ?.take(RuleMatcher.MAX_PATTERN_LENGTH),
                    outcome = request.outcome.name,
                    active = request.active,
                ),
                cutoff = request.updatedAt - historyRetentionMillis(),
                maximumEntries = UserPreferences.HISTORY_MAX_ENTRIES,
            )
            true
        }

    suspend fun deleteEvent(eventId: String) {
        HistoryOperationCoordinator.mutex.withLock { historyDao.deleteByEventId(eventId) }
    }

    suspend fun confirmRemoval(eventId: String, at: Long = System.currentTimeMillis()) {
        HistoryOperationCoordinator.mutex.withLock { historyDao.confirmRemoval(eventId, at) }
    }

    suspend fun closeEvent(eventId: String, at: Long = System.currentTimeMillis()) {
        HistoryOperationCoordinator.mutex.withLock { historyDao.closeEvent(eventId, at) }
    }

    suspend fun findActive(sourceIdentity: String): NotificationHistoryEntity? =
        HistoryOperationCoordinator.mutex.withLock { historyDao.findActive(sourceIdentity) }

    suspend fun reconcileActiveIdentities(activeIdentities: List<String>) {
        HistoryOperationCoordinator.mutex.withLock {
            if (activeIdentities.isEmpty()) historyDao.closeAllActive()
            else historyDao.closeMissingActive(activeIdentities)
        }
    }

    private suspend fun mutatePolicy(at: Long, mutation: suspend () -> Unit) {
        HistoryOperationCoordinator.mutex.withLock {
            val targetRevision = HistoryOperationCoordinator.beginMutation(at)
            try {
                mutation()
                val rules = exclusionDao.getEnabled().map(HistoryExclusionEntity::toDomain)
                val matcher = HistoryExclusionMatcher.compile(rules).getOrThrow()
                check(
                    HistoryOperationCoordinator.publishPolicy(
                        enabled = preferences.isHistoryEnabled(),
                        matcher = matcher,
                        expectedRevision = targetRevision,
                    ),
                )
            } catch (error: Throwable) {
                HistoryOperationCoordinator.invalidate(targetRevision)
                throw error
            }
        }
    }

    private suspend fun publishCurrentPolicy(): Boolean {
        val rules = exclusionDao.getEnabled().map(HistoryExclusionEntity::toDomain)
        val matcher = HistoryExclusionMatcher.compile(rules).getOrElse {
            HistoryOperationCoordinator.invalidate()
            return false
        }
        return HistoryOperationCoordinator.publishPolicy(preferences.isHistoryEnabled(), matcher)
    }

    private suspend fun deleteMatches(rule: HistoryExclusion) {
        val matcher = HistoryExclusionMatcher.compile(listOf(rule.copy(enabled = true))).getOrThrow()
        val ids = historyDao.getAll()
            .asSequence()
            .filter { entry ->
                matcher.matches(
                    NotificationContent(
                        packageName = entry.packageName,
                        title = entry.title,
                        body = entry.body,
                    ),
                )
            }
            .map(NotificationHistoryEntity::id)
            .toList()
        if (ids.isNotEmpty()) historyDao.deleteByIds(ids)
    }

    private fun historyRetentionMillis(): Long =
        UserPreferences.HISTORY_RETENTION_DAYS * 24L * 60L * 60L * 1_000L
}
