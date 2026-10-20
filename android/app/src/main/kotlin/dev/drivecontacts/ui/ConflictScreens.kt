package dev.drivecontacts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.drivecontacts.R
import dev.drivecontacts.core.Conflict
import dev.drivecontacts.core.ConflictKind
import dev.drivecontacts.core.ContactDiff
import dev.drivecontacts.core.Diff
import dev.drivecontacts.core.DiffRow
import dev.drivecontacts.core.DiffStatus
import dev.drivecontacts.core.Field

@Composable
private fun conflictName(conflict: Conflict): String =
    displayName(conflict.local ?: conflict.remote ?: conflict.base).ifEmpty { stringResource(R.string.unnamed_contact) }

@Composable
private fun conflictKindText(kind: ConflictKind): String = stringResource(
    when (kind) {
        ConflictKind.FIELDS -> R.string.conflict_kind_fields
        ConflictKind.DELETED_LOCALLY -> R.string.conflict_kind_deleted_locally
        ConflictKind.DELETED_REMOTELY -> R.string.conflict_kind_deleted_remotely
    },
)

@Composable
private fun fieldName(field: Field): String = stringResource(
    when (field) {
        Field.FORMATTED_NAME -> R.string.field_formatted_name
        Field.NAME -> R.string.field_name
        Field.NICKNAME -> R.string.field_nickname
        Field.ORGANIZATION -> R.string.field_organization
        Field.TITLE -> R.string.field_title
        Field.BIRTHDAY -> R.string.field_birthday
        Field.NOTE -> R.string.field_note
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(onBack: () -> Unit, onOpenConflict: (String) -> Unit, onCompareFiles: () -> Unit) {
    val viewModel: ConflictsViewModel = viewModel(factory = ConflictsViewModel.Factory)
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflicts_title)) },
                navigationIcon = { BackButton(onBack) },
                actions = { TextButton(onClick = onCompareFiles) { Text(stringResource(R.string.compare_files)) } },
            )
        },
    ) { padding ->
        if (conflicts.isEmpty()) {
            Text(stringResource(R.string.conflicts_empty), modifier = Modifier.padding(padding).padding(24.dp))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(conflicts, key = { it.uid }) { conflict ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpenConflict(conflict.uid) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(conflictName(conflict), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            conflictKindText(conflict.kind),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictDetailScreen(uid: String, onBack: () -> Unit, onGone: () -> Unit) {
    val viewModel: ConflictDetailViewModel = viewModel(key = uid, factory = ConflictDetailViewModel.factory(uid))
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val conflict = conflicts.firstOrNull { it.uid == uid }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    ErrorSnackbar(error, snackbar, viewModel::errorShown)
    // The screen closes when the conflict is closed, whoever closed it.
    LaunchedEffect(conflict == null) { if (conflict == null) onGone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(conflict?.let { conflictName(it) }.orEmpty()) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (conflict == null) return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (conflict.kind) {
                ConflictKind.FIELDS -> FieldChoices(conflict, viewModel)
                ConflictKind.DELETED_LOCALLY -> DeletionChoice(R.string.conflict_deleted_locally_text, viewModel)
                ConflictKind.DELETED_REMOTELY -> DeletionChoice(R.string.conflict_deleted_remotely_text, viewModel)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.show_original), Modifier.weight(1f))
                Switch(checked = viewModel.showOriginal, onCheckedChange = { viewModel.showOriginal = it })
            }
            val rows = remember(conflict) { Diff.rows(conflict.local, conflict.remote) }
            val baseColumn = if (viewModel.showOriginal) {
                alignBase(rows, remember(conflict) { Diff.rows(conflict.base, conflict.base) })
            } else {
                null
            }
            DiffTable(
                rows = rows,
                leftTitle = stringResource(R.string.this_device),
                rightTitle = stringResource(R.string.proton_drive),
                baseTitle = stringResource(R.string.original),
                baseColumn = baseColumn,
            )
        }
    }
}

@Composable
private fun DeletionChoice(textRes: Int, viewModel: ConflictDetailViewModel) {
    Text(stringResource(textRes), style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::delete) { Text(stringResource(R.string.delete)) }
        OutlinedButton(onClick = viewModel::keep) { Text(stringResource(R.string.keep)) }
    }
}

@Composable
private fun FieldChoices(conflict: Conflict, viewModel: ConflictDetailViewModel) {
    Text(stringResource(R.string.conflict_fields_text), style = MaterialTheme.typography.bodyLarge)
    conflict.fields.forEach { field ->
        val local = field in viewModel.useLocal
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(fieldName(field), style = MaterialTheme.typography.titleSmall)
                ChoiceRow(stringResource(R.string.this_device), fieldText(conflict.local, field), local) {
                    viewModel.choose(field, true)
                }
                ChoiceRow(stringResource(R.string.proton_drive), fieldText(conflict.remote, field), !local) {
                    viewModel.choose(field, false)
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { viewModel.chooseAll(conflict.fields, true) }) { Text(stringResource(R.string.keep_all_device)) }
        OutlinedButton(onClick = { viewModel.chooseAll(conflict.fields, false) }) { Text(stringResource(R.string.keep_all_drive)) }
    }
    Button(onClick = viewModel::apply, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.apply)) }
}

@Composable
private fun ChoiceRow(title: String, value: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.ifEmpty { stringResource(R.string.empty_value) }, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A side-by-side table. Changed rows have a colored background. [baseColumn] adds a third column. */
@Composable
fun DiffTable(
    rows: List<DiffRow>,
    leftTitle: String,
    rightTitle: String,
    baseTitle: String,
    baseColumn: List<List<String>>? = null,
) {
    val shown = visibleRowIndices(rows, baseColumn)
    Column(Modifier.fillMaxWidth()) {
        TableRow(label = "", cells = buildList {
            add(leftTitle); add(rightTitle); if (baseColumn != null) add(baseTitle)
        }, changed = false, header = true)
        HorizontalDivider()
        shown.forEach { i ->
            val row = rows[i]
            TableRow(
                label = row.label,
                cells = buildList {
                    add(row.left.joinToString("\n")); add(row.right.joinToString("\n"))
                    if (baseColumn != null) add(baseColumn[i].joinToString("\n"))
                },
                changed = row.changed,
                header = false,
            )
        }
    }
}

@Composable
private fun TableRow(label: String, cells: List<String>, changed: Boolean, header: Boolean) {
    val colors = MaterialTheme.colorScheme
    val background = if (changed) colors.tertiaryContainer else colors.surface
    val content = if (changed) colors.onTertiaryContainer else colors.onSurface
    val labelColor = if (changed) colors.onTertiaryContainer else colors.onSurfaceVariant
    Column(Modifier.fillMaxWidth().background(background).padding(vertical = 6.dp, horizontal = 4.dp)) {
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.forEach { cell ->
                Text(
                    cell,
                    modifier = Modifier.weight(1f),
                    color = content,
                    style = if (header) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareFilesScreen(onBack: () -> Unit) {
    val viewModel: CompareFilesViewModel = viewModel(factory = CompareFilesViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = (state as? CompareState.Loaded)?.diffs?.firstOrNull { it.uid == selectedUid }
    BackHandler(enabled = selected != null) { selectedUid = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compare_files)) },
                navigationIcon = { BackButton { if (selected != null) selectedUid = null else onBack() } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                CompareState.Loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                CompareState.Empty -> Text(stringResource(R.string.compare_empty), Modifier.padding(24.dp))
                is CompareState.Loaded -> when {
                    selected != null -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text(selected.displayName.ifEmpty { stringResource(R.string.unnamed_contact) }, style = MaterialTheme.typography.titleLarge)
                        DiffTable(
                            rows = selected.rows,
                            leftTitle = stringResource(R.string.this_device),
                            rightTitle = stringResource(R.string.proton_drive),
                            baseTitle = stringResource(R.string.original),
                        )
                    }
                    s.diffs.isEmpty() -> Text(stringResource(R.string.compare_no_differences), Modifier.padding(24.dp))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(s.diffs, key = { it.uid }) { diff ->
                            DiffListRow(diff) { selectedUid = diff.uid }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffListRow(diff: ContactDiff, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            diff.displayName.ifEmpty { stringResource(R.string.unnamed_contact) },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        val (label, container) = when (diff.status) {
            DiffStatus.ADDED -> R.string.badge_added to MaterialTheme.colorScheme.primaryContainer
            DiffStatus.REMOVED -> R.string.badge_removed to MaterialTheme.colorScheme.errorContainer
            DiffStatus.CHANGED -> R.string.badge_changed to MaterialTheme.colorScheme.tertiaryContainer
        }
        Surface(color = container, shape = MaterialTheme.shapes.small) {
            Text(stringResource(label), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}
