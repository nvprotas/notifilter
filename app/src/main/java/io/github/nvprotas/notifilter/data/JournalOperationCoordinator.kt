package io.github.nvprotas.notifilter.data

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

/** Serializes journal writes and destructive cleanup inside the app process. */
object JournalOperationCoordinator {
    val mutex = Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val initialized = AtomicBoolean(false)
    private val acceptingWrites = AtomicBoolean(false)
    private val writeBarrier = AtomicLong(0L)
    private val epoch = AtomicLong(0L)

    fun initialize(enabled: Boolean) {
        if (initialized.compareAndSet(false, true)) {
            acceptingWrites.set(enabled)
        }
    }

    fun suspendWrites(at: Long): Long {
        writeBarrier.accumulateAndGet(at) { current, candidate -> maxOf(current, candidate) }
        val newEpoch = epoch.incrementAndGet()
        acceptingWrites.set(false)
        return newEpoch
    }

    fun resumeWrites(enabled: Boolean, expectedEpoch: Long) {
        if (epoch.get() == expectedEpoch) acceptingWrites.set(enabled)
    }

    fun currentEpoch(): Long = epoch.get()

    fun isCurrent(expectedEpoch: Long): Boolean = epoch.get() == expectedEpoch

    fun canWrite(eventTime: Long, eventEpoch: Long): Boolean =
        acceptingWrites.get() &&
            eventEpoch == epoch.get() &&
            eventTime > writeBarrier.get()
}
