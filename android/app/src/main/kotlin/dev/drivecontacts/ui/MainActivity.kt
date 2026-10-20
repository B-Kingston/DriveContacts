package dev.drivecontacts.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.drivecontacts.AppGraph
import dev.drivecontacts.AppGraphProvider
import dev.drivecontacts.AuthState

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (applicationContext as AppGraphProvider).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DriveContactsTheme {
                DriveContactsRoot()
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
private fun DriveContactsRoot() {
    val authViewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory)
    val state by authViewModel.state.collectAsStateWithLifecycle()
    if (state is AuthState.SignedIn) {
        DriveContactsNavHost(onSignOut = authViewModel::signOutAndWipe)
    } else {
        SignInScreen(
            state = state,
            onSignIn = authViewModel::startSignIn,
            onCancel = authViewModel::cancelSignIn,
        )
    }
}
