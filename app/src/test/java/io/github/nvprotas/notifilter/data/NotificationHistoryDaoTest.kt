package io.github.nvprotas.notifilter.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationHistoryDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: NotificationHistoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.notificationHistoryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `same logical event updates content without duplicating`() = runTest {
        dao.upsertAndPrune(entry(eventId = "same", title = "Первый"), 0L, 10)
        dao.upsertAndPrune(entry(eventId = "same", title = "Обновлённый"), 0L, 10)

        val entries = dao.observeSince(0L).first()

        assertEquals(1, entries.size)
        assertEquals("Обновлённый", entries.single().title)
    }

    @Test
    fun `received update never downgrades confirmed outcome or matched rule`() = runTest {
        dao.upsertAndPrune(
            entry(
                eventId = "event",
                outcome = HistoryOutcome.DISMISS_CONFIRMED,
                matchedRuleId = 7L,
                matchedRulePattern = "реклама",
            ),
            0L,
            10,
        )
        dao.upsertAndPrune(
            entry(eventId = "event", title = "Новый текст", outcome = HistoryOutcome.RECEIVED),
            0L,
            10,
        )

        val stored = dao.observeSince(0L).first().single()

        assertEquals(HistoryOutcome.DISMISS_CONFIRMED.name, stored.outcome)
        assertEquals(7L, stored.matchedRuleId)
        assertEquals("реклама", stored.matchedRulePattern)
        assertEquals("Новый текст", stored.title)
    }

    @Test
    fun `later reuse of source identity remains a separate event`() = runTest {
        dao.insert(entry(eventId = "first", sourceIdentity = "same-key", postedAt = 100L))
        dao.insert(entry(eventId = "second", sourceIdentity = "same-key", postedAt = 200L))

        val entries = dao.observeSince(0L).first()

        assertEquals(listOf("second", "first"), entries.map(NotificationHistoryEntity::eventId))
    }

    @Test
    fun `nullable matched rule metadata is retained`() = runTest {
        dao.insert(entry(eventId = "allowed"))

        val stored = dao.observeSince(0L).first().single()

        assertNull(stored.matchedRuleId)
        assertNull(stored.matchedRulePattern)
        assertEquals(HistoryOutcome.RECEIVED.name, stored.outcome)
    }

    @Test
    fun `individual entry can be deleted without affecting the rest`() = runTest {
        dao.insert(entry(eventId = "keep", postedAt = 100L))
        dao.insert(entry(eventId = "delete", postedAt = 200L))
        val selected = dao.observeSince(0L).first().first { it.eventId == "delete" }

        dao.delete(selected)

        assertEquals(
            listOf("keep"),
            dao.observeSince(0L).first().map(NotificationHistoryEntity::eventId),
        )
    }

    @Test
    fun `listener confirmation updates the existing removal row`() = runTest {
        dao.insert(
            entry(
                eventId = "blocked",
                outcome = HistoryOutcome.DISMISS_REQUESTED,
                matchedRuleId = 7L,
                matchedRulePattern = "реклама",
            ),
        )

        dao.confirmRemoval(eventId = "blocked", updatedAt = 200L)

        val stored = dao.observeSince(0L).first().single()
        assertEquals(HistoryOutcome.DISMISS_CONFIRMED.name, stored.outcome)
        assertEquals(7L, stored.matchedRuleId)
        assertEquals("реклама", stored.matchedRulePattern)
    }

    @Test
    fun `clear removes old rows without deleting a later event`() = runTest {
        dao.insert(entry(eventId = "stale", postedAt = 100L))
        dao.clearHistory(at = 150L)
        dao.upsertAndPrune(entry(eventId = "fresh", postedAt = 200L), 0L, 10)

        val entries = dao.observeSince(0L).first()

        assertEquals(listOf("fresh"), entries.map(NotificationHistoryEntity::eventId))
    }

    @Test
    fun `insert prunes expired entries and caps history size`() = runTest {
        dao.insert(entry(eventId = "expired", postedAt = 10L))
        dao.insert(entry(eventId = "recent-1", postedAt = 100L))
        dao.upsertAndPrune(entry(eventId = "recent-2", postedAt = 200L), 50L, 2)

        val entries = dao.observeSince(0L).first()

        assertEquals(listOf("recent-2", "recent-1"), entries.map(NotificationHistoryEntity::eventId))
    }

    private fun entry(
        eventId: String,
        sourceIdentity: String? = null,
        title: String = eventId,
        postedAt: Long = 100L,
        outcome: HistoryOutcome = HistoryOutcome.RECEIVED,
        matchedRuleId: Long? = null,
        matchedRulePattern: String? = null,
    ) = NotificationHistoryEntity(
        eventId = eventId,
        sourceIdentity = sourceIdentity,
        packageName = "com.example",
        title = title,
        body = "Текст",
        postedAt = postedAt,
        updatedAt = postedAt,
        matchedRuleId = matchedRuleId,
        matchedRulePattern = matchedRulePattern,
        outcome = outcome.name,
        active = true,
    )
}
