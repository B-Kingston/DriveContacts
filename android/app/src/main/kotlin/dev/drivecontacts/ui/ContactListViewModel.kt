package dev.drivecontacts.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.drivecontacts.AppGraph
import dev.drivecontacts.AuthState
import dev.drivecontacts.R
import dev.drivecontacts.SyncStatus
import dev.drivecontacts.core.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactListState(
    /** The contacts that match the search, in display order. */
    val contacts: List<Contact> = emptyList(),
    val totalCount: Int = 0,
    /** False until the contacts are read. The list shows progress, not "no contacts", before that. */
    val loaded: Boolean = false,
    /** Not null when the contacts file could not be read. */
    val loadError: String? = null,
    val status: SyncStatus = SyncStatus(),
    val conflictCount: Int = 0,
    val phoneEnabled: Boolean = false,
    val showOnboarding: Boolean = false,
)

private data class SearchResult(val contacts: List<Contact>, val total: Int, val loaded: Boolean)

private class Indexed(val entries: List<ListEntry>, val loaded: Boolean)

/** Short pause after a keystroke before the search runs. */
private const val SEARCH_DEBOUNCE_MS = 150L

class ContactListViewModel(private val graph: AppGraph, private val prefs: OnboardingPrefs) : ViewModel() {
    private val dismissed = MutableStateFlow(prefs.dismissed)
    private val _error = MutableStateFlow<Int?>(null)

    /** The search text. It is Compose state, so the text field shows each keystroke at once. */
    var query: String by mutableStateOf("")
        private set

    /** A string resource id of the last error, or null. */
    val error: StateFlow<Int?> = _error

    /** The signed-in account, or null. The list screen shows it. */
    val email: StateFlow<String?> = graph.auth.state
        .map { (it as? AuthState.SignedIn)?.email }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), (graph.auth.state.value as? AuthState.SignedIn)?.email)

    // The flag goes through the same pipeline as the contacts, so "loaded" never pairs with an old, empty list.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val entries: Flow<Indexed> = graph.contacts.loaded
        .flatMapLatest { loaded -> graph.contacts.contacts.map { Indexed(indexContacts(it), loaded) } }
        .flowOn(Dispatchers.Default)

    @OptIn(FlowPreview::class)
    private val queryFlow: Flow<String> = snapshotFlow { query }
        .debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()

    /** The search result. It depends only on the contacts and the query, not on the sync status. */
    private val result: Flow<SearchResult> = combine(entries, queryFlow) { entries, query ->
        SearchResult(filterEntries(entries.entries, query), entries.entries.size, entries.loaded)
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<ContactListState> = combine(
        result,
        graph.contacts.loadError,
        graph.sync.status,
        graph.conflicts.conflicts,
        combine(graph.phoneSync.enabled, dismissed) { enabled, dismissed -> enabled to dismissed },
    ) { result, loadError, status, conflicts, (phoneEnabled, dismissed) ->
        val loaded = result.loaded
        ContactListState(
            contacts = result.contacts,
            totalCount = result.total,
            loaded = loaded,
            loadError = loadError,
            status = status,
            conflictCount = conflicts.size,
            phoneEnabled = phoneEnabled,
            showOnboarding = loaded && result.total == 0 && !phoneEnabled && !dismissed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactListState())

    fun updateQuery(value: String) {
        query = value
    }

    fun syncNow() = graph.sync.requestSync()

    fun dismissOnboarding() {
        prefs.dismissed = true
        dismissed.value = true
    }

    /** The caller has the contacts permissions. */
    fun enablePhone() {
        val email = (graph.auth.state.value as? AuthState.SignedIn)?.email ?: return
        dismissOnboarding()
        viewModelScope.launch {
            attempt({ _error.value = R.string.phone_enable_failed }) { graph.phoneSync.enable(email) }
        }
    }

    fun disablePhone() {
        viewModelScope.launch {
            attempt({ _error.value = R.string.phone_disable_failed }) { graph.phoneSync.disable() }
        }
    }

    fun permissionDenied() {
        _error.value = R.string.phone_permission_needed
    }

    fun errorShown() {
        _error.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ContactListViewModel(appGraph, OnboardingPrefs.of(application)) }
        }
    }
}
