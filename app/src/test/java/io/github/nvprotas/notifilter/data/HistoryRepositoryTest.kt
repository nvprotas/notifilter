package io.github.nvprotas.notifilter.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.nvprotas.notifilter.domain.HistoryExclusion
import io.github.nvprotas.notifilter.domain.NotificationContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HistoryRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var preferences: UserPreferences
    private lateinit var repository: HistoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        HistoryOperationCoordinator.resetForTest()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = UserPreferences(context)
        preferences.setHistoryEnabled(true)
        repository = HistoryRepository(database, preferences)
    }

    @After
    fun tearDown() {
        database.close()
        HistoryOperationCoordinator.resetForTest()
    }

    @Test
    fun `application exclusion deletes matches and rejects stale capture`() = runTest {
        repository.initializePolicy()
        val stored = request("stored", "com.bank", "Баланс", 100L)
        val storedToken = recordToken(stored)
        assertTrue(repository.record(stored, storedToken))

        val stale = request("stale", "com.bank", "Поздняя запись", 101L)
        val staleToken = recordToken(stale)
        repository.saveExclusion(HistoryExclusion(packageName = "com.bank"))

        assertFalse(repository.record(stale, staleToken))
        assertTrue(repository.observeHistory(0L).first().isEmpty())
    }

    @Test
    fun `regex exclusion removes matching rows only`() = runTest {
        repository.initializePolicy()
        val secret = request("secret", "com.chat", "секрет", 100L)
        val ordinary = request("ordinary", "com.chat", "привет", 101L)
        repository.record(secret, recordToken(secret))
        repository.record(ordinary, recordToken(ordinary))

        repository.saveExclusion(HistoryExclusion(pattern = "секрет"))

        assertEquals(
            listOf("ordinary"),
            repository.observeHistory(0L).first().map(NotificationHistoryEntity::eventId),
        )
    }

    @Test
    fun `disabling or deleting exclusion does not restore deleted rows`() = runTest {
        repository.initializePolicy()
        val request = request("deleted", "com.bank", "Баланс", 100L)
        repository.record(request, recordToken(request))
        repository.saveExclusion(HistoryExclusion(packageName = "com.bank"))
        val saved = repository.exclusions.first().single()

        repository.saveExclusion(saved.copy(enabled = false))
        assertTrue(repository.observeHistory(0L).first().isEmpty())

        repository.deleteExclusion(saved.copy(enabled = false))
        assertTrue(repository.observeHistory(0L).first().isEmpty())
    }

    @Test
    fun `clear keeps an event assessed after the clear barrier`() = runTest {
        repository.initializePolicy()
        val old = request("old", "com.chat", "Старое", 100L)
        repository.record(old, recordToken(old))
        val clearedAt = System.currentTimeMillis()

        repository.clearHistory(clearedAt)
        val fresh = request("fresh", "com.chat", "Новое", clearedAt + 1L)
        assertTrue(repository.record(fresh, recordToken(fresh)))

        assertEquals(
            listOf("fresh"),
            repository.observeHistory(0L).first().map(NotificationHistoryEntity::eventId),
        )
    }

    @Test
    fun `refilter outcome updates an existing logical row without duplication`() = runTest {
        repository.initializePolicy()
        val received = request("same", "com.chat", "Реклама", 100L)
        repository.record(received, recordToken(received))
        val blocked = received.copy(
            updatedAt = 101L,
            matchedRuleId = 4L,
            matchedRulePattern = "реклама",
            outcome = HistoryOutcome.DISMISS_REQUESTED,
        )

        repository.record(blocked, recordToken(blocked))

        val entries = repository.observeHistory(0L).first()
        assertEquals(1, entries.size)
        assertEquals(HistoryOutcome.DISMISS_REQUESTED.name, entries.single().outcome)
        assertEquals(4L, entries.single().matchedRuleId)
    }

    private fun recordToken(request: HistoryRecordRequest): HistoryWriteToken {
        val assessment = repository.assessCapture(request.content, request.updatedAt)
        return (assessment as HistoryCaptureAssessment.Record).token
    }

    private fun request(
        eventId: String,
        packageName: String,
        body: String,
        at: Long,
    ) = HistoryRecordRequest(
        eventId = eventId,
        sourceIdentity = "source-$eventId",
        content = NotificationContent(packageName, title = "", body = body),
        postedAt = at,
        updatedAt = at,
        matchedRuleId = null,
        matchedRulePattern = null,
        outcome = HistoryOutcome.RECEIVED,
        active = true,
    )

    companion object {
        private const val PREFERENCES_FILE = "notifilter_preferences"
    }
}
