package dev.drivecontacts.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import dev.drivecontacts.R
import dev.drivecontacts.core.model.Kind
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
    }
}

@Composable
fun kindName(kind: Kind): String = stringResource(
    when (kind) {
        Kind.MOBILE -> R.string.kind_mobile
        Kind.HOME -> R.string.kind_home
        Kind.WORK -> R.string.kind_work
        Kind.MAIN -> R.string.kind_main
        Kind.FAX_HOME -> R.string.kind_fax_home
        Kind.FAX_WORK -> R.string.kind_fax_work
        Kind.FAX_OTHER -> R.string.kind_fax_other
        Kind.PAGER -> R.string.kind_pager
        Kind.OTHER -> R.string.kind_other
        Kind.CUSTOM -> R.string.kind_custom
    },
)

/** The label that the detail view shows for a kind. A custom kind shows its own label. */
@Composable
fun kindText(kind: Kind, customLabel: String): String =
    if (kind == Kind.CUSTOM && customLabel.isNotEmpty()) customLabel else kindName(kind)

/** Runs [block]. A failure other than a cancellation calls [onFailure] and gives false. */
internal suspend fun attempt(onFailure: () -> Unit, block: suspend () -> Unit): Boolean =
    try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure()
        false
    }

/** Shows the error text of [error] in [snackbar], then calls [onShown]. */
@Composable
fun ErrorSnackbar(error: Int?, snackbar: SnackbarHostState, onShown: () -> Unit) {
    val text = error?.let { stringResource(it) }
    LaunchedEffect(text) {
        if (text != null) {
            snackbar.showSnackbar(text)
            onShown()
        }
    }
}
