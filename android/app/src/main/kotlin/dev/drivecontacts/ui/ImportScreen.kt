package dev.drivecontacts.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.drivecontacts.R

private val VCARD_MIME_TYPES = arrayOf("text/vcard", "text/x-vcard", "text/directory", "application/octet-stream", "*/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit) {
    val viewModel: ImportViewModel = viewModel(factory = ImportViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFile(uri)
    }

    LaunchedEffect(hasPermission) { if (hasPermission) viewModel.loadAccounts() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.import_help), style = MaterialTheme.typography.bodyMedium)

            Text(stringResource(R.string.import_from_phone), style = MaterialTheme.typography.titleMedium)
            if (!hasPermission) {
                Text(stringResource(R.string.import_permission_text))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) }) {
                    Text(stringResource(R.string.import_grant_permission))
                }
            } else {
                val accounts = state.accounts
                if (accounts != null && accounts.isEmpty()) {
                    Text(stringResource(R.string.import_no_accounts))
                }
                accounts.orEmpty().forEach { account ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = account in state.selected,
                            role = Role.Checkbox,
                            onValueChange = { viewModel.toggle(account) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = account in state.selected, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(account.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                pluralStringResource(R.plurals.import_contact_count, account.contactCount, account.contactCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Button(onClick = viewModel::importSelected, enabled = state.selected.isNotEmpty() && !state.busy) {
                    Text(stringResource(R.string.import_selected))
                }
            }

            HorizontalDivider()
            OutlinedButton(onClick = { fileLauncher.launch(VCARD_MIME_TYPES) }, enabled = !state.busy) {
                Text(stringResource(R.string.import_vcard))
            }
            if (state.failed) {
                Text(stringResource(R.string.import_failed), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    state.summary?.let { summary ->
        AlertDialog(
            onDismissRequest = viewModel::summaryShown,
            title = { Text(stringResource(R.string.import_result_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.import_result_imported, summary.imported))
                    Text(stringResource(R.string.import_result_duplicates, summary.skippedDuplicates))
                    Text(stringResource(R.string.import_result_dropped, summary.droppedProperties))
                }
            },
            confirmButton = { TextButton(onClick = viewModel::summaryShown) { Text(stringResource(R.string.ok)) } },
        )
    }
}
