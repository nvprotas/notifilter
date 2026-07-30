package io.github.nvprotas.notifilter.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class UserPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    private val _filteringEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_FILTERING_ENABLED, false),
    )
    val filteringEnabled: StateFlow<Boolean> = _filteringEnabled

    private val _journalEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_JOURNAL_ENABLED, false),
    )
    val journalEnabled: StateFlow<Boolean> = _journalEnabled

    fun isFilteringEnabled(): Boolean =
        preferences.getBoolean(KEY_FILTERING_ENABLED, false)

    fun shouldSaveJournal(): Boolean =
        preferences.getBoolean(KEY_JOURNAL_ENABLED, false)

    fun setFilteringEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_FILTERING_ENABLED, value).apply()
        _filteringEnabled.value = value
    }

    fun setJournalEnabled(value: Boolean): Boolean {
        val saved = preferences.edit().putBoolean(KEY_JOURNAL_ENABLED, value).commit()
        if (saved) _journalEnabled.value = value
        return saved
    }

    companion object {
        private const val FILE_NAME = "notifilter_preferences"
        private const val KEY_FILTERING_ENABLED = "filtering_enabled"
        private const val KEY_JOURNAL_ENABLED = "journal_enabled"

        const val JOURNAL_RETENTION_DAYS = 30
        const val JOURNAL_MAX_ENTRIES = 1_000
    }
}
