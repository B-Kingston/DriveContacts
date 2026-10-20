package dev.cardvault.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cardvault.AppGraph
import dev.cardvault.DeviceAccount
import dev.cardvault.ImportSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportState(
    /** Null until the accounts are loaded. */
    val accounts: List<DeviceAccount>? = null,
    val selected: Set<DeviceAccount> = emptySet(),
    val busy: Boolean = false,
    val summary: ImportSummary? = null,
    val failed: Boolean = false,
)

class ImportViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state

    /** The caller has the READ_CONTACTS permission. */
    fun loadAccounts() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, failed = false) }
            try {
                val accounts = graph.importer.deviceAccounts()
                _state.update { it.copy(accounts = accounts, selected = it.selected.intersect(accounts.toSet()), busy = false) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, failed = true) }
            }
        }
    }

    fun toggle(account: DeviceAccount) {
        _state.update { it.copy(selected = if (account in it.selected) it.selected - account else it.selected + account) }
    }

    fun importSelected() {
        val accounts = _state.value.accounts.orEmpty().filter { it in _state.value.selected }
        if (accounts.isEmpty()) return
        runImport { graph.importer.importFromDevice(accounts) }
    }

    fun importFile(uri: Uri) = runImport { graph.importer.importFromVCard(uri) }

    fun summaryShown() = _state.update { it.copy(summary = null, selected = emptySet()) }

    fun failureShown() = _state.update { it.copy(failed = false) }

    private fun runImport(action: suspend () -> ImportSummary) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, failed = false) }
            try {
                val summary = action()
                _state.update { it.copy(busy = false, summary = summary) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, failed = true) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { ImportViewModel(appGraph) } }
    }
}
