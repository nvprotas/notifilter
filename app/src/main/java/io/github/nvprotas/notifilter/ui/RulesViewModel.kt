package io.github.nvprotas.notifilter.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nvprotas.notifilter.data.AppDatabase
import io.github.nvprotas.notifilter.data.BlockedNotificationEntity
import io.github.nvprotas.notifilter.data.JournalOperationCoordinator
import io.github.nvprotas.notifilter.data.RuleRepository
import io.github.nvprotas.notifilter.data.UserPreferences
import io.github.nvprotas.notifilter.domain.FilterRule
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RulesViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.get(application)
    private val repository = RuleRepository(
        database.filterRuleDao(),
    )
    private val journalDao = database.blockedNotificationDao()
    private val preferences = UserPreferences(application)

    init {
        JournalOperationCoordinator.initialize(preferences.shouldSaveJournal())
    }

    val rules: StateFlow<List<FilterRule>> = repository.rules.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = emptyList(),
    )
    val filteringEnabled: StateFlow<Boolean> = preferences.filteringEnabled
    val journalEnabled: StateFlow<Boolean> = preferences.journalEnabled
    val journalEntries: StateFlow<List<BlockedNotificationEntity>> =
        journalDao.observeSince(System.currentTimeMillis() - journalRetentionMillis()).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = emptyList(),
        )

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    init {
        loadInstalledApps()
        pruneJournal()
    }

    fun save(rule: FilterRule) {
        viewModelScope.launch {
            runCatching { repository.save(rule) }
                .onFailure { _messages.emit("Не удалось сохранить правило") }
        }
    }

    fun delete(rule: FilterRule) {
        viewModelScope.launch {
            runCatching { repository.delete(rule) }
                .onFailure { _messages.emit("Не удалось удалить правило") }
        }
    }

    fun setRuleEnabled(rule: FilterRule, enabled: Boolean) {
        save(rule.copy(enabled = enabled))
    }

    fun setFilteringEnabled(enabled: Boolean) {
        preferences.setFilteringEnabled(enabled)
    }

    fun setJournalEnabled(enabled: Boolean) {
        val changedAt = System.currentTimeMillis()
        val operationEpoch = JournalOperationCoordinator.suspendWrites(changedAt)
        JournalOperationCoordinator.scope.launch {
            try {
                JournalOperationCoordinator.mutex.withLock {
                    if (!JournalOperationCoordinator.isCurrent(operationEpoch)) {
                        return@withLock
                    }
                    check(preferences.setJournalEnabled(enabled))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _messages.emit("Не удалось изменить настройку журнала")
            } finally {
                JournalOperationCoordinator.resumeWrites(
                    enabled = preferences.shouldSaveJournal(),
                    expectedEpoch = operationEpoch,
                )
            }
        }
    }

    fun deleteJournalEntry(entry: BlockedNotificationEntity) {
        viewModelScope.launch {
            runCatching { journalDao.delete(entry) }
                .onFailure { _messages.emit("Не удалось удалить запись") }
        }
    }

    fun clearJournal() {
        val clearedAt = System.currentTimeMillis()
        val operationEpoch = JournalOperationCoordinator.suspendWrites(clearedAt)
        JournalOperationCoordinator.scope.launch {
            try {
                JournalOperationCoordinator.mutex.withLock {
                    journalDao.clearJournal(clearedAt)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _messages.emit("Не удалось очистить журнал")
            } finally {
                JournalOperationCoordinator.resumeWrites(
                    enabled = preferences.shouldSaveJournal(),
                    expectedEpoch = operationEpoch,
                )
            }
        }
    }

    private fun pruneJournal() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                JournalOperationCoordinator.mutex.withLock {
                    journalDao.deleteOlderThan(System.currentTimeMillis() - journalRetentionMillis())
                    journalDao.trimToSize(UserPreferences.JOURNAL_MAX_ENTRIES)
                }
            }.onFailure { _messages.emit("Не удалось обновить журнал") }
        }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val packageManager = getApplication<Application>().packageManager
                    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val collator = Collator.getInstance(Locale.getDefault())

                    packageManager.queryIntentActivities(launcherIntent, 0)
                        .asSequence()
                        .map { info ->
                            InstalledApp(
                                packageName = info.activityInfo.packageName,
                                label = info.loadLabel(packageManager).toString(),
                            )
                        }
                        .distinctBy(InstalledApp::packageName)
                        .sortedWith { left, right -> collator.compare(left.label, right.label) }
                        .toList()
                }
            }.onSuccess { apps ->
                _installedApps.value = apps
            }.onFailure {
                _messages.emit("Не удалось получить список приложений")
            }
        }
    }

    private fun journalRetentionMillis(): Long =
        UserPreferences.JOURNAL_RETENTION_DAYS * 24L * 60L * 60L * 1_000L
}
