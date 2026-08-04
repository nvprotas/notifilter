package io.github.nvprotas.notifilter.notification

import io.github.nvprotas.notifilter.domain.FilterRule
import io.github.nvprotas.notifilter.domain.ActiveNotificationSample
import io.github.nvprotas.notifilter.domain.ActiveNotificationsState
import io.github.nvprotas.notifilter.domain.NotificationContent
import io.github.nvprotas.notifilter.domain.RuleAction
import io.github.nvprotas.notifilter.domain.RuleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilteringServiceLogicTest {
    @Test
    fun `existing active push is selected after matching block rule is committed`() {
        val decision = RuntimeNotificationFilter.blockedDecision(
            content = content("Реклама: скидка 30%"),
            eligibleForFiltering = true,
            filteringEnabled = true,
            matcher = matcher(rule(id = 1L, pattern = "скидка")),
        )

        assertNotNull(decision)
        assertEquals(1L, decision?.matchedRuleId)
    }

    @Test
    fun `protected active push is not selected`() {
        val decision = RuntimeNotificationFilter.blockedDecision(
            content = content("реклама"),
            eligibleForFiltering = false,
            filteringEnabled = true,
            matcher = matcher(rule(pattern = "реклама")),
        )

        assertNull(decision)
    }

    @Test
    fun `allow rule prevents selection during active refilter`() {
        val decision = RuntimeNotificationFilter.blockedDecision(
            content = content("Важная скидка"),
            eligibleForFiltering = true,
            filteringEnabled = true,
            matcher = matcher(
                rule(id = 1L, pattern = "скидка"),
                rule(id = 2L, pattern = "важная", action = RuleAction.ALLOW),
            ),
        )

        assertNull(decision)
    }

    @Test
    fun `disabled global filtering prevents selection`() {
        val decision = RuntimeNotificationFilter.blockedDecision(
            content = content("реклама"),
            eligibleForFiltering = true,
            filteringEnabled = false,
            matcher = matcher(rule(pattern = "реклама")),
        )

        assertNull(decision)
    }

    @Test
    fun `new generation supersedes an in-progress refilter pass`() {
        val generations = RefilterGenerationTracker()
        val first = generations.next()
        val second = generations.next()

        assertFalse(generations.isCurrent(first))
        assertTrue(generations.isCurrent(second))
    }

    @Test
    fun `listener reconnect replaces unavailable preview with active samples`() {
        ActiveNotificationCoordinator.publishUnavailable()
        val sample = ActiveNotificationSample(
            key = "active",
            content = content("push"),
            postedAt = 100L,
            eligibleForFiltering = true,
        )

        ActiveNotificationCoordinator.publishAvailable(listOf(sample))

        val state = ActiveNotificationCoordinator.state.value as ActiveNotificationsState.Available
        assertEquals(listOf(sample), state.notifications)
        ActiveNotificationCoordinator.publishUnavailable()
        assertEquals(ActiveNotificationsState.Unavailable, ActiveNotificationCoordinator.state.value)
    }

    @Test
    fun `posted callback and refilter share only one logical cancellation`() {
        val registry = InFlightCancellationRegistry<Operation>(Operation::createdAt)
        val posted = Operation(id = "posted", createdAt = 10L)
        val refilter = Operation(id = "refilter", createdAt = 11L)

        assertTrue(registry.tryStart("same-key", posted))
        assertFalse(registry.tryStart("same-key", refilter))
        assertEquals(posted, registry.finish("same-key"))
        assertNull(registry.finish("same-key"))
    }

    @Test
    fun `disappearing notification and timeout cleanup are harmless`() {
        val registry = InFlightCancellationRegistry<Operation>(Operation::createdAt)

        assertNull(registry.finish("already-gone"))
        assertTrue(registry.tryStart("stale", Operation("stale", createdAt = 10L)))
        registry.pruneOlderThan(20L)
        assertTrue(registry.tryStart("stale", Operation("fresh", createdAt = 30L)))
    }

    private fun matcher(vararg rules: FilterRule): RuleMatcher =
        RuleMatcher.compile(rules.toList())

    private fun rule(
        id: Long = 1L,
        pattern: String,
        action: RuleAction = RuleAction.BLOCK,
    ) = FilterRule(id = id, pattern = pattern, action = action)

    private fun content(body: String) = NotificationContent(
        packageName = "com.shop",
        title = "",
        body = body,
    )

    private data class Operation(
        val id: String,
        val createdAt: Long,
    )
}
