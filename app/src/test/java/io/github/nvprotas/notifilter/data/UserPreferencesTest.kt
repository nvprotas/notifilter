package io.github.nvprotas.notifilter.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserPreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `filtering change is observed by another preferences instance`() {
        val writer = UserPreferences(context)
        val observer = UserPreferences(context)

        assertFalse(observer.filteringEnabled.value)
        writer.setFilteringEnabled(true)

        assertTrue(observer.filteringEnabled.value)
        assertTrue(observer.isFilteringEnabled())
    }

    @Test
    fun `journal change is observed by another preferences instance`() {
        val writer = UserPreferences(context)
        val observer = UserPreferences(context)

        assertFalse(observer.journalEnabled.value)
        assertTrue(writer.setJournalEnabled(true))

        assertTrue(observer.journalEnabled.value)
        assertTrue(observer.shouldSaveJournal())
    }

    companion object {
        private const val FILE_NAME = "notifilter_preferences"
    }
}
