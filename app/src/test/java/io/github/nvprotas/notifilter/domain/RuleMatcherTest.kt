package io.github.nvprotas.notifilter.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleMatcherTest {
    @Test
    fun `block rule matches a fragment ignoring case`() {
        val matcher = RuleMatcher.compile(
            listOf(rule(id = 1, pattern = "скидк[аи]")),
        )

        val decision = matcher.evaluate(notification(body = "Только сегодня СКИДКА 30%"))

        assertTrue(decision.shouldBlock)
        assertTrue(decision.matchedRuleId == 1L)
    }

    @Test
    fun `rule for another package does not match`() {
        val matcher = RuleMatcher.compile(
            listOf(rule(packageName = "com.shop", pattern = "акция")),
        )

        val decision = matcher.evaluate(
            notification(packageName = "com.chat", body = "акция"),
        )

        assertFalse(decision.shouldBlock)
    }

    @Test
    fun `title target does not inspect body`() {
        val matcher = RuleMatcher.compile(
            listOf(rule(pattern = "реклама", target = MatchTarget.TITLE)),
        )

        val decision = matcher.evaluate(
            notification(title = "Новое сообщение", body = "реклама"),
        )

        assertFalse(decision.shouldBlock)
    }

    @Test
    fun `allow exception wins over block rule`() {
        val matcher = RuleMatcher.compile(
            listOf(
                rule(id = 1, pattern = "скидка", action = RuleAction.BLOCK),
                rule(id = 2, pattern = "скидка на лекарства", action = RuleAction.ALLOW),
            ),
        )

        val decision = matcher.evaluate(notification(body = "Скидка на лекарства по рецепту"))

        assertFalse(decision.shouldBlock)
        assertTrue(decision.matchedRuleId == 2L)
    }

    @Test
    fun `disabled rule is ignored`() {
        val matcher = RuleMatcher.compile(
            listOf(rule(pattern = ".*", enabled = false)),
        )

        assertFalse(matcher.evaluate(notification(body = "anything")).shouldBlock)
    }

    @Test
    fun `case sensitive rule respects letter case`() {
        val matcher = RuleMatcher.compile(
            listOf(rule(pattern = "SALE", ignoreCase = false)),
        )

        assertFalse(matcher.evaluate(notification(body = "sale")).shouldBlock)
        assertTrue(matcher.evaluate(notification(body = "SALE")).shouldBlock)
    }

    @Test
    fun `invalid expression is rejected and skipped`() {
        assertNotNull(RuleMatcher.validationError("["))
        assertFalse(
            RuleMatcher.compile(listOf(rule(pattern = "[")))
                .evaluate(notification(body = "["))
                .shouldBlock,
        )
    }

    @Test
    fun `valid expression has no validation error`() {
        assertNull(RuleMatcher.validationError("(sale|discount)\\s+\\d+%"))
    }

    @Test
    fun `unsupported backreference is rejected`() {
        assertNotNull(RuleMatcher.validationError("(sale)\\1"))
    }

    private fun rule(
        id: Long = 1,
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

    private fun notification(
        packageName: String = "com.shop",
        title: String = "",
        body: String = "",
    ) = NotificationContent(
        packageName = packageName,
        title = title,
        body = body,
    )
}
