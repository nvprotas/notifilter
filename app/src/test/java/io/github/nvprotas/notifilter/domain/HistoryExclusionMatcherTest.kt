package io.github.nvprotas.notifilter.domain

import io.github.nvprotas.notifilter.data.AppDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExclusionMatcherTest {
    @Test
    fun `application-only rule excludes every notification from its package`() {
        val matcher = matcher(HistoryExclusion(packageName = "com.bank"))

        assertTrue(matcher.matches(content(packageName = "com.bank", body = "Баланс")))
        assertFalse(matcher.matches(content(packageName = "com.chat", body = "Баланс")))
    }

    @Test
    fun `global content rule applies to every package`() {
        val matcher = matcher(HistoryExclusion(pattern = "секрет"))

        assertTrue(matcher.matches(content(packageName = "com.one", body = "Секрет")))
        assertTrue(matcher.matches(content(packageName = "com.two", body = "секрет")))
    }

    @Test
    fun `combined rule requires package and content`() {
        val matcher = matcher(HistoryExclusion(packageName = "com.bank", pattern = "код"))

        assertTrue(matcher.matches(content(packageName = "com.bank", body = "Код")))
        assertFalse(matcher.matches(content(packageName = "com.bank", body = "Баланс")))
        assertFalse(matcher.matches(content(packageName = "com.chat", body = "Код")))
    }

    @Test
    fun `target and case sensitivity are honored`() {
        val titleMatcher = matcher(
            HistoryExclusion(pattern = "SECRET", target = MatchTarget.TITLE, ignoreCase = false),
        )

        assertTrue(titleMatcher.matches(content(title = "SECRET", body = "обычно")))
        assertFalse(titleMatcher.matches(content(title = "secret", body = "SECRET")))
    }

    @Test
    fun `empty and invalid rules are rejected`() {
        assertNotNull(HistoryExclusionValidator.validationError(HistoryExclusion()))
        assertNotNull(HistoryExclusionValidator.validationError(HistoryExclusion(pattern = "(")))
        assertTrue(HistoryExclusionMatcher.compile(listOf(HistoryExclusion())).isFailure)
    }

    @Test
    fun `default numeric rule matches standalone four to six digits only`() {
        val matcher = matcher(HistoryExclusion(pattern = AppDatabase.DEFAULT_OTP_PATTERN))

        assertTrue(matcher.matches(content(body = "Код 4821")))
        assertTrue(matcher.matches(content(title = "123456")))
        assertFalse(matcher.matches(content(body = "123")))
        assertFalse(matcher.matches(content(body = "1234567")))
        assertFalse(matcher.matches(content(body = "номер A1234567B")))
    }

    private fun matcher(vararg rules: HistoryExclusion): HistoryExclusionMatcher =
        HistoryExclusionMatcher.compile(rules.toList()).getOrThrow()

    private fun content(
        packageName: String = "com.example",
        title: String = "",
        body: String = "",
    ) = NotificationContent(packageName = packageName, title = title, body = body)
}
