package dev.cardvault.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cardvault.AuthState
import dev.cardvault.R

@Composable
fun SignInScreen(state: AuthState, onSignIn: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var openedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var browserMissing by rememberSaveable { mutableStateOf(false) }
    val waitingUrl = (state as? AuthState.WaitingForBrowser)?.signInUrl

    // Opens the sign-in page one time for each URL.
    LaunchedEffect(waitingUrl) {
        if (waitingUrl != null && waitingUrl != openedUrl) {
            openedUrl = waitingUrl
            browserMissing = !openCustomTab(context, waitingUrl)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.sign_in_explanation),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.sign_in_disclosure),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            when (state) {
                is AuthState.Loading -> CircularProgressIndicator()
                is AuthState.WaitingForBrowser -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.sign_in_waiting), textAlign = TextAlign.Center)
                    if (browserMissing) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sign_in_no_browser),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { browserMissing = !openCustomTab(context, state.signInUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.sign_in_open_again)) }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                is AuthState.Failed -> {
                    Text(
                        stringResource(R.string.sign_in_failed, state.message),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.try_again))
                    }
                }
                else -> Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sign_in_button))
                }
            }
        }
    }
}

/** Opens [url] in a Custom Tab. Gives false when no browser can open it. */
private fun openCustomTab(context: Context, url: String): Boolean = try {
    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    true
} catch (_: ActivityNotFoundException) {
    false
}
