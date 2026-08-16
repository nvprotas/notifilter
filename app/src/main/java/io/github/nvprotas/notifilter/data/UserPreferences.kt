package io.github.nvprotas.notifilter.data

import android.content.Context
import android.content.SharedPreferences
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

    private val _historyEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_HISTORY_ENABLED, false),
    )
    val historyEnabled: StateFlow<Boolean> = _historyEnabled

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                KEY_FILTERING_ENABLED -> {
                    _filteringEnabled.value = sharedPreferences.getBoolean(
                        KEY_FILTERING_ENABLED,
                        false,
                    )
                }

                KEY_HISTORY_ENABLED -> {
                    _historyEnabled.value = sharedPreferences.getBoolean(
                        KEY_HISTORY_ENABLED,
                        false,
                    )
                }
            }
        }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    fun isFilteringEnabled(): Boolean =
        preferences.getBoolean(KEY_FILTERING_ENABLED, false)

    fun isHistoryEnabled(): Boolean =
        preferences.getBoolean(KEY_HISTORY_ENABLED, false)

    fun setFilteringEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_FILTERING_ENABLED, value).apply()
        _filteringEnabled.value = value
    }

    fun setHistoryEnabled(value: Boolean): Boolean {
        val saved = preferences.edit().putBoolean(KEY_HISTORY_ENABLED, value).commit()
        if (saved) _historyEnabled.value = value
        return saved
    }

    companion object {
        private const val FILE_NAME = "notifilter_preferences"
        private const val KEY_FILTERING_ENABLED = "filtering_enabled"
        private const val KEY_HISTORY_ENABLED = "history_enabled"

        const val HISTORY_RETENTION_DAYS = 30
        const val HISTORY_MAX_ENTRIES = 1_000
    }
}
