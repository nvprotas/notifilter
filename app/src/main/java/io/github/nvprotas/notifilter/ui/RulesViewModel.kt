package io.github.nvprotas.notifilter.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nvprotas.notifilter.data.AppDatabase
import io.github.nvprotas.notifilter.data.BlockedNotificationEntity
import io.github.nvprotas.notifilter.data.JournalOperationCoordinator
import io.github.nvprotas.notifilter.data.RuleBackup
import io.github.nvprotas.notifilter.data.RuleBackupCodec
import io.github.nvprotas.notifilter.data.RuleBackupError
import io.github.nvprotas.notifilter.data.RuleBackupException
import io.github.nvprotas.notifilter.data.RuleRepository
import io.github.nvprotas.notifilter.data.UserPreferences
import io.github.nvprotas.notifilter.data.functionalKey
import io.github.nvprotas.notifilter.domain.ActiveNotificationsState
import io.github.nvprotas.notifilter.domain.FilterRule
import io.github.nvprotas.notifilter.domain.RulePreviewEvaluator
import io.github.nvprotas.notifilter.domain.RulePreviewResult
import io.github.nvprotas.notifilter.notification.ActiveNotificationCoordinator
import java.io.IOException
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RuleImportPreview(
    val importedRuleCount: Int,
    val currentRuleCount: Int,
    val duplicateCount: Int,
)

enum class RuleImportMode {
    ADD,
    REPLACE,
}

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
    val activeNotifications: StateFlow<ActiveNotificationsState> =
        ActiveNotificationCoordinator.state
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

    private var pendingRuleImport: RuleBackup? = null
    private val _ruleImportPreview = MutableStateFlow<RuleImportPreview?>(null)
    val ruleImportPreview: StateFlow<RuleImportPreview?> = _ruleImportPreview

    private val _backupOperationInProgress = MutableStateFlow(false)
    val backupOperationInProgress: StateFlow<Boolean> = _backupOperationInProgress

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

    fun exportRules(uri: Uri) {
        if (_backupOperationInProgress.value) return
        _backupOperationInProgress.value = true
        viewModelScope.launch {
            try {
                val exportedCount = withContext(Dispatchers.IO) {
                    val rulesToExport = repository.exportRules()
                    val bytes = RuleBackupCodec.encode(rulesToExport)
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(bytes)
                        output.flush()
                    } ?: throw IOException("Unable to open export document")
                    rulesToExport.size
                }
                _messages.emit("Экспортировано правил: $exportedCount")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _messages.emit("Не удалось экспортировать правила. Выберите другой файл и повторите.")
            } finally {
                _backupOperationInProgress.value = false
            }
        }
    }

    fun prepareRuleImport(uri: Uri) {
        if (_backupOperationInProgress.value) return
        pendingRuleImport = null
        _ruleImportPreview.value = null
        _backupOperationInProgress.value = true
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val backup = resolver.openInputStream(uri)?.use { input ->
                        RuleBackupCodec.decode(input)
                    }
                        ?: throw IOException("Unable to open import document")
                    val currentRules = repository.exportRules()
                    PreparedRuleImport(
                        backup = backup,
                        preview = buildRuleImportPreview(currentRules, backup.rules),
                    )
                }
                pendingRuleImport = prepared.backup
                _ruleImportPreview.value = prepared.preview
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _messages.emit(ruleImportErrorMessage(error))
            } finally {
                _backupOperationInProgress.value = false
            }
        }
    }

    fun dismissRuleImport() {
        if (_backupOperationInProgress.value) return
        pendingRuleImport = null
        _ruleImportPreview.value = null
    }

    fun confirmRuleImport(mode: RuleImportMode) {
        if (_backupOperationInProgress.value) return
        val backup = pendingRuleImport ?: return
        _backupOperationInProgress.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (mode) {
                        RuleImportMode.ADD -> repository.addImportedRules(backup.rules)
                        RuleImportMode.REPLACE -> repository.replaceRules(backup.rules)
                    }
                }
                pendingRuleImport = null
                _ruleImportPreview.value = null
                val message = when (mode) {
                    RuleImportMode.ADD -> if (result.skipped > 0) {
                        "Добавлено правил: ${result.imported}. Пропущено дубликатов: ${result.skipped}."
                    } else {
                        "Добавлено правил: ${result.imported}"
                    }

                    RuleImportMode.REPLACE -> "Восстановлено правил: ${result.imported}"
                }
                _messages.emit(message)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _messages.emit("Не удалось импортировать правила. Текущие правила не изменены.")
            } finally {
                _backupOperationInProgress.value = false
            }
        }
    }

    fun setRuleEnabled(rule: FilterRule, enabled: Boolean) {
        save(rule.copy(enabled = enabled))
    }

    fun setFilteringEnabled(enabled: Boolean) {
        preferences.setFilteringEnabled(enabled)
    }

    suspend fun previewRule(
        savedRules: List<FilterRule>,
        existingRule: FilterRule?,
        draft: FilterRule,
        filteringEnabled: Boolean,
        activeState: ActiveNotificationsState,
    ): RulePreviewResult {
        delay(PREVIEW_DEBOUNCE_MILLIS)
        return withContext(Dispatchers.Default) {
            RulePreviewEvaluator.evaluate(
                savedRules = savedRules,
                existingRule = existingRule,
                draft = draft,
                filteringEnabled = filteringEnabled,
                activeState = activeState,
            )
        }
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

    companion object {
        private const val PREVIEW_DEBOUNCE_MILLIS = 180L
    }
}

private data class PreparedRuleImport(
    val backup: RuleBackup,
    val preview: RuleImportPreview,
)

internal fun buildRuleImportPreview(
    currentRules: List<FilterRule>,
    importedRules: List<FilterRule>,
): RuleImportPreview {
    val knownKeys = currentRules.mapTo(mutableSetOf()) { it.functionalKey() }
    val addableCount = importedRules.count { knownKeys.add(it.functionalKey()) }
    return RuleImportPreview(
        importedRuleCount = importedRules.size,
        currentRuleCount = currentRules.size,
        duplicateCount = importedRules.size - addableCount,
    )
}

internal fun ruleImportErrorMessage(error: Throwable): String = when (error) {
    is RuleBackupException -> when (error.error) {
        RuleBackupError.UNSUPPORTED_FORMAT ->
            "Это не резервная копия правил Notifilter."

        RuleBackupError.UNSUPPORTED_VERSION ->
            "Версия резервной копии ${error.declaredVersion ?: "?"} не поддерживается."

        RuleBackupError.TOO_LARGE,
        RuleBackupError.TOO_MANY_RULES -> "Резервная копия слишком большая для импорта."

        RuleBackupError.INVALID_RULE ->
            "Правило ${error.ruleNumber ?: "?"} в резервной копии содержит недопустимые данные."

        RuleBackupError.MALFORMED_DOCUMENT ->
            "Не удалось прочитать файл. Выберите резервную копию правил Notifilter."
    }

    else -> "Не удалось открыть резервную копию. Выберите другой файл и повторите."
}
