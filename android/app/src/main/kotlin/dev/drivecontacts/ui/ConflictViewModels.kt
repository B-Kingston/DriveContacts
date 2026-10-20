package dev.drivecontacts.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.drivecontacts.AppGraph
import dev.drivecontacts.R
import dev.drivecontacts.core.Conflict
import dev.drivecontacts.core.ContactDiff
import dev.drivecontacts.core.Diff
import dev.drivecontacts.core.Field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConflictsViewModel(graph: AppGraph) : ViewModel() {
    val conflicts: StateFlow<List<Conflict>> = graph.conflicts.conflicts

    companion object {
        val Factory = viewModelFactory { initializer { ConflictsViewModel(appGraph) } }
    }
}

class ConflictDetailViewModel(private val graph: AppGraph, private val uid: String) : ViewModel() {
    val conflicts: StateFlow<List<Conflict>> = graph.conflicts.conflicts

    /** The fields for which the user picked the local value. The default is the remote value. */
    var useLocal: Set<Field> by mutableStateOf(emptySet())
        private set

    var showOriginal: Boolean by mutableStateOf(false)

    fun choose(field: Field, local: Boolean) {
        useLocal = if (local) useLocal + field else useLocal - field
    }

    fun chooseAll(fields: List<Field>, local: Boolean) {
        useLocal = if (local) fields.toSet() else emptySet()
    }

    fun apply() = resolve { graph.conflicts.resolveFields(uid, useLocal) }

    fun delete() = resolve { graph.conflicts.resolveDelete(uid) }

    fun keep() = resolve { graph.conflicts.resolveKeep(uid) }

    private val _error = MutableStateFlow<Int?>(null)

    /** A string resource id of the last error, or null. */
    val error: StateFlow<Int?> = _error

    fun errorShown() {
        _error.value = null
    }

    /** The repository closes the conflict. The screen closes when the conflict is gone. A failure keeps the screen open. */
    private fun resolve(action: suspend () -> Unit) {
        viewModelScope.launch { attempt({ _error.value = R.string.resolve_failed }) { action() } }
    }

    companion object {
        fun factory(uid: String) = viewModelFactory { initializer { ConflictDetailViewModel(appGraph, uid) } }
    }
}

sealed interface CompareState {
    data object Loading : CompareState
    data object Empty : CompareState
    data class Loaded(val diffs: List<ContactDiff>) : CompareState
}

class CompareFilesViewModel(graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow<CompareState>(CompareState.Loading)
    val state: StateFlow<CompareState> = _state

    init {
        viewModelScope.launch {
            val files = graph.conflicts.lastConflictFiles()
            _state.value = if (files == null) {
                CompareState.Empty
            } else {
                CompareState.Loaded(withContext(Dispatchers.Default) { Diff.diff(files.first, files.second) })
            }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { CompareFilesViewModel(appGraph) } }
    }
}
