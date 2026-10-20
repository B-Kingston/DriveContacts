package dev.cardvault.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cardvault.AppGraph
import dev.cardvault.AppGraphProvider
import dev.cardvault.AuthState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal val CreationExtras.application: Application get() = this[APPLICATION_KEY] as Application

/** The app graph from the Application class. */
internal val CreationExtras.appGraph: AppGraph get() = (application as AppGraphProvider).graph

/** Remembers whether the user closed the first-run card. */
class OnboardingPrefs(private val prefs: SharedPreferences) {
    var dismissed: Boolean
        get() = prefs.getBoolean(KEY_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_DISMISSED, value).apply()

    fun clear() = prefs.edit().remove(KEY_DISMISSED).apply()

    companion object {
        private const val FILE = "cardvault_ui"
        private const val KEY_DISMISSED = "onboarding_dismissed"

        fun of(app: Application) = OnboardingPrefs(app.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/** Activity-wide view model. Its scope lives as long as the activity, so a sign-out is not cancelled by navigation. */
class AuthViewModel(private val graph: AppGraph, private val prefs: OnboardingPrefs) : ViewModel() {
    val state: StateFlow<AuthState> = graph.auth.state

    fun startSignIn() = graph.auth.startSignIn()

    fun cancelSignIn() = graph.auth.cancelSignIn()

    fun signOutAndWipe() {
        viewModelScope.launch {
            graph.signOutAndWipe()
            prefs.clear()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { AuthViewModel(appGraph, OnboardingPrefs.of(application)) }
        }
    }
}
