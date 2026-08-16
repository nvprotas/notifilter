package io.github.nvprotas.notifilter.notification

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class ActiveHistoryEvent(
    val eventId: String,
    val sourceIdentity: String,
    val postedAt: Long,
    val active: AtomicBoolean = AtomicBoolean(true),
)

internal class ActiveHistoryRegistry {
    private val events = ConcurrentHashMap<String, ActiveHistoryEvent>()

    fun getOrCreate(androidKey: String, postedAt: Long): ActiveHistoryEvent =
        events.computeIfAbsent(androidKey) {
            ActiveHistoryEvent(
                eventId = UUID.randomUUID().toString(),
                sourceIdentity = notificationSourceIdentity(androidKey),
                postedAt = postedAt,
            )
        }

    fun restore(androidKey: String, event: ActiveHistoryEvent): ActiveHistoryEvent =
        events.putIfAbsent(androidKey, event) ?: event

    fun remove(androidKey: String): ActiveHistoryEvent? =
        events.remove(androidKey)?.also { it.active.set(false) }

    fun clear() {
        events.values.forEach { it.active.set(false) }
        events.clear()
    }
}

internal fun notificationSourceIdentity(androidKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(androidKey.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
