package io.github.nvprotas.notifilter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulePreviewEvaluatorTest {
    @Test
    fun `new enabled block draft predicts removal of matching active notification`() {
        val result = preview(
            draft = rule(pattern = "скидк[аи]"),
            samples = listOf(sample(body = "Скидка только сегодня")),
        )

        assertEquals(1, result.directMatchCount)
        assertEquals(1, result.removalCount)
        assertTrue(result.entries.single().willBeRemoved)
    }

    @Test
    fun `direct match respects package target and case sensitivity`() {
        val result = preview(
            draft = rule(
                packageName = "com.shop",
                pattern = "SALE",
                target = MatchTarget.TITLE,
                ignoreCase = false,
            ),
            samples = listOf(
                sample(key = "match", packageName = "com.shop", title = "SALE", body = "other"),
                sample(key = "body", packageName = "com.shop", title = "other", body = "SALE"),
                sample(key = "case", packageName = "com.shop", title = "sale", body = "other"),
                sample(key = "package", packageName = "com.chat", title = "SALE", body = "other"),
            ),
        )

        assertEquals(listOf("match"), result.entries.filter { it.directMatch }.map { it.sample.key })
        assertEquals(1, result.removalCount)
    }

    @Test
    fun `editing replaces saved rule instead of keeping both versions`() {
        val saved = rule(id = 7L, pattern = "старое")
        val result = preview(
            savedRules = listOf(saved),
            existingRule = saved,
            draft = saved.copy(pattern = "новое"),
            samples = listOf(sample(body = "старое сообщение")),
        )

        val entry = result.entries.single()
        assertTrue(entry.baselineDecision.shouldBlock)
        assertFalse(entry.proposedDecision.shouldBlock)
        assertFalse(entry.willBeRemoved)
    }

    @Test
    fun `allow exception overrides matching block draft`() {
        val allow = rule(id = 2L, pattern = "важное", action = RuleAction.ALLOW)
        val result = preview(
            savedRules = listOf(allow),
            draft = rule(pattern = "скидка"),
            samples = listOf(sample(body = "Важное: скидка на лекарства")),
        )

        val entry = result.entries.single()
        assertTrue(entry.directMatch)
        assertFalse(entry.proposedDecision.shouldBlock)
        assertEquals(allow.id, entry.proposedDecision.matchedRuleId)
        assertFalse(entry.willBeRemoved)
    }

    @Test
    fun `disabled draft reports direct match without predicted removal`() {
        val result = preview(
            draft = rule(pattern = "реклама", enabled = false),
            samples = listOf(sample(body = "реклама")),
        )

        val entry = result.entries.single()
        assertTrue(entry.directMatch)
        assertFalse(entry.proposedDecision.shouldBlock)
        assertFalse(entry.willBeRemoved)
    }

    @Test
    fun `protected notification is never predicted for removal`() {
        val result = preview(
            draft = rule(pattern = "реклама"),
            samples = listOf(sample(body = "реклама", eligible = false)),
        )

        val entry = result.entries.single()
        assertTrue(entry.directMatch)
        assertTrue(entry.proposedDecision.shouldBlock)
        assertFalse(entry.willBeRemoved)
    }

    @Test
    fun `global filtering off suppresses predicted removal`() {
        val result = preview(
            draft = rule(pattern = "реклама"),
            filteringEnabled = false,
            samples = listOf(sample(body = "реклама")),
        )

        assertTrue(result.entries.single().directMatch)
        assertEquals(0, result.removalCount)
    }

    @Test
    fun `unavailable active state produces unavailable preview`() {
        val result = RulePreviewEvaluator.evaluate(
            savedRules = emptyList(),
            existingRule = null,
            draft = rule(pattern = "реклама"),
            filteringEnabled = true,
            activeState = ActiveNotificationsState.Unavailable,
        )

        assertEquals(RulePreviewResult.Unavailable, result)
    }

    @Test
    fun `invalid regex produces validation result`() {
        val result = RulePreviewEvaluator.evaluate(
            savedRules = emptyList(),
            existingRule = null,
            draft = rule(pattern = "["),
            filteringEnabled = true,
            activeState = ActiveNotificationsState.Available(emptyList()),
        )

        assertTrue(result is RulePreviewResult.Invalid)
    }

    private fun preview(
        savedRules: List<FilterRule> = emptyList(),
        existingRule: FilterRule? = null,
        draft: FilterRule,
        filteringEnabled: Boolean = true,
        samples: List<ActiveNotificationSample>,
    ): RulePreviewResult.Available = RulePreviewEvaluator.evaluate(
        savedRules = savedRules,
        existingRule = existingRule,
        draft = draft,
        filteringEnabled = filteringEnabled,
        activeState = ActiveNotificationsState.Available(samples),
    ) as RulePreviewResult.Available

    private fun rule(
        id: Long = 0L,
        packageName: String? = null,
        pattern: String,
        target: MatchTarget = MatchTarget.ALL_TEXT,
        action: RuleAction = RuleAction.BLOCK,
        ignoreCase: Boolean = true,
        enabled: Boolean = true,
    ) = FilterRule(
        id = id,
        packageName = packageName,
        pattern = pattern,
        target = target,
        action = action,
        ignoreCase = ignoreCase,
        enabled = enabled,
    )

    private fun sample(
        key: String = "notification",
        packageName: String = "com.shop",
        title: String = "",
        body: String = "",
        eligible: Boolean = true,
    ) = ActiveNotificationSample(
        key = key,
        content = NotificationContent(
            packageName = packageName,
            title = title,
            body = body,
        ),
        postedAt = 100L,
        eligibleForFiltering = eligible,
    )
}
