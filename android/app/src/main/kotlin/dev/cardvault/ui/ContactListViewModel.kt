package dev.cardvault.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cardvault.AppGraph
import dev.cardvault.AuthState
import dev.cardvault.R
import dev.cardvault.SyncStatus
import dev.cardvault.core.model.Contact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContactListState(
    /** The contacts that match the search. */
    val contacts: List<Contact> = emptyList(),
    val totalCount: Int = 0,
    val query: String = "",
    val status: SyncStatus = SyncStatus(),
    val conflictCount: Int = 0,
    val phoneEnabled: Boolean = false,
    val showOnboarding: Boolean = false,
)

class ContactListViewModel(private val graph: AppGraph, private val prefs: OnboardingPrefs) : ViewModel() {
    private val query = MutableStateFlow("")
    private val dismissed = MutableStateFlow(prefs.dismissed)
    private val _error = MutableStateFlow<Int?>(null)

    /** A string resource id of the last error, or null. */
    val error: StateFlow<Int?> = _error

    val state: StateFlow<ContactListState> = combine(
        graph.contacts.contacts,
        query,
        graph.sync.status,
        graph.conflicts.conflicts,
        combine(graph.phoneSync.enabled, dismissed) { enabled, dismissed -> enabled to dismissed },
    ) { contacts, query, status, conflicts, (phoneEnabled, dismissed) ->
        ContactListState(
            contacts = filterContacts(contacts, query),
            totalCount = contacts.size,
            query = query,
            status = status,
            conflictCount = conflicts.size,
            phoneEnabled = phoneEnabled,
            showOnboarding = contacts.isEmpty() && !phoneEnabled && !dismissed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactListState())

    fun setQuery(value: String) = query.update { value }

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
            try {
                graph.phoneSync.enable(email)
            } catch (e: SecurityException) {
                _error.value = R.string.phone_enable_failed
            }
        }
    }

    fun disablePhone() {
        viewModelScope.launch { graph.phoneSync.disable() }
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
