package io.github.nvprotas.notifilter.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BlockedNotificationDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: BlockedNotificationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.blockedNotificationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `same event is idempotent and never overwrites its first snapshot`() = runTest {
        dao.insert(entry(fingerprint = "same", title = "Первый"))
        dao.insert(entry(fingerprint = "same", title = "Обновлённый"))

        val entries = dao.observeSince(0L).first()

        assertEquals(1, entries.size)
        assertEquals("Первый", entries.single().title)
    }

    @Test
    fun `different events with a reused Android key remain separate`() = runTest {
        dao.insert(entry(fingerprint = "same-key-at-100", blockedAt = 100L))
        dao.insert(entry(fingerprint = "same-key-at-200", blockedAt = 200L))

        val entries = dao.observeSince(0L).first()

        assertEquals(2, entries.size)
    }

    @Test
    fun `confirmed status is never downgraded by an idempotent insert`() = runTest {
        dao.insertAndPrune(
            entry = entry(fingerprint = "event").copy(
                status = JournalStatus.DISMISS_CONFIRMED.name,
            ),
            cutoff = 0L,
            maximumEntries = 10,
        )
        dao.insertAndPrune(
            entry = entry(fingerprint = "event").copy(
                status = JournalStatus.DISMISS_REQUESTED.name,
            ),
            cutoff = 0L,
            maximumEntries = 10,
        )

        val stored = dao.observeSince(0L).first().single()

        assertEquals(JournalStatus.DISMISS_CONFIRMED.name, stored.status)
    }

    @Test
    fun `clear removes old rows without deleting a later event`() = runTest {
        dao.insert(entry(fingerprint = "stale", blockedAt = 100L))
        dao.clearJournal(at = 150L)
        dao.insertAndPrune(
            entry = entry(fingerprint = "fresh", blockedAt = 200L),
            cutoff = 0L,
            maximumEntries = 10,
        )

        val entries = dao.observeSince(0L).first()

        assertEquals(listOf("fresh"), entries.map { it.notificationFingerprint })
    }

    @Test
    fun `insert prunes expired entries and caps journal size`() = runTest {
        dao.insert(entry(fingerprint = "expired", blockedAt = 10L))
        dao.insert(entry(fingerprint = "recent-1", blockedAt = 100L))
        dao.insertAndPrune(
            entry = entry(fingerprint = "recent-2", blockedAt = 200L),
            cutoff = 50L,
            maximumEntries = 2,
        )

        val entries = dao.observeSince(0L).first()

        assertEquals(listOf("recent-2", "recent-1"), entries.map { it.notificationFingerprint })
    }

    private fun entry(
        fingerprint: String,
        title: String = fingerprint,
        blockedAt: Long = 100L,
    ) = BlockedNotificationEntity(
        packageName = "com.example",
        title = title,
        body = "Текст",
        blockedAt = blockedAt,
        matchedRuleId = 1L,
        matchedRulePattern = "реклама",
        notificationFingerprint = fingerprint,
        status = JournalStatus.DISMISS_REQUESTED.name,
    )
}
