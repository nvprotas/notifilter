package io.github.nvprotas.notifilter.notification

import java.util.concurrent.ConcurrentHashMap

internal class InFlightCancellationRegistry<T>(
    private val createdAtElapsed: (T) -> Long,
) {
    private val entries = ConcurrentHashMap<String, T>()

    fun tryStart(key: String, operation: T): Boolean =
        entries.putIfAbsent(key, operation) == null

    fun finish(key: String): T? = entries.remove(key)

    fun abandon(key: String, operation: T): Boolean = entries.remove(key, operation)

    fun pruneOlderThan(cutoffElapsed: Long) {
        entries.entries.removeIf { createdAtElapsed(it.value) < cutoffElapsed }
    }
}
