package dev.cardvault.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cardvault.R
import dev.cardvault.core.model.Kind

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
