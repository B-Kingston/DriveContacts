package dev.drivecontacts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.drivecontacts.R
import dev.drivecontacts.core.model.Contact

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(uid: String, onBack: () -> Unit, onGone: () -> Unit, onEdit: () -> Unit) {
    val viewModel: ContactDetailViewModel = viewModel(key = uid, factory = ContactDetailViewModel.factory(uid))
    val lookup by viewModel.contact.collectAsStateWithLifecycle()
    val contact = (lookup as? ContactLookup.Found)?.contact
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    ErrorSnackbar(error, snackbar, viewModel::errorShown)
    LaunchedEffect(lookup is ContactLookup.Gone) { if (lookup is ContactLookup.Gone) onGone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contact?.let { displayName(it) }.orEmpty()) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (contact != null) {
            DetailBody(contact, Modifier.padding(padding))
        } else {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                when (val l = lookup) {
                    is ContactLookup.Loading -> CircularProgressIndicator()
                    is ContactLookup.Failed -> Text(
                        stringResource(R.string.load_failed, l.message),
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {}
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_contact_title)) },
            text = { Text(stringResource(R.string.delete_contact_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun DetailBody(c: Contact, modifier: Modifier) {
    SelectionContainer {
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                displayName(c).ifEmpty { stringResource(R.string.unnamed_contact) },
                style = MaterialTheme.typography.headlineMedium,
            )
            val structured = joinNameParts(c.name)
            if (structured.isNotEmpty() && structured != c.formattedName) Item(stringResource(R.string.field_name), structured)
            Item(stringResource(R.string.field_nickname), c.nickname)
            Item(
                stringResource(R.string.field_organization),
                listOf(c.organization.name, c.organization.department).filter { it.isNotEmpty() }.joinToString(", "),
            )
            Item(stringResource(R.string.field_title), c.title)
            c.phones.forEach { Item(kindText(it.kind, it.customLabel), it.value) }
            c.emails.forEach { Item(kindText(it.kind, it.customLabel), it.value) }
            c.addresses.forEach { a ->
                val lines = listOf(a.poBox, a.extended, a.street, listOf(a.postalCode, a.locality).filter { it.isNotEmpty() }.joinToString(" "), a.region, a.country)
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
                Item(kindText(a.kind, a.customLabel), lines)
            }
            c.urls.forEach { Item(stringResource(R.string.field_website), it) }
            Item(stringResource(R.string.field_birthday), c.birthday)
            Item(stringResource(R.string.field_note), c.note)
            if (c.extras.isNotEmpty()) {
                Text(
                    pluralStringResource(R.plurals.other_properties_kept, c.extras.size, c.extras.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Item(label: String, value: String) {
    if (value.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
