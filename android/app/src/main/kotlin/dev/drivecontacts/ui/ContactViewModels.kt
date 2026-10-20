package dev.drivecontacts.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.drivecontacts.AppGraph
import dev.drivecontacts.R
import dev.drivecontacts.core.model.Contact
import dev.drivecontacts.core.model.Kind
import dev.drivecontacts.core.model.PostalAddress
import dev.drivecontacts.core.model.TypedValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the detail screen knows about its contact. */
sealed interface ContactLookup {
    /** The contacts are not read yet. */
    data object Loading : ContactLookup

    /** The contacts file could not be read. */
    data class Failed(val message: String) : ContactLookup

    /** The contact does not exist (for example after a delete). */
    data object Gone : ContactLookup
    data class Found(val contact: Contact) : ContactLookup
}

class ContactDetailViewModel(private val graph: AppGraph, private val uid: String) : ViewModel() {
    private fun lookup(list: List<Contact>, loaded: Boolean, loadError: String?): ContactLookup {
        val found = list.firstOrNull { it.uid == uid }
        return when {
            found != null -> ContactLookup.Found(found)
            loaded -> ContactLookup.Gone
            loadError != null -> ContactLookup.Failed(loadError)
            else -> ContactLookup.Loading
        }
    }

    val contact: StateFlow<ContactLookup> = combine(graph.contacts.contacts, graph.contacts.loaded, graph.contacts.loadError, ::lookup)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialLookup())

    /** The loader sets the contacts before [loaded]. So read [loaded] first: a later read of the contacts cannot be stale. */
    private fun initialLookup(): ContactLookup {
        val loaded = graph.contacts.loaded.value
        return lookup(graph.contacts.contacts.value, loaded, graph.contacts.loadError.value)
    }

    private val _error = MutableStateFlow<Int?>(null)

    /** A string resource id of the last error, or null. */
    val error: StateFlow<Int?> = _error

    fun errorShown() {
        _error.value = null
    }

    /** The screen closes when the contact is gone. */
    fun delete() {
        viewModelScope.launch {
            attempt({ _error.value = R.string.delete_failed }) { graph.contacts.delete(uid) }
        }
    }

    companion object {
        fun factory(uid: String) = viewModelFactory {
            initializer { ContactDetailViewModel(appGraph, uid) }
        }
    }
}

/** The kinds of list rows in the edit form. */
enum class RowList { PHONES, EMAILS, ADDRESSES, URLS }

/** Stable ids of the rows of the form lists. A row keeps its id when other rows come or go. */
@Serializable
data class RowIds(
    val phones: List<Long> = emptyList(),
    val emails: List<Long> = emptyList(),
    val addresses: List<Long> = emptyList(),
    val urls: List<Long> = emptyList(),
) {
    fun of(list: RowList): List<Long> = when (list) {
        RowList.PHONES -> phones
        RowList.EMAILS -> emails
        RowList.ADDRESSES -> addresses
        RowList.URLS -> urls
    }

    fun with(list: RowList, ids: List<Long>): RowIds = when (list) {
        RowList.PHONES -> copy(phones = ids)
        RowList.EMAILS -> copy(emails = ids)
        RowList.ADDRESSES -> copy(addresses = ids)
        RowList.URLS -> copy(urls = ids)
    }

    val max: Long get() = (phones + emails + addresses + urls).maxOrNull() ?: -1L
}

/**
 * The edit form as the saved state keeps it, so that the form survives the death of the process.
 * [original] is null for a new contact and when it equals the draft ([isNew] tells which).
 */
@Serializable
private data class EditSession(val isNew: Boolean, val original: Contact?, val draft: Contact, val ids: RowIds)

class ContactEditViewModel(
    private val graph: AppGraph,
    private val uid: String?,
    private val handle: SavedStateHandle,
) : ViewModel() {
    /** False until the contacts are read and the form is set up. */
    var ready: Boolean by mutableStateOf(false)
        private set

    /** The contacts file error, or null. The screen shows it instead of the progress. */
    val loadError: StateFlow<String?> = graph.contacts.loadError

    /** The contact when the form opened, or null for a new contact. */
    private var original: Contact? by mutableStateOf(null)

    val isNew: Boolean get() = original == null

    /** The form state. The screen edits it through [update] and the row functions. */
    var draft: Contact by mutableStateOf(Contact(uid = uid ?: Contact.newUid()))
        private set

    var rowIds: RowIds by mutableStateOf(RowIds())
        private set

    private var nextId = 0L

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    private val _error = MutableStateFlow<Int?>(null)

    /** A string resource id of the last error, or null. */
    val error: StateFlow<Int?> = _error

    private val prepared: Contact by derivedStateOf { prepareForSave(draft) }
    private val preparedOriginal: Contact by derivedStateOf { prepareForSave(original ?: Contact(uid = draft.uid)) }

    val canSave: Boolean by derivedStateOf { ready && hasContent(prepared) }

    /** True when the form differs from the contact that it started with. */
    val changed: Boolean by derivedStateOf { ready && !prepared.sameContentAs(preparedOriginal) }

    init {
        val restored = handle.get<String>(SESSION_KEY)?.let { runCatching { Json.decodeFromString<EditSession>(it) }.getOrNull() }
        if (restored != null) {
            open(if (restored.isNew) null else restored.original ?: restored.draft, restored.draft, restored.ids)
        } else {
            viewModelScope.launch {
                graph.contacts.loaded.first { it }
                val existing = uid?.let { id -> graph.contacts.contacts.value.firstOrNull { it.uid == id } }
                val form = existing ?: draft
                open(existing, form, RowIds(
                    phones = newIds(form.phones.size), emails = newIds(form.emails.size),
                    addresses = newIds(form.addresses.size), urls = newIds(form.urls.size),
                ))
            }
        }
    }

    private fun open(original: Contact?, form: Contact, ids: RowIds) {
        this.original = original
        draft = form
        rowIds = ids
        nextId = ids.max + 1
        ready = true
        persist()
    }

    private fun newIds(count: Int): List<Long> = List(count) { nextId++ }

    /** Keeps the form in the saved state. A form that is too big for the saved state is not kept. */
    private fun persist() {
        val kept = original?.takeIf { it != draft }
        val json = Json.encodeToString(EditSession(original == null, kept, draft, rowIds))
        if (json.length <= MAX_SESSION_CHARS) handle[SESSION_KEY] = json else handle.remove<String>(SESSION_KEY)
    }

    /** Changes values of the form. It must not add or remove list rows; use [addRow] and [removeRow] for that. */
    fun update(change: (Contact) -> Contact) {
        draft = change(draft)
        persist()
    }

    fun addRow(list: RowList) {
        draft = when (list) {
            RowList.PHONES -> draft.copy(phones = draft.phones + TypedValue(Kind.MOBILE, ""))
            RowList.EMAILS -> draft.copy(emails = draft.emails + TypedValue(Kind.HOME, ""))
            RowList.ADDRESSES -> draft.copy(addresses = draft.addresses + PostalAddress(kind = Kind.HOME))
            RowList.URLS -> draft.copy(urls = draft.urls + "")
        }
        rowIds = rowIds.with(list, rowIds.of(list) + nextId++)
        persist()
    }

    fun removeRow(list: RowList, index: Int) {
        draft = when (list) {
            RowList.PHONES -> draft.copy(phones = draft.phones.filterIndexed { i, _ -> i != index })
            RowList.EMAILS -> draft.copy(emails = draft.emails.filterIndexed { i, _ -> i != index })
            RowList.ADDRESSES -> draft.copy(addresses = draft.addresses.filterIndexed { i, _ -> i != index })
            RowList.URLS -> draft.copy(urls = draft.urls.filterIndexed { i, _ -> i != index })
        }
        rowIds = rowIds.with(list, rowIds.of(list).filterIndexed { i, _ -> i != index })
        persist()
    }

    fun errorShown() {
        _error.value = null
    }

    /**
     * Saves the form. The saved contact keeps the changes that a sync made to fields that the user did not change.
     * The merge runs under the lock of the repository, so no sync commit lands between the read and the write.
     * On a failure the form stays open.
     */
    fun save() {
        if (!canSave) return
        val form = draft
        val base = original
        viewModelScope.launch {
            val saved = attempt({ _error.value = R.string.save_failed }) {
                // A restored form can be saved before the contacts are read. Wait for the read, and fail if it failed.
                val (loaded, _) = combine(graph.contacts.loaded, graph.contacts.loadError) { l, e -> l to e }
                    .first { (l, e) -> l || e != null }
                check(loaded) { "The contacts file could not be read" }
                graph.contacts.update(form.uid) { current -> mergeEdit(base, current, form) }
            }
            if (saved) _saved.value = true
        }
    }

    companion object {
        private const val SESSION_KEY = "session"

        /** The saved state has a size limit of about 1 MB. Text in a bundle takes two bytes a character. */
        private const val MAX_SESSION_CHARS = 200_000

        fun factory(uid: String?) = viewModelFactory {
            initializer { ContactEditViewModel(appGraph, uid, createSavedStateHandle()) }
        }
    }
}
