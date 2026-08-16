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
    fun `history change is observed by another preferences instance`() {
        val writer = UserPreferences(context)
        val observer = UserPreferences(context)

        assertFalse(observer.historyEnabled.value)
        assertTrue(writer.setHistoryEnabled(true))

        assertTrue(observer.historyEnabled.value)
        assertTrue(observer.isHistoryEnabled())
    }

    @Test
    fun `legacy journal consent does not enable broader history`() {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("journal_enabled", true)
            .commit()

        val preferences = UserPreferences(context)

        assertFalse(preferences.historyEnabled.value)
        assertFalse(preferences.isHistoryEnabled())
    }

    companion object {
        private const val FILE_NAME = "notifilter_preferences"
    }
}
