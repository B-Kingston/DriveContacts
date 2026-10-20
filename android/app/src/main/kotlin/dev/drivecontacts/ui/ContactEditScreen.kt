package dev.drivecontacts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.drivecontacts.R
import dev.drivecontacts.core.model.Kind
import dev.drivecontacts.core.model.PostalAddress
import dev.drivecontacts.core.model.TypedValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditScreen(uid: String?, onDone: () -> Unit) {
    val viewModel: ContactEditViewModel = viewModel(key = uid ?: "new", factory = ContactEditViewModel.factory(uid))
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val c = viewModel.draft
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val changed = viewModel.changed

    LaunchedEffect(saved) { if (saved) onDone() }
    ErrorSnackbar(error, snackbar, viewModel::errorShown)
    BackHandler(enabled = changed) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (viewModel.isNew) R.string.new_contact else R.string.edit_contact)) },
                navigationIcon = {
                    IconButton(onClick = { if (changed) confirmDiscard = true else onDone() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = viewModel.canSave) { Text(stringResource(R.string.save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!viewModel.ready) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                val loadError by viewModel.loadError.collectAsStateWithLifecycle()
                val message = loadError
                if (message != null) {
                    Text(stringResource(R.string.load_failed, message), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                } else {
                    CircularProgressIndicator()
                }
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val autoName = autoFormattedName("", c.name)
            FormField(
                c.formattedName, { v -> viewModel.update { it.copy(formattedName = v) } },
                stringResource(R.string.field_formatted_name),
                supporting = if (c.formattedName.isBlank() && autoName.isNotEmpty()) autoName else null,
            )
            SectionTitle(R.string.field_name)
            FormField(c.name.prefix, { v -> viewModel.update { it.copy(name = it.name.copy(prefix = v)) } }, stringResource(R.string.name_prefix))
            FormField(c.name.given, { v -> viewModel.update { it.copy(name = it.name.copy(given = v)) } }, stringResource(R.string.name_given))
            FormField(c.name.additional, { v -> viewModel.update { it.copy(name = it.name.copy(additional = v)) } }, stringResource(R.string.name_additional))
            FormField(c.name.family, { v -> viewModel.update { it.copy(name = it.name.copy(family = v)) } }, stringResource(R.string.name_family))
            FormField(c.name.suffix, { v -> viewModel.update { it.copy(name = it.name.copy(suffix = v)) } }, stringResource(R.string.name_suffix))
            FormField(c.nickname, { v -> viewModel.update { it.copy(nickname = v) } }, stringResource(R.string.field_nickname))
            FormField(
                c.organization.name,
                { v -> viewModel.update { it.copy(organization = it.organization.copy(name = v)) } },
                stringResource(R.string.field_organization),
            )
            FormField(
                c.organization.department,
                { v -> viewModel.update { it.copy(organization = it.organization.copy(department = v)) } },
                stringResource(R.string.field_department),
            )
            FormField(c.title, { v -> viewModel.update { it.copy(title = v) } }, stringResource(R.string.field_title))

            SectionTitle(R.string.section_phones)
            TypedValueList(
                items = c.phones,
                ids = viewModel.rowIds.phones,
                kinds = PHONE_KINDS,
                keyboard = KeyboardType.Phone,
                valueLabel = stringResource(R.string.field_phone),
                addLabel = stringResource(R.string.add_phone),
                onChange = { list -> viewModel.update { it.copy(phones = list) } },
                onRemove = { viewModel.removeRow(RowList.PHONES, it) },
                onAdd = { viewModel.addRow(RowList.PHONES) },
            )
            SectionTitle(R.string.section_emails)
            TypedValueList(
                items = c.emails,
                ids = viewModel.rowIds.emails,
                kinds = EMAIL_KINDS,
                keyboard = KeyboardType.Email,
                valueLabel = stringResource(R.string.field_email),
                addLabel = stringResource(R.string.add_email),
                onChange = { list -> viewModel.update { it.copy(emails = list) } },
                onRemove = { viewModel.removeRow(RowList.EMAILS, it) },
                onAdd = { viewModel.addRow(RowList.EMAILS) },
            )
            SectionTitle(R.string.section_addresses)
            c.addresses.forEachIndexed { index, address ->
                key(viewModel.rowIds.addresses[index]) {
                    AddressEditor(
                        address,
                        onChange = { edited -> viewModel.update { it.copy(addresses = it.addresses.toMutableList().also { l -> l[index] = edited }) } },
                        onRemove = { viewModel.removeRow(RowList.ADDRESSES, index) },
                    )
                }
            }
            TextButton(onClick = { viewModel.addRow(RowList.ADDRESSES) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.add_address))
            }
            SectionTitle(R.string.section_websites)
            c.urls.forEachIndexed { index, url ->
                key(viewModel.rowIds.urls[index]) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { v -> viewModel.update { it.copy(urls = it.urls.toMutableList().also { l -> l[index] = v }) } },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.field_website)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        RemoveButton { viewModel.removeRow(RowList.URLS, index) }
                    }
                }
            }
            TextButton(onClick = { viewModel.addRow(RowList.URLS) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.add_website))
            }
            FormField(
                c.birthday, { v -> viewModel.update { it.copy(birthday = v) } },
                stringResource(R.string.field_birthday),
                supporting = stringResource(R.string.birthday_hint),
            )
            OutlinedTextField(
                value = c.note,
                onValueChange = { v -> viewModel.update { it.copy(note = v) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.field_note)) },
                minLines = 3,
            )
            if (c.extras.isNotEmpty()) {
                Text(
                    pluralStringResource(R.plurals.other_properties_kept, c.extras.size, c.extras.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDone() }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep_editing)) }
            },
        )
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun FormField(value: String, onChange: (String) -> Unit, label: String, supporting: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
    )
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove))
    }
}

@Composable
private fun TypedValueList(
    items: List<TypedValue>,
    ids: List<Long>,
    kinds: List<Kind>,
    keyboard: KeyboardType,
    valueLabel: String,
    addLabel: String,
    onChange: (List<TypedValue>) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    items.forEachIndexed { index, item ->
        key(ids[index]) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KindDropdown(
                            kind = item.kind,
                            kinds = kinds,
                            onSelect = { k -> onChange(items.toMutableList().also { it[index] = item.copy(kind = k) }) },
                            modifier = Modifier.weight(1f),
                        )
                        RemoveButton { onRemove(index) }
                    }
                    if (item.kind == Kind.CUSTOM) {
                        OutlinedTextField(
                            value = item.customLabel,
                            onValueChange = { v -> onChange(items.toMutableList().also { it[index] = item.copy(customLabel = v) }) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.custom_label)) },
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = item.value,
                        onValueChange = { v -> onChange(items.toMutableList().also { it[index] = item.copy(value = v) }) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(valueLabel) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    )
                }
            }
        }
    }
    TextButton(onClick = onAdd) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(addLabel)
    }
}

@Composable
private fun AddressEditor(address: PostalAddress, onChange: (PostalAddress) -> Unit, onRemove: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindDropdown(address.kind, ADDRESS_KINDS, { onChange(address.copy(kind = it)) }, Modifier.weight(1f))
                RemoveButton(onRemove)
            }
            if (address.kind == Kind.CUSTOM) {
                FormField(address.customLabel, { onChange(address.copy(customLabel = it)) }, stringResource(R.string.custom_label))
            }
            FormField(address.street, { onChange(address.copy(street = it)) }, stringResource(R.string.address_street))
            FormField(address.extended, { onChange(address.copy(extended = it)) }, stringResource(R.string.address_extended))
            FormField(address.poBox, { onChange(address.copy(poBox = it)) }, stringResource(R.string.address_po_box))
            FormField(address.postalCode, { onChange(address.copy(postalCode = it)) }, stringResource(R.string.address_postal_code))
            FormField(address.locality, { onChange(address.copy(locality = it)) }, stringResource(R.string.address_locality))
            FormField(address.region, { onChange(address.copy(region = it)) }, stringResource(R.string.address_region))
            FormField(address.country, { onChange(address.copy(country = it)) }, stringResource(R.string.address_country))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindDropdown(kind: Kind, kinds: List<Kind>, onSelect: (Kind) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = kindName(kind),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.field_type)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            kinds.forEach { option ->
                DropdownMenuItem(
                    text = { Text(kindName(option)) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}
