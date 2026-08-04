package io.github.nvprotas.notifilter.notification

import io.github.nvprotas.notifilter.domain.FilterDecision
import io.github.nvprotas.notifilter.domain.NotificationContent
import io.github.nvprotas.notifilter.domain.RuleMatcher
import java.util.concurrent.atomic.AtomicLong

internal object RuntimeNotificationFilter {
    fun blockedDecision(
        content: NotificationContent,
        eligibleForFiltering: Boolean,
        filteringEnabled: Boolean,
        matcher: RuleMatcher,
    ): FilterDecision? {
        if (!filteringEnabled || !eligibleForFiltering) return null
        return matcher.evaluate(content).takeIf(FilterDecision::shouldBlock)
    }
}

internal class RefilterGenerationTracker {
    private val generation = AtomicLong(0L)

    fun next(): Long = generation.incrementAndGet()

    fun isCurrent(expected: Long): Boolean = generation.get() == expected
}
