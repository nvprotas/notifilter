package io.github.nvprotas.notifilter.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.weight
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nvprotas.notifilter.data.BlockedNotificationEntity
import io.github.nvprotas.notifilter.data.JournalStatus
import io.github.nvprotas.notifilter.data.UserPreferences
import io.github.nvprotas.notifilter.domain.FilterRule
import io.github.nvprotas.notifilter.domain.MatchTarget
import io.github.nvprotas.notifilter.domain.NotificationContent
import io.github.nvprotas.notifilter.domain.RuleAction
import io.github.nvprotas.notifilter.domain.RuleMatcher
import java.text.DateFormat
import java.util.Date

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
    val journalEnabled by viewModel.journalEnabled.collectAsStateWithLifecycle()
    val journalEntries by viewModel.journalEntries.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                                Text(if (section == MainSection.RULES) "Правила" else "Журнал")
                            },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            JournalContent(
                entries = journalEntries,
                apps = apps,
                journalEnabled = journalEnabled,
                onJournalEnabledChange = viewModel::setJournalEnabled,
                onDelete = viewModel::deleteJournalEntry,
                onClear = viewModel::clearJournal,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
    }

    if (creatingRule || editingRule != null) {
        RuleEditorDialog(
            existingRule = editingRule,
            apps = apps,
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
}

@Composable
private fun JournalContent(
    entries: List<BlockedNotificationEntity>,
    apps: List<InstalledApp>,
    journalEnabled: Boolean,
    onJournalEnabledChange: (Boolean) -> Unit,
    onDelete: (BlockedNotificationEntity) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<BlockedNotificationEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val labelsByPackage = remember(apps) { apps.associate { it.packageName to it.label } }
    val visibleEntries = remember(entries, query, labelsByPackage) {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isEmpty()) {
            entries
        } else {
            entries.filter { entry ->
                entry.packageName.lowercase().contains(normalizedQuery) ||
                    labelsByPackage[entry.packageName]
                        ?.lowercase()
                        ?.contains(normalizedQuery) == true ||
                    entry.title.lowercase().contains(normalizedQuery) ||
                    entry.body.lowercase().contains(normalizedQuery) ||
                    entry.matchedRulePattern.lowercase().contains(normalizedQuery)
            }
        }
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
                        title = "Сохранять журнал",
                        description = "Выключен по умолчанию. Заголовок и основной текст скрываемых уведомлений сохраняются только на устройстве",
                        checked = journalEnabled,
                        onCheckedChange = onJournalEnabledChange,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "До ${UserPreferences.JOURNAL_RETENTION_DAYS} дней, не более ${UserPreferences.JOURNAL_MAX_ENTRIES} записей",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { confirmClear = true },
                            enabled = entries.isNotEmpty(),
                        ) { Text("Очистить") }
                    }
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
                label = { Text("Поиск в журнале") },
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
                            if (entries.isEmpty()) "Записей журнала пока нет" else "Ничего не найдено",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (entries.isEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (journalEnabled) {
                                    "Когда правило запросит скрытие уведомления, его содержимое появится здесь."
                                } else {
                                    "Включите журнал, чтобы сохранять локальную историю сработавших правил."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        } else {
            items(visibleEntries, key = BlockedNotificationEntity::id) { entry ->
                JournalEntryCard(
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
        JournalEntryDialog(
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
            title = { Text("Очистить журнал?") },
            text = { Text("Все сохранённые уведомления будут удалены из журнала.") },
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
}

@Composable
private fun JournalEntryCard(
    entry: BlockedNotificationEntity,
    appLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formattedTime = remember(entry.blockedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(entry.blockedAt))
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
            Text(
                text = "${journalStatusLabel(entry)} · Правило: ${entry.matchedRulePattern}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun JournalEntryDialog(
    entry: BlockedNotificationEntity,
    appLabel: String?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val formattedTime = remember(entry.blockedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
            .format(Date(entry.blockedAt))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.title.ifBlank { "Запись журнала" }) },
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
                    text = journalStatusLabel(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.body.isNotBlank()) {
                    HorizontalDivider()
                    Text(entry.body, style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
                Text("Сработавшее правило", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = entry.matchedRulePattern,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Удалить запись") } },
    )
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
                    "Сервис в фоне проверяет текст всех доступных ему уведомлений, даже когда Notifilter закрыт. Журнал выключен по умолчанию; при отдельном включении видимые заголовок и основной текст сработавших уведомлений сохраняются только на устройстве."
                } else {
                    "Android попросит разрешить сервису в фоне читать текст всех доступных ему уведомлений и удалять совпавшие, даже когда Notifilter закрыт. Журнал включается отдельно и хранится только на устройстве; данные никуда не отправляются."
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
                description = "Мгновенно остановить или возобновить все правила",
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
            .clickable { onCheckedChange(!checked) }
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
                "Добавьте выражение вроде (?i)скидк[аи]|акци[яи], выберите приложение и проверьте его на тестовой строке.",
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
    apps: List<InstalledApp>,
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
    var testText by rememberSaveable(existingRule?.id) { mutableStateOf("") }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }

    val validationError = RuleMatcher.validationError(pattern)
    val testMatches = if (validationError == null && testText.isNotEmpty()) {
        val testRule = FilterRule(
            id = Long.MIN_VALUE,
            pattern = pattern,
            target = target,
            action = RuleAction.BLOCK,
            ignoreCase = ignoreCase,
        )
        RuleMatcher.compile(listOf(testRule)).evaluate(
            NotificationContent(
                packageName = "test",
                title = testText,
                body = testText,
            ),
        ).shouldBlock
    } else {
        false
    }

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

                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Тестовая строка (не сохраняется)") },
                    supportingText = {
                        if (testText.isNotEmpty() && validationError == null) {
                            Text(
                                if (testMatches) "Совпадение найдено" else "Совпадения нет",
                                color = if (testMatches) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(
                enabled = validationError == null,
                onClick = {
                    onSave(
                        FilterRule(
                            id = existingRule?.id ?: 0,
                            packageName = packageName.trim().takeIf(String::isNotEmpty),
                            pattern = pattern,
                            target = target,
                            action = action,
                            ignoreCase = ignoreCase,
                            enabled = enabled,
                            createdAt = existingRule?.createdAt ?: System.currentTimeMillis(),
                        ),
                    )
                },
            ) { Text("Сохранить") }
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
private fun ChoiceRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<InstalledApp>,
    onDismiss: () -> Unit,
    onSelect: (InstalledApp?) -> Unit,
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
                            subtitle = "Правило без ограничения по пакету",
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

private fun journalStatusLabel(entry: BlockedNotificationEntity): String =
    if (entry.status == JournalStatus.DISMISS_CONFIRMED.name) {
        "Android подтвердил скрытие"
    } else {
        "Отправлен запрос на скрытие"
    }

private enum class MainSection {
    RULES,
    JOURNAL,
}
