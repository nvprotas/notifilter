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
import io.github.nvprotas.notifilter.domain.RuleMatcher
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class FilteringNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val matcher = AtomicReference(RuleMatcher.EMPTY)
    private val pendingCancellations = ConcurrentHashMap<String, PendingCancellation>()

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
                matcher.set(RuleMatcher.compile(dao.getEnabled().map { it.toDomain() }))
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
                    matcher.set(RuleMatcher.compile(entities.map { it.toDomain() }))
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
                pendingCancellations.entries.removeIf { it.value.createdAtElapsed < cutoff }
            }
        }
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) {
        val posted = notification ?: return
        if (!preferences.isFilteringEnabled()) return
        if (!isSafeToFilter(posted)) return

        val content = NotificationTextExtractor.extract(
            packageName = posted.packageName,
            notification = posted.notification,
        )
        val decision = matcher.get().evaluate(content)
        if (decision.shouldBlock) {
            val eventTime = System.currentTimeMillis()
            val eventEpoch = JournalOperationCoordinator.currentEpoch()
            val shouldSaveJournal = preferences.shouldSaveJournal() &&
                JournalOperationCoordinator.canWrite(eventTime, eventEpoch)
            var createdPending = false
            val pending = if (shouldSaveJournal) {
                val candidate = PendingCancellation(
                    eventId = UUID.randomUUID().toString(),
                    epoch = eventEpoch,
                    createdAt = eventTime,
                    createdAtElapsed = SystemClock.elapsedRealtime(),
                )
                pendingCancellations.compute(posted.key) { _, existing ->
                    if (existing != null && existing.epoch == eventEpoch) {
                        existing
                    } else {
                        createdPending = true
                        candidate
                    }
                }
            } else {
                null
            }
            val journalSnapshot = if (createdPending) {
                NotificationTextExtractor.journalSnapshot(posted.notification)
            } else {
                JournalSnapshot.EMPTY
            }

            runCatching { cancelNotification(posted.key) }
                .onSuccess {
                    if (createdPending && pending != null) {
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
                    if (createdPending && pending != null) {
                        pendingCancellations.remove(posted.key, pending)
                    }
                    Log.w(TAG, "Unable to cancel notification", error)
                }
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
        if (reason != REASON_LISTENER_CANCEL) return

        val pending = pendingCancellations.remove(removed.key) ?: return
        pending.confirmed.set(true)
        JournalOperationCoordinator.scope.launch {
            runCatching {
                JournalOperationCoordinator.mutex.withLock {
                    journalDao.updateStatus(pending.eventId, JournalStatus.DISMISS_CONFIRMED.name)
                }
            }.onFailure { error -> Log.w(TAG, "Unable to confirm journal entry", error) }
        }
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
        val confirmed: AtomicBoolean = AtomicBoolean(false),
    )
}
