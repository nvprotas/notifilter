package io.github.nvprotas.notifilter.data

import io.github.nvprotas.notifilter.domain.HistoryExclusionMatcher
import io.github.nvprotas.notifilter.domain.NotificationContent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

data class HistoryWriteToken internal constructor(
    val eventTime: Long,
    internal val revision: Long,
)

sealed interface HistoryCaptureAssessment {
    data object UnavailableOrDisabled : HistoryCaptureAssessment
    data object Excluded : HistoryCaptureAssessment
    data class Record(val token: HistoryWriteToken) : HistoryCaptureAssessment
}

internal data class HistoryCapturePolicy(
    val revision: Long,
    val enabled: Boolean,
    val matcher: HistoryExclusionMatcher,
)

/** Serializes history writes, policy replacement, exclusions, and destructive cleanup. */
object HistoryOperationCoordinator {
    val mutex = Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val revision = AtomicLong(0L)
    private val writeBarrier = AtomicLong(0L)
    private val acceptingWrites = AtomicBoolean(false)
    private val policy = AtomicReference<HistoryCapturePolicy?>(null)

    fun assess(content: NotificationContent, eventTime: Long): HistoryCaptureAssessment {
        val current = policy.get() ?: return HistoryCaptureAssessment.UnavailableOrDisabled
        if (!current.enabled || !acceptingWrites.get()) {
            return HistoryCaptureAssessment.UnavailableOrDisabled
        }
        if (current.matcher.matches(content)) return HistoryCaptureAssessment.Excluded
        return HistoryCaptureAssessment.Record(
            HistoryWriteToken(eventTime = eventTime, revision = current.revision),
        )
    }

    fun beginMutation(at: Long): Long {
        writeBarrier.accumulateAndGet(at) { current, candidate -> maxOf(current, candidate) }
        acceptingWrites.set(false)
        return revision.incrementAndGet()
    }

    fun publishPolicy(
        enabled: Boolean,
        matcher: HistoryExclusionMatcher,
        expectedRevision: Long? = null,
    ): Boolean {
        val targetRevision = if (expectedRevision == null) {
            revision.incrementAndGet()
        } else {
            if (revision.get() != expectedRevision) return false
            expectedRevision
        }
        policy.set(
            HistoryCapturePolicy(
                revision = targetRevision,
                enabled = enabled,
                matcher = matcher,
            ),
        )
        acceptingWrites.set(enabled)
        return true
    }

    fun invalidate(expectedRevision: Long? = null) {
        if (expectedRevision != null && revision.get() != expectedRevision) return
        acceptingWrites.set(false)
        policy.set(null)
    }

    fun canWrite(token: HistoryWriteToken): Boolean {
        val current = policy.get() ?: return false
        return acceptingWrites.get() &&
            current.enabled &&
            current.revision == token.revision &&
            revision.get() == token.revision &&
            token.eventTime > writeBarrier.get()
    }

    internal fun currentPolicy(): HistoryCapturePolicy? = policy.get()

    internal fun resetForTest() {
        revision.set(0L)
        writeBarrier.set(0L)
        acceptingWrites.set(false)
        policy.set(null)
    }
}
