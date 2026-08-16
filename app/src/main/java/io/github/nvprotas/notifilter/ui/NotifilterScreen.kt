package io.github.nvprotas.notifilter.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nvprotas.notifilter.data.HistoryOutcome
import io.github.nvprotas.notifilter.data.NotificationHistoryEntity
import io.github.nvprotas.notifilter.data.UserPreferences
import io.github.nvprotas.notifilter.domain.ActiveNotificationsState
import io.github.nvprotas.notifilter.domain.FilterRule
import io.github.nvprotas.notifilter.domain.HistoryExclusion
import io.github.nvprotas.notifilter.domain.HistoryExclusionValidator
import io.github.nvprotas.notifilter.domain.MatchTarget
import io.github.nvprotas.notifilter.domain.RuleAction
import io.github.nvprotas.notifilter.domain.RulePreviewEntry
import io.github.nvprotas.notifilter.domain.RulePreviewResult
import java.text.DateFormat
import java.util.Date

private typealias RulePreviewLoader = suspend (
    List<FilterRule>,
    FilterRule?,
    FilterRule,
    Boolean,
    ActiveNotificationsState,
) -> RulePreviewResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotifilterScreen(
    viewModel: RulesViewModel,
    notificationAccessGranted: Boolean,
    onOpenAccessSettings: () -> Unit,
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val apps by viewModel.installedApps.collectAsStateWithLifecycle()
    val filteringEnabled by viewModel.filteringEnabled.collectAsStateWithLifecycle()
    val activeNotifications by viewModel.activeNotifications.collectAsStateWithLifecycle()
    val historyEnabled by viewModel.historyEnabled.collectAsStateWithLifecycle()
    val historyEntries by viewModel.historyEntries.collectAsStateWithLifecycle()
    val historyExclusions by viewModel.historyExclusions.collectAsStateWithLifecycle()
    val ruleImportPreview by viewModel.ruleImportPreview.collectAsStateWithLifecycle()
    val backupOperationInProgress by viewModel.backupOperationInProgress.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportRulesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri -> uri?.let(viewModel::exportRules) },
    )
    val importRulesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(viewModel::prepareRuleImport) },
    )

    var selectedSection by rememberSaveable { mutableStateOf(MainSection.RULES) }
    var creatingRule by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<FilterRule?>(null) }
    var deletingRule by remember { mutableStateOf<FilterRule?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Notifilter") })
                TabRow(selectedTabIndex = selectedSection.ordinal) {
                    MainSection.entries.forEach { section ->
                        Tab(
                            selected = selectedSection == section,
                            onClick = { selectedSection = section },
                            text = {
                                Text(if (section == MainSection.RULES) "Правила" else "История")
                            },
                        )
                    }
                }
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        floatingActionButton = {
            if (selectedSection == MainSection.RULES) {
                ExtendedFloatingActionButton(
                    onClick = { creatingRule = true },
                    text = { Text("Добавить правило") },
                    icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
                )
            }
        },
    ) { contentPadding ->
        if (selectedSection == MainSection.RULES) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    AccessCard(
                        granted = notificationAccessGranted,
                        onOpenSettings = onOpenAccessSettings,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                item {
                    MasterSwitchCard(
                        enabled = filteringEnabled,
                        onEnabledChange = viewModel::setFilteringEnabled,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = "Правила",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Исключение «Разрешить» всегда важнее правила «Скрыть».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    RuleBackupCard(
                        operationInProgress = backupOperationInProgress,
                        onExport = { exportRulesLauncher.launch("notifilter-rules.json") },
                        onImport = {
                            importRulesLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "text/json",
                                    "text/plain",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                if (rules.isEmpty()) {
                    item {
                        EmptyRulesCard(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                } else {
                    items(rules, key = FilterRule::id) { rule ->
                        RuleCard(
                            rule = rule,
                            appLabel = apps.firstOrNull { it.packageName == rule.packageName }?.label,
                            onEnabledChange = { enabled -> viewModel.setRuleEnabled(rule, enabled) },
                            onEdit = { editingRule = rule },
                            onDelete = { deletingRule = rule },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                item { Spacer(Modifier.height(88.dp)) }
            }
        } else {
            HistoryContent(
                entries = historyEntries,
                exclusions = historyExclusions,
                apps = apps,
                historyEnabled = historyEnabled,
                onHistoryEnabledChange = viewModel::setHistoryEnabled,
                onSaveExclusion = viewModel::saveHistoryExclusion,
                onSetExclusionEnabled = viewModel::setHistoryExclusionEnabled,
                onDeleteExclusion = viewModel::deleteHistoryExclusion,
                onDelete = viewModel::deleteHistoryEntry,
                onClear = viewModel::clearHistory,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
    }

    if (creatingRule || editingRule != null) {
        RuleEditorDialog(
            existingRule = editingRule,
            savedRules = rules,
            apps = apps,
            filteringEnabled = filteringEnabled,
            activeNotifications = activeNotifications,
            loadPreview = viewModel::previewRule,
            onDismiss = {
                creatingRule = false
                editingRule = null
            },
            onSave = { rule ->
                viewModel.save(rule)
                creatingRule = false
                editingRule = null
            },
        )
    }

    deletingRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { deletingRule = null },
            title = { Text("Удалить правило?") },
            text = { Text(rule.pattern) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(rule)
                        deletingRule = null
                    },
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deletingRule = null }) { Text("Отмена") }
            },
        )
    }

    ruleImportPreview?.let { preview ->
        RuleImportDialog(
            preview = preview,
            operationInProgress = backupOperationInProgress,
            onAdd = { viewModel.confirmRuleImport(RuleImportMode.ADD) },
            onReplace = { viewModel.confirmRuleImport(RuleImportMode.REPLACE) },
            onDismiss = viewModel::dismissRuleImport,
        )
    }
}

@Composable
private fun RuleBackupCard(
    operationInProgress: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Резервная копия правил",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Сохраните правила в JSON перед удалением приложения или переносом на другое устройство.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onExport,
                enabled = !operationInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Экспортировать правила") }
            OutlinedButton(
                onClick = onImport,
                enabled = !operationInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Импортировать правила") }
            if (operationInProgress) {
                Text(
                    text = "Обрабатываем файл…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun RuleImportDialog(
    preview: RuleImportPreview,
    operationInProgress: Boolean,
    onAdd: () -> Unit,
    onReplace: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!operationInProgress) onDismiss() },
        title = { Text("Импортировать правила?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("В резервной копии: ${preview.importedRuleCount}")
                Text("Текущих правил: ${preview.currentRuleCount}")
                if (preview.duplicateCount > 0) {
                    Text(
                        ruleImportDuplicateMessage(preview.duplicateCount),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Добавление сохранит текущие правила и добавит только новые.")
                Text(
                    ruleImportReplacementMessage(preview.currentRuleCount),
                    color = MaterialTheme.colorScheme.error,
                )
                if (operationInProgress) {
                    Text(
                        text = "Импортируем правила…",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = onAdd,
                    enabled = !operationInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Добавить правила") }
                OutlinedButton(
                    onClick = onReplace,
                    enabled = !operationInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Заменить все правила") }
                TextButton(
                    onClick = onDismiss,
                    enabled = !operationInProgress,
                ) { Text("Отмена") }
            }
        },
    )
}

internal fun ruleImportDuplicateMessage(duplicateCount: Int): String =
    "При добавлении будет пропущено дубликатов: $duplicateCount"

internal fun ruleImportReplacementMessage(currentRuleCount: Int): String =
    "Замена удалит $currentRuleCount текущих правил и восстановит правила из файла."

@Composable
private fun HistoryContent(
    entries: List<NotificationHistoryEntity>,
    exclusions: List<HistoryExclusion>,
    apps: List<InstalledApp>,
    historyEnabled: Boolean,
    onHistoryEnabledChange: (Boolean) -> Unit,
    onSaveExclusion: (HistoryExclusion) -> Unit,
    onSetExclusionEnabled: (HistoryExclusion, Boolean) -> Unit,
    onDeleteExclusion: (HistoryExclusion) -> Unit,
    onDelete: (NotificationHistoryEntity) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A history search may contain copied notification text; keep it out of saved-instance state.
    var query by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<NotificationHistoryEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var showExclusions by rememberSaveable { mutableStateOf(false) }
    var creatingExclusion by remember { mutableStateOf(false) }
    var editingExclusion by remember { mutableStateOf<HistoryExclusion?>(null) }
    var deletingExclusion by remember { mutableStateOf<HistoryExclusion?>(null) }
    val labelsByPackage = remember(apps) { apps.associate { it.packageName to it.label } }
    val selectableApps = remember(apps, entries) {
        (apps + entries.map { entry ->
            InstalledApp(
                packageName = entry.packageName,
                label = labelsByPackage[entry.packageName] ?: entry.packageName,
            )
        })
            .distinctBy(InstalledApp::packageName)
            .sortedBy { it.label.lowercase() }
    }
    val visibleEntries = remember(entries, query, labelsByPackage) {
        filterHistoryEntries(entries, query, labelsByPackage)
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingSwitchRow(
                        title = "Сохранять историю",
                        description = "Сохранять на устройстве заголовок и текст новых доступных для фильтрации уведомлений, кроме исключённых",
                        checked = historyEnabled,
                        onCheckedChange = onHistoryEnabledChange,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "До ${UserPreferences.HISTORY_RETENTION_DAYS} дней, не более ${UserPreferences.HISTORY_MAX_ENTRIES} записей",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { confirmClear = true },
                            enabled = entries.isNotEmpty(),
                        ) { Text("Очистить") }
                    }
                    OutlinedButton(
                        onClick = { showExclusions = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text("Настроить исключения: ${exclusions.size}")
                    }
                    Text(
                        text = "По умолчанию не сохраняются уведомления с отдельным блоком из 4–6 цифр. Исключения не влияют на показ уведомлений.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                label = { Text("Поиск в истории") },
                placeholder = { Text("Приложение, заголовок, текст или правило") },
                singleLine = true,
            )
        }

        if (visibleEntries.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            if (entries.isEmpty()) "История пока пуста" else "Ничего не найдено",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (entries.isEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (historyEnabled) {
                                    "Новые уведомления появятся здесь, если они не совпадут с исключениями."
                                } else {
                                    "Включите историю, чтобы сохранять новые уведомления только на этом устройстве."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        } else {
            items(visibleEntries, key = NotificationHistoryEntity::id) { entry ->
                HistoryEntryCard(
                    entry = entry,
                    appLabel = labelsByPackage[entry.packageName],
                    onClick = { selectedEntry = entry },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    selectedEntry?.let { entry ->
        HistoryEntryDialog(
            entry = entry,
            appLabel = labelsByPackage[entry.packageName],
            onDismiss = { selectedEntry = null },
            onDelete = {
                onDelete(entry)
                selectedEntry = null
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Все сохранённые уведомления будут удалены. Новые продолжат сохраняться, если история включена.") },
            confirmButton = {
                Button(
                    onClick = {
                        onClear()
                        confirmClear = false
                    },
                ) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Отмена") }
            },
        )
    }

    if (showExclusions) {
        HistoryExclusionsDialog(
            exclusions = exclusions,
            apps = selectableApps,
            onDismiss = { showExclusions = false },
            onAdd = {
                showExclusions = false
                creatingExclusion = true
            },
            onEdit = { rule ->
                showExclusions = false
                editingExclusion = rule
            },
            onEnabledChange = onSetExclusionEnabled,
            onDelete = { rule ->
                showExclusions = false
                deletingExclusion = rule
            },
        )
    }

    if (creatingExclusion || editingExclusion != null) {
        HistoryExclusionEditorDialog(
            existing = editingExclusion,
            apps = selectableApps,
            onDismiss = {
                creatingExclusion = false
                editingExclusion = null
                showExclusions = true
            },
            onSave = { rule ->
                onSaveExclusion(rule)
                creatingExclusion = false
                editingExclusion = null
                showExclusions = true
            },
        )
    }

    deletingExclusion?.let { rule ->
        AlertDialog(
            onDismissRequest = {
                deletingExclusion = null
                showExclusions = true
            },
            title = { Text("Удалить исключение?") },
            text = {
                Text("Будущие совпадающие уведомления смогут сохраняться в истории. Уже удалённые записи не восстановятся.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteExclusion(rule)
                        deletingExclusion = null
                        showExclusions = true
                    },
                ) { Text("Удалить исключение") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deletingExclusion = null
                        showExclusions = true
                    },
                ) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun HistoryEntryCard(
    entry: NotificationHistoryEntity,
    appLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formattedTime = remember(entry.postedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(entry.postedAt))
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = appLabel ?: entry.packageName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = entry.title.ifBlank { "Без заголовка" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            val status = historyStatusLabel(entry)
            Text(
                text = entry.matchedRulePattern?.let { pattern ->
                    "$status · Правило: $pattern"
                } ?: status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HistoryEntryDialog(
    entry: NotificationHistoryEntity,
    appLabel: String?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val formattedTime = remember(entry.postedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
            .format(Date(entry.postedAt))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.title.ifBlank { "Запись истории" }) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = appLabel ?: entry.packageName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = historyStatusLabel(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.body.isNotBlank()) {
                    HorizontalDivider()
                    Text(entry.body, style = MaterialTheme.typography.bodyLarge)
                }
                entry.matchedRulePattern?.let { pattern ->
                    HorizontalDivider()
                    Text("Сработавшее правило", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = pattern,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Удалить запись") } },
    )
}

@Composable
private fun HistoryExclusionsDialog(
    exclusions: List<HistoryExclusion>,
    apps: List<InstalledApp>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (HistoryExclusion) -> Unit,
    onEnabledChange: (HistoryExclusion, Boolean) -> Unit,
    onDelete: (HistoryExclusion) -> Unit,
) {
    val labelsByPackage = remember(apps) { apps.associate { it.packageName to it.label } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Исключения истории") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Совпавшие уведомления не сохраняются. При включении исключения прежние совпадения удаляются.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (exclusions.isEmpty()) {
                    item {
                        Text(
                            "Исключений пока нет",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(exclusions, key = HistoryExclusion::id) { rule ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.toggleable(
                                        value = rule.enabled,
                                        role = Role.Switch,
                                        onValueChange = { enabled -> onEnabledChange(rule, enabled) },
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            historyExclusionScopeLabel(rule, labelsByPackage),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        rule.pattern?.let { pattern ->
                                            Text(
                                                pattern,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = rule.enabled,
                                        onCheckedChange = null,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(onClick = { onEdit(rule) }) { Text("Изменить") }
                                    TextButton(onClick = { onDelete(rule) }) { Text("Удалить") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onAdd) { Text("Добавить исключение") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun HistoryExclusionEditorDialog(
    existing: HistoryExclusion?,
    apps: List<InstalledApp>,
    onDismiss: () -> Unit,
    onSave: (HistoryExclusion) -> Unit,
) {
    var packageName by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.packageName.orEmpty())
    }
    var pattern by rememberSaveable(existing?.id) { mutableStateOf(existing?.pattern.orEmpty()) }
    var target by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.target ?: MatchTarget.ALL_TEXT)
    }
    var ignoreCase by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.ignoreCase ?: true)
    }
    var enabled by rememberSaveable(existing?.id) { mutableStateOf(existing?.enabled ?: true) }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var attemptedSave by rememberSaveable { mutableStateOf(false) }
    val draft = remember(packageName, pattern, target, ignoreCase, enabled, existing) {
        HistoryExclusion(
            id = existing?.id ?: 0L,
            packageName = packageName.trim().takeIf(String::isNotEmpty),
            pattern = pattern.trim().takeIf(String::isNotEmpty),
            target = target,
            ignoreCase = ignoreCase,
            enabled = enabled,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
    }
    val validationError = HistoryExclusionValidator.validationError(draft)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Новое исключение" else "Изменить исключение") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Пакет приложения") },
                    placeholder = { Text("Например, com.example.app") },
                    supportingText = { Text("Оставьте пустым, чтобы проверять все приложения") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = { showAppPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Выбрать приложение") }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Регулярное выражение") },
                    placeholder = { Text("Например, (?:^|[^0-9])[0-9]{4,6}(?:[^0-9]|$)") },
                    supportingText = {
                        Text(
                            if (attemptedSave && validationError != null) validationError
                            else "Оставьте пустым, чтобы исключить все уведомления выбранного приложения",
                        )
                    },
                    isError = attemptedSave && validationError != null,
                    minLines = 2,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
                if (attemptedSave && validationError != null) {
                    Text(
                        text = validationError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (pattern.isNotBlank()) {
                    Text("Проверять", style = MaterialTheme.typography.titleSmall)
                    MatchTarget.entries.forEach { option ->
                        ChoiceRow(
                            selected = target == option,
                            label = targetLabel(option),
                            onClick = { target = option },
                        )
                    }
                    SettingSwitchRow(
                        title = "Без учёта регистра",
                        description = "Применяется только к регулярному выражению",
                        checked = ignoreCase,
                        onCheckedChange = { ignoreCase = it },
                    )
                }
                SettingSwitchRow(
                    title = "Исключение включено",
                    description = "При включении уже сохранённые совпадения будут удалены",
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    attemptedSave = true
                    if (validationError == null) onSave(draft)
                },
            ) { Text("Сохранить исключение") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (showAppPicker) {
        AppPickerDialog(
            apps = apps,
            onDismiss = { showAppPicker = false },
            onSelect = { selected ->
                packageName = selected?.packageName.orEmpty()
                showAppPicker = false
            },
            allAppsSubtitle = "Регулярное выражение действует для всех приложений",
        )
    }
}

@Composable
private fun AccessCard(
    granted: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (granted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = if (granted) "Доступ к уведомлениям включён" else "Нужен доступ к уведомлениям",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (granted) {
                    "Сервис в фоне проверяет текст доступных уведомлений. Предпросмотр хранится только в памяти. История выключена по умолчанию и после отдельного включения сохраняет на устройстве только уведомления, не совпавшие с исключениями."
                } else {
                    "Android попросит разрешить сервису читать активные уведомления, показывать их в предпросмотре и удалять совпавшие. Предпросмотр не сохраняется; история включается отдельно и хранится только на устройстве."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenSettings) {
                Text(if (granted) "Настроить доступ" else "Разрешить доступ")
            }
        }
    }
}

@Composable
private fun MasterSwitchCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            SettingSwitchRow(
                title = "Фильтрация включена",
                description = "При включении правила сразу применяются и к push, уже находящимся в шторке",
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
            Text(
                text = "Android сообщает приложению об уведомлении уже после публикации: карточка, звук или вибрация могут успеть появиться.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Text(
                text = "Системные приложения, звонки, будильники, медиа, ongoing и foreground-service уведомления не фильтруются.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun EmptyRulesCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Правил пока нет", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Добавьте выражение вроде (?i)скидк[аи]|акци[яи]. Редактор покажет совпадения среди активных push до сохранения.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RuleCard(
    rule: FilterRule,
    appLabel: String?,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = appLabel ?: rule.packageName ?: "Все приложения",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    rule.packageName?.let { packageName ->
                        Text(
                            text = packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Switch(checked = rule.enabled, onCheckedChange = onEnabledChange)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = rule.pattern,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = if (rule.action == RuleAction.BLOCK) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = if (rule.action == RuleAction.BLOCK) "СКРЫТЬ" else "РАЗРЕШИТЬ",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = targetLabel(rule.target),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEdit) { Text("Изменить") }
                TextButton(onClick = onDelete) { Text("Удалить") }
            }
        }
    }
}

@Composable
private fun RuleEditorDialog(
    existingRule: FilterRule?,
    savedRules: List<FilterRule>,
    apps: List<InstalledApp>,
    filteringEnabled: Boolean,
    activeNotifications: ActiveNotificationsState,
    loadPreview: RulePreviewLoader,
    onDismiss: () -> Unit,
    onSave: (FilterRule) -> Unit,
) {
    var packageName by rememberSaveable(existingRule?.id) {
        mutableStateOf(existingRule?.packageName.orEmpty())
    }
    var pattern by rememberSaveable(existingRule?.id) {
        mutableStateOf(existingRule?.pattern.orEmpty())
    }
    var target by rememberSaveable(existingRule?.id) {
        mutableStateOf(existingRule?.target ?: MatchTarget.ALL_TEXT)
    }
    var action by rememberSaveable(existingRule?.id) {
        mutableStateOf(existingRule?.action ?: RuleAction.BLOCK)
    }
    var ignoreCase by rememberSaveable(existingRule?.id) {
        mutableStateOf(existingRule?.ignoreCase ?: true)
    }
    var enabled by rememberSaveable(existingRule?.id) {
        mutableStateOf(existingRule?.enabled ?: true)
    }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }

    val draft = remember(packageName, pattern, target, action, ignoreCase, enabled, existingRule) {
        FilterRule(
            id = existingRule?.id ?: 0L,
            packageName = packageName.trim().takeIf(String::isNotEmpty),
            pattern = pattern,
            target = target,
            action = action,
            ignoreCase = ignoreCase,
            enabled = enabled,
            createdAt = existingRule?.createdAt ?: System.currentTimeMillis(),
        )
    }
    val validationError = io.github.nvprotas.notifilter.domain.RuleMatcher.validationError(pattern)
    val previewKey = listOf(savedRules, existingRule, draft, filteringEnabled, activeNotifications)
    val preview by produceState<RulePreviewResult>(
        initialValue = RulePreviewResult.Computing,
        key1 = previewKey,
    ) {
        value = RulePreviewResult.Computing
        value = loadPreview(
            savedRules,
            existingRule,
            draft,
            filteringEnabled,
            activeNotifications,
        )
    }
    val predictedRemovals = (preview as? RulePreviewResult.Available)?.removalCount ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingRule == null) "Новое правило" else "Изменить правило") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Пакет приложения") },
                    placeholder = { Text("Пусто — все приложения") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = { showAppPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Выбрать из установленных") }

                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Регулярное выражение") },
                    supportingText = {
                        Text(
                            validationError
                                ?: "RE2: ищется фрагмент; для полного совпадения используйте ^…$. Lookaround и обратные ссылки не поддерживаются.",
                        )
                    },
                    isError = validationError != null && pattern.isNotEmpty(),
                    minLines = 2,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )

                Text("Проверять", style = MaterialTheme.typography.titleSmall)
                MatchTarget.entries.forEach { option ->
                    ChoiceRow(
                        selected = target == option,
                        label = targetLabel(option),
                        onClick = { target = option },
                    )
                }

                Text("Действие", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = action == RuleAction.BLOCK,
                        onClick = { action = RuleAction.BLOCK },
                        label = { Text("Скрыть") },
                    )
                    FilterChip(
                        selected = action == RuleAction.ALLOW,
                        onClick = { action = RuleAction.ALLOW },
                        label = { Text("Разрешить") },
                    )
                }

                SettingSwitchRow(
                    title = "Без учёта регистра",
                    description = "Например, «скидка» совпадёт со «СКИДКА»",
                    checked = ignoreCase,
                    onCheckedChange = { ignoreCase = it },
                )
                SettingSwitchRow(
                    title = "Правило включено",
                    description = "Можно временно выключить, не удаляя",
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )

                RulePreviewSection(
                    preview = preview,
                    draft = draft,
                    filteringEnabled = filteringEnabled,
                    apps = apps,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = validationError == null,
                onClick = {
                    onSave(draft)
                },
            ) {
                Text(
                    if (predictedRemovals > 0) {
                        "Сохранить и скрыть: $predictedRemovals"
                    } else {
                        "Сохранить"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (showAppPicker) {
        AppPickerDialog(
            apps = apps,
            onDismiss = { showAppPicker = false },
            onSelect = { selected ->
                packageName = selected?.packageName.orEmpty()
                showAppPicker = false
            },
        )
    }
}

@Composable
private fun RulePreviewSection(
    preview: RulePreviewResult,
    draft: FilterRule,
    filteringEnabled: Boolean,
    apps: List<InstalledApp>,
) {
    val labelsByPackage = remember(apps) { apps.associate { it.packageName to it.label } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Активные уведомления", style = MaterialTheme.typography.titleSmall)
        Text(
            "Предпросмотр ничего не скрывает. Совпавшие push исчезнут только после сохранения включённого правила.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val currentPreview = preview) {
            RulePreviewResult.Computing -> Text("Проверяем уведомления в шторке…")
            RulePreviewResult.Unavailable -> Text(
                "Предпросмотр недоступен. Разрешите Notifilter доступ к уведомлениям.",
                color = MaterialTheme.colorScheme.error,
            )

            is RulePreviewResult.Invalid -> Text(
                "Исправьте регулярное выражение, чтобы увидеть результат.",
                color = MaterialTheme.colorScheme.error,
            )

            is RulePreviewResult.Available -> {
                if (currentPreview.activeCount == 0) {
                    Text("В системной шторке нет доступных уведомлений.")
                } else {
                    Text(
                        "Совпало: ${currentPreview.directMatchCount} из ${currentPreview.activeCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val outcomeText = when {
                        !filteringEnabled ->
                            "Общая фильтрация выключена — после сохранения ничего не исчезнет."

                        !draft.enabled ->
                            "Правило выключено — совпадения не будут скрыты."

                        draft.action == RuleAction.ALLOW -> {
                            val newlyAllowed = currentPreview.entries.count {
                                it.directMatch &&
                                    it.baselineDecision.shouldBlock &&
                                    !it.proposedDecision.shouldBlock
                            }
                            "Правило разрешит уведомлений: $newlyAllowed"
                        }

                        else -> "После сохранения исчезнут: ${currentPreview.removalCount}"
                    }
                    Text(
                        outcomeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (currentPreview.removalCount > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )

                    val relevantEntries = currentPreview.entries
                        .filter { it.directMatch || it.willBeRemoved }
                        .take(MAX_PREVIEW_SAMPLES)
                    relevantEntries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider()
                        RulePreviewNotification(
                            entry = entry,
                            draft = draft,
                            filteringEnabled = filteringEnabled,
                            appLabel = labelsByPackage[entry.sample.content.packageName],
                        )
                    }
                    val relevantCount = currentPreview.entries.count {
                        it.directMatch || it.willBeRemoved
                    }
                    if (relevantCount > relevantEntries.size) {
                        Text(
                            "И ещё ${relevantCount - relevantEntries.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (currentPreview.directMatchCount == 0) {
                        Text(
                            "Текущее выражение не совпало ни с одним активным уведомлением.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RulePreviewNotification(
    entry: RulePreviewEntry,
    draft: FilterRule,
    filteringEnabled: Boolean,
    appLabel: String?,
) {
    val content = entry.sample.content
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = appLabel ?: content.packageName,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (content.title.isNotBlank()) {
            Text(
                content.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (content.body.isNotBlank()) {
            Text(
                content.body,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            previewOutcome(entry, draft, filteringEnabled),
            style = MaterialTheme.typography.labelSmall,
            color = if (entry.willBeRemoved) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun previewOutcome(
    entry: RulePreviewEntry,
    draft: FilterRule,
    filteringEnabled: Boolean,
): String = when {
    !entry.directMatch && entry.willBeRemoved -> "Будет скрыто другим включённым правилом"
    !entry.directMatch -> "Нет прямого совпадения с черновиком"
    !filteringEnabled -> "Совпало, но общая фильтрация выключена"
    !draft.enabled -> "Совпало, но правило выключено"
    !entry.sample.eligibleForFiltering -> "Совпало, но это защищённое уведомление"
    entry.willBeRemoved -> "Исчезнет после сохранения"
    draft.action == RuleAction.ALLOW -> "Останется видимым благодаря этому исключению"
    entry.proposedDecision.matchedRuleId != null -> "Останется: сработало правило «Разрешить»"
    else -> "Совпало, но решение не изменится"
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<InstalledApp>,
    onDismiss: () -> Unit,
    onSelect: (InstalledApp?) -> Unit,
    allAppsSubtitle: String = "Правило без ограничения по пакету",
) {
    var query by remember { mutableStateOf("") }
    val visibleApps = remember(apps, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) {
            apps
        } else {
            apps.filter { app ->
                app.label.lowercase().contains(normalized) ||
                    app.packageName.lowercase().contains(normalized)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Выберите приложение", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Поиск") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        AppPickerRow(
                            title = "Все приложения",
                            subtitle = allAppsSubtitle,
                            onClick = { onSelect(null) },
                        )
                    }
                    items(visibleApps, key = InstalledApp::packageName) { app ->
                        AppPickerRow(
                            title = app.label,
                            subtitle = app.packageName,
                            onClick = { onSelect(app) },
                        )
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Отмена") }
            }
        }
    }
}

@Composable
private fun AppPickerRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun targetLabel(target: MatchTarget): String = when (target) {
    MatchTarget.TITLE -> "Заголовок"
    MatchTarget.BODY -> "Текст уведомления"
    MatchTarget.ALL_TEXT -> "Заголовок и весь текст"
}

internal fun historyStatusLabel(entry: NotificationHistoryEntity): String = when (entry.outcome) {
    HistoryOutcome.DISMISS_CONFIRMED.name -> "Android подтвердил скрытие"
    HistoryOutcome.DISMISS_REQUESTED.name -> "Отправлен запрос на скрытие"
    else -> "Получено"
}

internal fun historyExclusionScopeLabel(
    rule: HistoryExclusion,
    labelsByPackage: Map<String, String> = emptyMap(),
): String = rule.packageName?.let { packageName ->
    labelsByPackage[packageName] ?: packageName
} ?: "Все приложения"

internal fun filterHistoryEntries(
    entries: List<NotificationHistoryEntity>,
    query: String,
    labelsByPackage: Map<String, String> = emptyMap(),
): List<NotificationHistoryEntity> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isEmpty()) return entries
    return entries.filter { entry ->
        entry.packageName.lowercase().contains(normalizedQuery) ||
            labelsByPackage[entry.packageName]
                ?.lowercase()
                ?.contains(normalizedQuery) == true ||
            entry.title.lowercase().contains(normalizedQuery) ||
            entry.body.lowercase().contains(normalizedQuery) ||
            entry.matchedRulePattern
                ?.lowercase()
                ?.contains(normalizedQuery) == true
    }
}

private enum class MainSection {
    RULES,
    HISTORY,
}

private const val MAX_PREVIEW_SAMPLES = 5
