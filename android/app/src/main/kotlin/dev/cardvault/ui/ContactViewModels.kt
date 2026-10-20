package dev.cardvault.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cardvault.AppGraph
import dev.cardvault.core.model.Contact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ContactDetailViewModel(private val graph: AppGraph, private val uid: String) : ViewModel() {
    /** The contact, or null when it does not exist (for example after a delete). */
    val contact: StateFlow<Contact?> = graph.contacts.contacts
        .map { list -> list.firstOrNull { it.uid == uid } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.contacts.contacts.value.firstOrNull { it.uid == uid })

    /** The screen closes when the contact is gone. */
    fun delete() {
        viewModelScope.launch { graph.contacts.delete(uid) }
    }

    companion object {
        fun factory(uid: String) = viewModelFactory {
            initializer { ContactDetailViewModel(appGraph, uid) }
        }
    }
}

class ContactEditViewModel(private val graph: AppGraph, uid: String?) : ViewModel() {
    val isNew: Boolean
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    /** The form state. The screen edits it directly. */
    var draft: Contact by mutableStateOf(Contact(uid = uid ?: Contact.newUid()))
        private set

    init {
        val existing = uid?.let { id -> graph.contacts.contacts.value.firstOrNull { it.uid == id } }
        isNew = existing == null
        if (existing != null) draft = existing
    }

    fun update(change: (Contact) -> Contact) {
        draft = change(draft)
    }

    val canSave: Boolean get() = hasContent(prepareForSave(draft))

    fun save() {
        val contact = prepareForSave(draft)
        if (!hasContent(contact)) return
        viewModelScope.launch {
            graph.contacts.upsert(contact)
            _saved.value = true
        }
    }

    companion object {
        fun factory(uid: String?) = viewModelFactory {
            initializer { ContactEditViewModel(appGraph, uid) }
        }
    }
}
