package dev.drivecontacts.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.drivecontacts.BuildConfig
import dev.drivecontacts.R
import dev.drivecontacts.SyncStatus
import dev.drivecontacts.core.model.Contact
import kotlinx.coroutines.delay

/** How often the "synced N min ago" text updates. */
private const val CLOCK_TICK_MS = 60_000L

private val PHONE_PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    onOpenContact: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenConflicts: () -> Unit,
    onImport: () -> Unit,
    onSignOut: () -> Unit,
) {
    val viewModel: ContactListViewModel = viewModel(factory = ContactListViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (PHONE_PERMISSIONS.all { result[it] == true }) viewModel.enablePhone() else viewModel.permissionDenied()
    }

    val email by viewModel.email.collectAsStateWithLifecycle()
    ErrorSnackbar(error, snackbar, viewModel::errorShown)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        email?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_sync_now)) },
                            onClick = { menuOpen = false; viewModel.syncNow() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_import)) },
                            onClick = { menuOpen = false; onImport() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (state.phoneEnabled) R.string.menu_phone_off else R.string.menu_phone_on,
                                    ),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                if (state.phoneEnabled) viewModel.disablePhone() else permissionLauncher.launch(PHONE_PERMISSIONS)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_sign_out)) },
                            onClick = { menuOpen = false; confirmSignOut = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_about)) },
                            onClick = { menuOpen = false; showAbout = true },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_contact))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SyncLine(state.status)
            OutlinedTextField(
                value = viewModel.query,
                onValueChange = viewModel::updateQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (viewModel.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                },
            )
            LazyColumn(Modifier.fillMaxSize()) {
                if (state.conflictCount > 0) {
                    item(key = "conflicts") {
                        ConflictBanner(state.conflictCount, onOpenConflicts)
                    }
                }
                if (!state.loaded) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            val loadError = state.loadError
                            if (loadError != null) {
                                Text(
                                    stringResource(R.string.load_failed, loadError),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            } else {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
                if (state.showOnboarding) {
                    item(key = "onboarding") {
                        OnboardingCard(
                            onShowInPhone = { permissionLauncher.launch(PHONE_PERMISSIONS) },
                            onImport = { viewModel.dismissOnboarding(); onImport() },
                            onDismiss = viewModel::dismissOnboarding,
                        )
                    }
                }
                if (state.loaded && state.contacts.isEmpty() && !state.showOnboarding) {
                    item(key = "empty") {
                        Text(
                            stringResource(if (state.totalCount == 0) R.string.list_empty else R.string.list_no_match),
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.contacts, key = { "c:" + it.uid }) { contact ->
                    ContactRow(contact, onClick = { onOpenContact(contact.uid) })
                }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out_title)) },
            text = { Text(stringResource(R.string.sign_out_message)) },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text(stringResource(R.string.menu_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME) },
            text = { Text(stringResource(R.string.about_text)) },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun SyncLine(status: SyncStatus) {
    val text = when {
        status.running -> stringResource(R.string.sync_running)
        status.lastError != null -> stringResource(R.string.sync_error, status.lastError)
        status.lastSyncAt != null -> syncedText(status.lastSyncAt)
        else -> stringResource(R.string.sync_never)
    }
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (status.lastError != null && !status.running) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun syncedText(lastSyncAt: Long): String {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    return when (val age = relativeTime(now, lastSyncAt)) {
        RelativeTime.Now -> stringResource(R.string.synced_now)
        is RelativeTime.Minutes -> stringResource(R.string.synced_minutes, age.count)
        is RelativeTime.Hours -> stringResource(R.string.synced_hours, age.count)
        is RelativeTime.Days -> stringResource(R.string.synced_days, age.count)
    }
}

@Composable
private fun ConflictBanner(count: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            pluralStringResource(R.plurals.conflicts_banner, count, count),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun OnboardingCard(onShowInPhone: () -> Unit, onImport: () -> Unit, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.onboarding_text), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShowInPhone) { Text(stringResource(R.string.onboarding_show_in_phone)) }
                TextButton(onClick = onImport) { Text(stringResource(R.string.onboarding_import)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.onboarding_not_now)) }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    val name = displayName(contact).ifEmpty { stringResource(R.string.unnamed_contact) }
    val subtitle = contactSubtitle(contact)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(avatarLetter(name), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
