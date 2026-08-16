package io.github.nvprotas.notifilter.notification

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.nvprotas.notifilter.data.AppDatabase
import io.github.nvprotas.notifilter.data.HistoryCaptureAssessment
import io.github.nvprotas.notifilter.data.HistoryOperationCoordinator
import io.github.nvprotas.notifilter.data.HistoryOutcome
import io.github.nvprotas.notifilter.data.HistoryRecordRequest
import io.github.nvprotas.notifilter.data.HistoryRepository
import io.github.nvprotas.notifilter.data.UserPreferences
import io.github.nvprotas.notifilter.data.toDomain
import io.github.nvprotas.notifilter.domain.ActiveNotificationSample
import io.github.nvprotas.notifilter.domain.FilterDecision
import io.github.nvprotas.notifilter.domain.FilterRule
import io.github.nvprotas.notifilter.domain.NotificationContent
import io.github.nvprotas.notifilter.domain.RuleMatcher
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
    private val historyIdentitiesReady = AtomicBoolean(false)
    private val historyRegistry = ActiveHistoryRegistry()

    private lateinit var preferences: UserPreferences
    private lateinit var historyRepository: HistoryRepository

    override fun onCreate() {
        super.onCreate()
        preferences = UserPreferences(applicationContext)
        val database = AppDatabase.get(applicationContext)
        historyRepository = HistoryRepository(database, preferences)

        val ruleDao = database.filterRuleDao()
        serviceScope.launch {
            try {
                updateMatcher(ruleDao.getEnabled().map { it.toDomain() })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to load notification rules", error)
            }

            ruleDao.observeEnabled()
                .retryWhen { error, attempt ->
                    if (error is CancellationException) throw error
                    Log.e(TAG, "Unable to observe notification rules", error)
                    delay(retryDelay(attempt))
                    true
                }
                .collect { entities -> updateMatcher(entities.map { it.toDomain() }) }
        }

        serviceScope.launch {
            while (true) {
                try {
                    historyRepository.initializePolicy()
                    historyRepository.observePolicyChanges()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    HistoryOperationCoordinator.invalidate()
                    Log.e(TAG, "Unable to load notification history policy", error)
                    delay(RULE_RETRY_INITIAL_MILLIS)
                }
            }
        }

        serviceScope.launch {
            runCatching { historyRepository.pruneHistory() }
                .onFailure { error -> Log.w(TAG, "Unable to prune notification history", error) }
        }

        serviceScope.launch {
            while (true) {
                delay(PENDING_CLEANUP_INTERVAL_MILLIS)
                val cutoff = SystemClock.elapsedRealtime() - PENDING_EXPIRY_MILLIS
                pendingCancellations.pruneOlderThan(cutoff)
            }
        }

        serviceScope.launch {
            preferences.filteringEnabled.collect { scheduleRefilter() }
        }
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) {
        val posted = notification ?: return
        refreshActiveNotificationState(postedOverride = posted)
        processNotification(posted = posted, postedEvent = true)
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
        val generation = synchronized(matcherUpdateLock) { refilterGeneration.next() }
        launchRefilter(generation)
    }

    private fun launchRefilter(generation: Long) {
        synchronized(refilterJobLock) {
            refilterJob?.cancel()
            refilterJob = serviceScope.launch { reFilterActiveNotifications(generation) }
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
            processNotification(
                posted = posted,
                postedEvent = false,
                expectedGeneration = generation,
            )
        }
    }

    private fun processNotification(
        posted: StatusBarNotification,
        postedEvent: Boolean,
        expectedGeneration: Long? = null,
    ) {
        val eligible = isSafeToFilter(posted)
        val content = NotificationTextExtractor.extract(
            packageName = posted.packageName,
            notification = posted.notification,
        )
        val decision = synchronized(matcherUpdateLock) {
            if (expectedGeneration != null && !refilterGeneration.isCurrent(expectedGeneration)) {
                return
            }
            RuntimeNotificationFilter.blockedDecision(
                content = content,
                eligibleForFiltering = eligible,
                filteringEnabled = preferences.isFilteringEnabled(),
                matcher = matcherState.get().matcher,
            )
        }

        val lifecycle = if (eligible) {
            historyRegistry.getOrCreate(posted.key, posted.postTime)
        } else {
            historyRegistry.remove(posted.key)?.also { event -> deleteHistoryEvent(event.eventId) }
            null
        }

        if (decision != null && lifecycle != null) {
            requestCancellation(posted, content, decision, lifecycle)
        } else if (postedEvent && lifecycle != null) {
            recordReceivedOrDeleteExcluded(content, lifecycle)
        }
    }

    private fun recordReceivedOrDeleteExcluded(
        content: NotificationContent,
        lifecycle: ActiveHistoryEvent,
    ) {
        if (!historyIdentitiesReady.get()) return
        val eventTime = System.currentTimeMillis()
        when (val assessment = historyRepository.assessCapture(content, eventTime)) {
            HistoryCaptureAssessment.Excluded -> deleteHistoryEvent(lifecycle.eventId)
            is HistoryCaptureAssessment.Record -> {
                HistoryOperationCoordinator.scope.launch {
                    runCatching {
                        historyRepository.record(
                            request = HistoryRecordRequest(
                                eventId = lifecycle.eventId,
                                sourceIdentity = lifecycle.sourceIdentity,
                                content = content,
                                postedAt = lifecycle.postedAt,
                                updatedAt = eventTime,
                                matchedRuleId = null,
                                matchedRulePattern = null,
                                outcome = HistoryOutcome.RECEIVED,
                                active = lifecycle.active.get(),
                            ),
                            token = assessment.token,
                        )
                    }.onFailure { error -> Log.w(TAG, "Unable to write notification history", error) }
                }
            }

            HistoryCaptureAssessment.UnavailableOrDisabled -> Unit
        }
    }

    private fun requestCancellation(
        posted: StatusBarNotification,
        content: NotificationContent,
        decision: FilterDecision,
        lifecycle: ActiveHistoryEvent,
    ) {
        val eventTime = System.currentTimeMillis()
        val pending = PendingCancellation(
            eventId = lifecycle.eventId,
            createdAtElapsed = SystemClock.elapsedRealtime(),
        )
        if (!pendingCancellations.tryStart(posted.key, pending)) return

        runCatching { cancelNotification(posted.key) }
            .onSuccess {
                if (!historyIdentitiesReady.get()) return@onSuccess
                when (val assessment = historyRepository.assessCapture(content, eventTime)) {
                    HistoryCaptureAssessment.Excluded -> deleteHistoryEvent(lifecycle.eventId)
                    is HistoryCaptureAssessment.Record -> {
                        HistoryOperationCoordinator.scope.launch {
                            runCatching {
                                historyRepository.record(
                                    request = HistoryRecordRequest(
                                        eventId = lifecycle.eventId,
                                        sourceIdentity = lifecycle.sourceIdentity,
                                        content = content,
                                        postedAt = lifecycle.postedAt,
                                        updatedAt = eventTime,
                                        matchedRuleId = decision.matchedRuleId,
                                        matchedRulePattern = decision.matchedRulePattern,
                                        outcome = if (pending.confirmed.get()) {
                                            HistoryOutcome.DISMISS_CONFIRMED
                                        } else {
                                            HistoryOutcome.DISMISS_REQUESTED
                                        },
                                        active = lifecycle.active.get(),
                                    ),
                                    token = assessment.token,
                                )
                            }.onFailure { error ->
                                Log.w(TAG, "Unable to write notification history", error)
                            }
                        }
                    }

                    HistoryCaptureAssessment.UnavailableOrDisabled -> Unit
                }
            }
            .onFailure { error ->
                pendingCancellations.abandon(posted.key, pending)
                Log.w(TAG, "Unable to cancel notification", error)
            }
    }

    private fun deleteHistoryEvent(eventId: String) {
        HistoryOperationCoordinator.scope.launch {
            runCatching { historyRepository.deleteEvent(eventId) }
                .onFailure { error -> Log.w(TAG, "Unable to delete excluded history", error) }
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
        val lifecycle = historyRegistry.remove(removed.key)
        lifecycle?.let { event ->
            HistoryOperationCoordinator.scope.launch {
                runCatching { historyRepository.closeEvent(event.eventId) }
                    .onFailure { error -> Log.w(TAG, "Unable to close notification history event", error) }
            }
        }
        if (reason != REASON_LISTENER_CANCEL) return

        val pending = pendingCancellations.finish(removed.key) ?: return
        pending.confirmed.set(true)
        HistoryOperationCoordinator.scope.launch {
            runCatching { historyRepository.confirmRemoval(pending.eventId) }
                .onFailure { error -> Log.w(TAG, "Unable to confirm history removal", error) }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected.set(true)
        historyIdentitiesReady.set(false)
        val active = readActiveNotifications() ?: return
        publishActiveNotificationState(active)
        serviceScope.launch {
            reconcileHistory(active)
            historyIdentitiesReady.set(true)
            scheduleRefilter()
        }
    }

    override fun onListenerDisconnected() {
        listenerConnected.set(false)
        historyIdentitiesReady.set(false)
        historyRegistry.clear()
        synchronized(matcherUpdateLock) { refilterGeneration.next() }
        synchronized(refilterJobLock) {
            refilterJob?.cancel()
            refilterJob = null
        }
        ActiveNotificationCoordinator.publishUnavailable()
        super.onListenerDisconnected()
    }

    private suspend fun reconcileHistory(active: List<StatusBarNotification>) {
        val identities = active.associateWith { posted -> notificationSourceIdentity(posted.key) }
        runCatching { historyRepository.reconcileActiveIdentities(identities.values.toList()) }
            .onFailure { error -> Log.w(TAG, "Unable to reconcile notification history", error) }
        identities.forEach { (posted, identity) ->
            runCatching { historyRepository.findActive(identity) }
                .getOrNull()
                ?.let { entry ->
                    historyRegistry.restore(
                        posted.key,
                        ActiveHistoryEvent(
                            eventId = entry.eventId,
                            sourceIdentity = identity,
                            postedAt = entry.postedAt,
                        ),
                    )
                }
        }
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

    private fun retryDelay(attempt: Long): Long =
        (RULE_RETRY_INITIAL_MILLIS * (attempt + 1L)).coerceAtMost(RULE_RETRY_MAX_MILLIS)

    override fun onDestroy() {
        listenerConnected.set(false)
        historyIdentitiesReady.set(false)
        historyRegistry.clear()
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
        val createdAtElapsed: Long,
        val confirmed: AtomicBoolean = AtomicBoolean(false),
    )

    private data class MatcherState(
        val revision: Long,
        val matcher: RuleMatcher,
    )
}
