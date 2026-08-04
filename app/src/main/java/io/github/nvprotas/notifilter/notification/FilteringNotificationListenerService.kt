package io.github.nvprotas.notifilter.notification

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.nvprotas.notifilter.data.AppDatabase
import io.github.nvprotas.notifilter.data.BlockedNotificationEntity
import io.github.nvprotas.notifilter.data.JournalOperationCoordinator
import io.github.nvprotas.notifilter.data.JournalStatus
import io.github.nvprotas.notifilter.data.UserPreferences
import io.github.nvprotas.notifilter.data.toDomain
import io.github.nvprotas.notifilter.domain.ActiveNotificationSample
import io.github.nvprotas.notifilter.domain.FilterDecision
import io.github.nvprotas.notifilter.domain.FilterRule
import io.github.nvprotas.notifilter.domain.RuleMatcher
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class FilteringNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val matcherState = AtomicReference(MatcherState(revision = 0L, matcher = RuleMatcher.EMPTY))
    private val matcherUpdateLock = Any()
    private val refilterGeneration = RefilterGenerationTracker()
    private val refilterJobLock = Any()
    private var refilterJob: Job? = null
    private val pendingCancellations = InFlightCancellationRegistry<PendingCancellation> {
        it.createdAtElapsed
    }
    private val listenerConnected = AtomicBoolean(false)

    private lateinit var preferences: UserPreferences
    private val journalDao by lazy {
        AppDatabase.get(applicationContext).blockedNotificationDao()
    }

    override fun onCreate() {
        super.onCreate()
        preferences = UserPreferences(applicationContext)
        JournalOperationCoordinator.initialize(preferences.shouldSaveJournal())

        val dao = AppDatabase.get(applicationContext).filterRuleDao()
        serviceScope.launch {
            try {
                updateMatcher(dao.getEnabled().map { it.toDomain() })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to load notification rules", error)
            }

            dao.observeEnabled()
                .retryWhen { error, attempt ->
                    if (error is CancellationException) throw error
                    Log.e(TAG, "Unable to observe notification rules", error)
                    delay(
                        (RULE_RETRY_INITIAL_MILLIS * (attempt + 1L))
                            .coerceAtMost(RULE_RETRY_MAX_MILLIS),
                    )
                    true
                }
                .collect { entities ->
                    updateMatcher(entities.map { it.toDomain() })
                }
        }

        serviceScope.launch {
            val now = System.currentTimeMillis()
            val cutoff = now - journalRetentionMillis()
            runCatching {
                JournalOperationCoordinator.mutex.withLock {
                    journalDao.deleteOlderThan(cutoff)
                    journalDao.trimToSize(UserPreferences.JOURNAL_MAX_ENTRIES)
                }
            }.onFailure { error -> Log.w(TAG, "Unable to prune notification journal", error) }
        }

        serviceScope.launch {
            while (true) {
                delay(PENDING_CLEANUP_INTERVAL_MILLIS)
                val cutoff = SystemClock.elapsedRealtime() - PENDING_EXPIRY_MILLIS
                pendingCancellations.pruneOlderThan(cutoff)
            }
        }

        serviceScope.launch {
            preferences.filteringEnabled.collect {
                scheduleRefilter()
            }
        }
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) {
        val posted = notification ?: return
        refreshActiveNotificationState(postedOverride = posted)
        evaluateAndCancel(posted)
    }

    private fun updateMatcher(rules: List<FilterRule>) {
        val generation = synchronized(matcherUpdateLock) {
            val previous = matcherState.get()
            matcherState.set(
                MatcherState(
                    revision = previous.revision + 1L,
                    matcher = RuleMatcher.compile(rules),
                ),
            )
            refilterGeneration.next()
        }
        launchRefilter(generation)
    }

    private fun scheduleRefilter() {
        val generation = synchronized(matcherUpdateLock) {
            refilterGeneration.next()
        }
        launchRefilter(generation)
    }

    private fun launchRefilter(generation: Long) {
        synchronized(refilterJobLock) {
            refilterJob?.cancel()
            refilterJob = serviceScope.launch {
                reFilterActiveNotifications(generation)
            }
        }
    }

    private suspend fun reFilterActiveNotifications(generation: Long) {
        if (!listenerConnected.get()) return
        val active = readActiveNotifications() ?: return
        publishActiveNotificationState(active)
        if (!preferences.isFilteringEnabled()) return

        active.forEach { posted ->
            currentCoroutineContext().ensureActive()
            if (!refilterGeneration.isCurrent(generation)) return
            evaluateAndCancel(posted, expectedGeneration = generation)
        }
    }

    private fun evaluateAndCancel(
        posted: StatusBarNotification,
        expectedGeneration: Long? = null,
    ) {
        val content = NotificationTextExtractor.extract(
            packageName = posted.packageName,
            notification = posted.notification,
        )
        synchronized(matcherUpdateLock) {
            if (expectedGeneration != null && !refilterGeneration.isCurrent(expectedGeneration)) return
            val decision = RuntimeNotificationFilter.blockedDecision(
                content = content,
                eligibleForFiltering = isSafeToFilter(posted),
                filteringEnabled = preferences.isFilteringEnabled(),
                matcher = matcherState.get().matcher,
            ) ?: return
            requestCancellation(posted, decision)
        }
    }

    private fun requestCancellation(
        posted: StatusBarNotification,
        decision: FilterDecision,
    ) {
        val eventTime = System.currentTimeMillis()
        val eventEpoch = JournalOperationCoordinator.currentEpoch()
        val shouldSaveJournal = preferences.shouldSaveJournal() &&
            JournalOperationCoordinator.canWrite(eventTime, eventEpoch)
        val pending = PendingCancellation(
            eventId = UUID.randomUUID().toString(),
            epoch = eventEpoch,
            createdAt = eventTime,
            createdAtElapsed = SystemClock.elapsedRealtime(),
            saveJournal = shouldSaveJournal,
        )
        if (!pendingCancellations.tryStart(posted.key, pending)) return

        val journalSnapshot = if (shouldSaveJournal) {
            NotificationTextExtractor.journalSnapshot(posted.notification)
        } else {
            JournalSnapshot.EMPTY
        }

        runCatching { cancelNotification(posted.key) }
            .onSuccess {
                if (shouldSaveJournal) {
                    saveToJournal(
                        packageName = posted.packageName,
                        pending = pending,
                        eventTime = pending.createdAt,
                        snapshot = journalSnapshot,
                        matchedRuleId = decision.matchedRuleId,
                        matchedRulePattern = decision.matchedRulePattern.orEmpty(),
                    )
                }
            }
            .onFailure { error ->
                pendingCancellations.abandon(posted.key, pending)
                Log.w(TAG, "Unable to cancel notification", error)
            }
    }

    private fun saveToJournal(
        packageName: String,
        pending: PendingCancellation,
        eventTime: Long,
        snapshot: JournalSnapshot,
        matchedRuleId: Long?,
        matchedRulePattern: String,
    ) {
        JournalOperationCoordinator.scope.launch {
            runCatching {
                JournalOperationCoordinator.mutex.withLock {
                    if (!preferences.shouldSaveJournal()) return@withLock
                    if (!JournalOperationCoordinator.canWrite(eventTime, pending.epoch)) {
                        return@withLock
                    }

                    val status = if (pending.confirmed.get()) {
                        JournalStatus.DISMISS_CONFIRMED
                    } else {
                        JournalStatus.DISMISS_REQUESTED
                    }
                    journalDao.insertAndPrune(
                        entry = BlockedNotificationEntity(
                            packageName = packageName,
                            title = snapshot.title,
                            body = snapshot.body,
                            blockedAt = eventTime,
                            matchedRuleId = matchedRuleId,
                            matchedRulePattern = matchedRulePattern.take(RuleMatcher.MAX_PATTERN_LENGTH),
                            notificationFingerprint = pending.eventId,
                            status = status.name,
                        ),
                        cutoff = eventTime - journalRetentionMillis(),
                        maximumEntries = UserPreferences.JOURNAL_MAX_ENTRIES,
                    )
                }
            }.onFailure { error -> Log.w(TAG, "Unable to write journal entry", error) }
        }
    }

    override fun onNotificationRemoved(
        notification: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int,
    ) {
        super.onNotificationRemoved(notification, rankingMap, reason)
        val removed = notification ?: return
        refreshActiveNotificationState(removedKey = removed.key)
        if (reason != REASON_LISTENER_CANCEL) return

        val pending = pendingCancellations.finish(removed.key) ?: return
        pending.confirmed.set(true)
        if (!pending.saveJournal) return
        JournalOperationCoordinator.scope.launch {
            runCatching {
                JournalOperationCoordinator.mutex.withLock {
                    journalDao.updateStatus(pending.eventId, JournalStatus.DISMISS_CONFIRMED.name)
                }
            }.onFailure { error -> Log.w(TAG, "Unable to confirm journal entry", error) }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected.set(true)
        refreshActiveNotificationState()
        scheduleRefilter()
    }

    override fun onListenerDisconnected() {
        listenerConnected.set(false)
        synchronized(matcherUpdateLock) {
            refilterGeneration.next()
        }
        synchronized(refilterJobLock) {
            refilterJob?.cancel()
            refilterJob = null
        }
        ActiveNotificationCoordinator.publishUnavailable()
        super.onListenerDisconnected()
    }

    private fun refreshActiveNotificationState(
        postedOverride: StatusBarNotification? = null,
        removedKey: String? = null,
    ) {
        if (!listenerConnected.get()) return
        val active = readActiveNotifications() ?: return
        val byKey = active.associateByTo(LinkedHashMap()) { it.key }
        postedOverride?.let { byKey[it.key] = it }
        removedKey?.let(byKey::remove)
        publishActiveNotificationState(byKey.values.toList())
    }

    private fun readActiveNotifications(): List<StatusBarNotification>? =
        runCatching { activeNotifications?.toList().orEmpty() }
            .onFailure { error -> Log.w(TAG, "Unable to read active notifications", error) }
            .getOrElse {
                ActiveNotificationCoordinator.publishUnavailable()
                return null
            }

    private fun publishActiveNotificationState(active: List<StatusBarNotification>) {
        val samples = active
            .sortedByDescending { it.postTime }
            .map { posted ->
                ActiveNotificationSample(
                    key = posted.key,
                    content = NotificationTextExtractor.extract(
                        packageName = posted.packageName,
                        notification = posted.notification,
                    ),
                    postedAt = posted.postTime,
                    eligibleForFiltering = isSafeToFilter(posted),
                )
            }
        ActiveNotificationCoordinator.publishAvailable(samples)
    }

    private fun isSafeToFilter(posted: StatusBarNotification): Boolean {
        if (posted.packageName == packageName) return false
        if (isSystemApplication(posted.packageName)) return false
        if (!posted.isClearable) return false

        val notification = posted.notification
        val protectedFlags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE
        if (notification.flags and protectedFlags != 0) return false
        if (notification.category == Notification.CATEGORY_CALL) return false
        if (notification.category == Notification.CATEGORY_ALARM) return false
        if (notification.category == Notification.CATEGORY_TRANSPORT) return false
        if (notification.fullScreenIntent != null) return false
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        return true
    }

    private fun isSystemApplication(targetPackageName: String): Boolean {
        val applicationInfo = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(targetPackageName, 0)
        }.getOrNull() ?: return true
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return applicationInfo.flags and systemFlags != 0
    }

    private fun journalRetentionMillis(): Long =
        UserPreferences.JOURNAL_RETENTION_DAYS * 24L * 60L * 60L * 1_000L

    override fun onDestroy() {
        listenerConnected.set(false)
        ActiveNotificationCoordinator.publishUnavailable()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NotifilterService"
        private const val RULE_RETRY_INITIAL_MILLIS = 1_000L
        private const val RULE_RETRY_MAX_MILLIS = 30_000L
        private const val PENDING_CLEANUP_INTERVAL_MILLIS = 60_000L
        private const val PENDING_EXPIRY_MILLIS = 5L * 60L * 1_000L
    }

    private data class PendingCancellation(
        val eventId: String,
        val epoch: Long,
        val createdAt: Long,
        val createdAtElapsed: Long,
        val saveJournal: Boolean,
        val confirmed: AtomicBoolean = AtomicBoolean(false),
    )

    private data class MatcherState(
        val revision: Long,
        val matcher: RuleMatcher,
    )
}
