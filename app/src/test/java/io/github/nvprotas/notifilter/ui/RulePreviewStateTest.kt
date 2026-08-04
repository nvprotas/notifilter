package io.github.nvprotas.notifilter.ui

import io.github.nvprotas.notifilter.domain.ActiveNotificationSample
import io.github.nvprotas.notifilter.domain.ActiveNotificationsState
import io.github.nvprotas.notifilter.domain.FilterRule
import io.github.nvprotas.notifilter.domain.NotificationContent
import io.github.nvprotas.notifilter.domain.RuleAction
import io.github.nvprotas.notifilter.domain.RulePreviewEvaluator
import io.github.nvprotas.notifilter.domain.RulePreviewResult
import io.github.nvprotas.notifilter.notification.ActiveNotificationCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulePreviewStateTest {
    @Test
    fun `active-set change produces a new preview without mutating notifications`() {
        val sample = sample(body = "реклама")
        ActiveNotificationCoordinator.publishAvailable(listOf(sample))

        val first = preview(ActiveNotificationCoordinator.state.value)
        assertEquals(1, first.directMatchCount)
        assertEquals(1, first.removalCount)
        assertEquals(
            listOf(sample),
            (ActiveNotificationCoordinator.state.value as ActiveNotificationsState.Available)
                .notifications,
        )

        ActiveNotificationCoordinator.publishAvailable(emptyList())
        val second = preview(ActiveNotificationCoordinator.state.value)
        assertEquals(0, second.activeCount)
        ActiveNotificationCoordinator.publishUnavailable()
    }

    @Test
    fun `changing action and enabled state updates outcome while preserving direct match`() {
        val state = ActiveNotificationsState.Available(listOf(sample(body = "реклама")))

        val blocking = preview(state, FilterRule(pattern = "реклама"))
        val allowing = preview(
            state,
            FilterRule(pattern = "реклама", action = RuleAction.ALLOW),
        )
        val disabled = preview(
            state,
            FilterRule(pattern = "реклама", enabled = false),
        )

        assertTrue(blocking.entries.single().directMatch)
        assertEquals(1, blocking.removalCount)
        assertTrue(allowing.entries.single().directMatch)
        assertEquals(0, allowing.removalCount)
        assertTrue(disabled.entries.single().directMatch)
        assertFalse(disabled.entries.single().proposedDecision.shouldBlock)
    }

    private fun preview(
        state: ActiveNotificationsState,
        draft: FilterRule = FilterRule(pattern = "реклама"),
    ): RulePreviewResult.Available = RulePreviewEvaluator.evaluate(
        savedRules = emptyList(),
        existingRule = null,
        draft = draft,
        filteringEnabled = true,
        activeState = state,
    ) as RulePreviewResult.Available

    private fun sample(body: String) = ActiveNotificationSample(
        key = "active",
        content = NotificationContent(
            packageName = "com.shop",
            title = "",
            body = body,
        ),
        postedAt = 100L,
        eligibleForFiltering = true,
    )
}
