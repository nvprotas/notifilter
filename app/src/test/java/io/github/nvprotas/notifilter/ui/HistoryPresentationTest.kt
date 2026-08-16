package io.github.nvprotas.notifilter.ui

import io.github.nvprotas.notifilter.data.AppDatabase
import io.github.nvprotas.notifilter.data.HistoryOutcome
import io.github.nvprotas.notifilter.data.NotificationHistoryEntity
import io.github.nvprotas.notifilter.domain.HistoryExclusion
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPresentationTest {
    @Test
    fun `received history without matched rule has a neutral status`() {
        assertEquals("Получено", historyStatusLabel(entry(outcome = HistoryOutcome.RECEIVED)))
    }

    @Test
    fun `removal outcomes have distinct status labels`() {
        assertEquals(
            "Отправлен запрос на скрытие",
            historyStatusLabel(entry(outcome = HistoryOutcome.DISMISS_REQUESTED)),
        )
        assertEquals(
            "Android подтвердил скрытие",
            historyStatusLabel(entry(outcome = HistoryOutcome.DISMISS_CONFIRMED)),
        )
    }

    @Test
    fun `exclusion scope uses app label and global fallback`() {
        assertEquals(
            "Банк",
            historyExclusionScopeLabel(
                HistoryExclusion(packageName = "com.bank"),
                mapOf("com.bank" to "Банк"),
            ),
        )
        assertEquals("Все приложения", historyExclusionScopeLabel(HistoryExclusion(pattern = "код")))
    }

    @Test
    fun `seeded digit exclusion is presented as an ordinary global rule`() {
        val seeded = HistoryExclusion(pattern = AppDatabase.DEFAULT_OTP_PATTERN)

        assertEquals("Все приложения", historyExclusionScopeLabel(seeded))
        assertEquals(AppDatabase.DEFAULT_OTP_PATTERN, seeded.pattern)
    }

    @Test
    fun `history search covers label package content and nullable rule`() {
        val entries = listOf(
            entry(eventId = "one", packageName = "com.bank", title = "Баланс"),
            entry(
                eventId = "two",
                packageName = "com.shop",
                body = "Скидка",
                matchedRulePattern = "реклама",
            ),
        )
        val labels = mapOf("com.bank" to "Мой банк")

        assertEquals(listOf("one"), filterHistoryEntries(entries, "банк", labels).map { it.eventId })
        assertEquals(listOf("two"), filterHistoryEntries(entries, "скидка", labels).map { it.eventId })
        assertEquals(listOf("two"), filterHistoryEntries(entries, "реклама", labels).map { it.eventId })
        assertEquals(emptyList<String>(), filterHistoryEntries(entries, "нет", labels).map { it.eventId })
    }

    private fun entry(
        eventId: String = "event",
        packageName: String = "com.example",
        title: String = "",
        body: String = "",
        outcome: HistoryOutcome = HistoryOutcome.RECEIVED,
        matchedRulePattern: String? = null,
    ) = NotificationHistoryEntity(
        eventId = eventId,
        sourceIdentity = null,
        packageName = packageName,
        title = title,
        body = body,
        postedAt = 100L,
        updatedAt = 100L,
        matchedRuleId = null,
        matchedRulePattern = matchedRulePattern,
        outcome = outcome.name,
        active = false,
    )
}
