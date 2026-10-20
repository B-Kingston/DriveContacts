package dev.cardvault.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cardvault.AppGraph
import dev.cardvault.AppGraphProvider
import dev.cardvault.AuthState

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (applicationContext as AppGraphProvider).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CardvaultTheme {
                CardvaultRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.sync.setForeground(true)
    }

    override fun onStop() {
        graph.sync.setForeground(false)
        super.onStop()
    }
}

/** Shows the sign-in screen until the user is signed in. Then it shows the navigation graph. */
@Composable
private fun CardvaultRoot() {
    val authViewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory)
    val state by authViewModel.state.collectAsStateWithLifecycle()
    if (state is AuthState.SignedIn) {
        CardvaultNavHost(onSignOut = authViewModel::signOutAndWipe)
    } else {
        SignInScreen(
            state = state,
            onSignIn = authViewModel::startSignIn,
            onCancel = authViewModel::cancelSignIn,
        )
    }
}
