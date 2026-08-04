package io.github.nvprotas.notifilter.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.nvprotas.notifilter.domain.FilterRule
import io.github.nvprotas.notifilter.domain.MatchTarget
import io.github.nvprotas.notifilter.domain.RuleAction
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuleRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: FilterRuleDao
    private lateinit var repository: RuleRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.filterRuleDao()
        repository = RuleRepository(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `replace removes old rules and preserves imported order`() = runTest {
        repository.save(rule(pattern = "old", createdAt = 1))

        val result = repository.replaceRules(
            listOf(
                rule(pattern = "first"),
                rule(pattern = "second"),
                rule(pattern = "third"),
            ),
        )

        assertEquals(RuleImportResult(imported = 3, skipped = 0), result)
        assertEquals(listOf("first", "second", "third"), repository.exportRules().map { it.pattern })
    }

    @Test
    fun `add preserves existing rules and skips exact duplicates`() = runTest {
        repository.save(rule(pattern = "existing", createdAt = 1))

        val result = repository.addImportedRules(
            listOf(
                rule(pattern = "existing", id = 99, createdAt = 99),
                rule(pattern = "new"),
                rule(pattern = "new"),
            ),
        )

        assertEquals(RuleImportResult(imported = 1, skipped = 2), result)
        assertEquals(setOf("existing", "new"), repository.exportRules().map { it.pattern }.toSet())
    }

    @Test
    fun `rules differing in functional fields are not duplicates`() = runTest {
        repository.save(rule(pattern = "sale", enabled = true, createdAt = 1))

        val result = repository.addImportedRules(
            listOf(rule(pattern = "sale", enabled = false)),
        )

        assertEquals(RuleImportResult(imported = 1, skipped = 0), result)
        assertEquals(2, repository.exportRules().size)
    }

    @Test
    fun `replace transaction restores old rules when an insert fails`() = runTest {
        dao.save(rule(pattern = "old", id = 10, createdAt = 1).toEntity())
        val conflicting = listOf(
            rule(pattern = "first", id = 1).toEntity(),
            rule(pattern = "second", id = 1).toEntity(),
        )

        val result = runCatching { dao.replaceAll(conflicting) }

        assertTrue(result.isFailure)
        assertEquals(listOf("old"), dao.getAll().map { it.pattern })
    }

    private fun rule(
        pattern: String,
        id: Long = 0,
        enabled: Boolean = true,
        createdAt: Long = 0,
    ) = FilterRule(
        id = id,
        packageName = "com.example",
        pattern = pattern,
        target = MatchTarget.ALL_TEXT,
        action = RuleAction.BLOCK,
        ignoreCase = true,
        enabled = enabled,
        createdAt = createdAt,
    )
}
