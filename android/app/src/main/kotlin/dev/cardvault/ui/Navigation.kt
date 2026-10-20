package dev.cardvault.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/** Navigation routes. A contact UID is URL-encoded in the path. */
object Routes {
    const val UID_ARG = "uid"

    const val LIST = "contacts"
    const val DETAIL = "contact/{$UID_ARG}"
    const val EDIT_NEW = "edit"
    const val EDIT = "edit/{$UID_ARG}"
    const val CONFLICTS = "conflicts"
    const val CONFLICT = "conflict/{$UID_ARG}"
    const val COMPARE = "compare"
    const val IMPORT = "import"

    fun detail(uid: String) = "contact/${Uri.encode(uid)}"
    fun edit(uid: String) = "edit/${Uri.encode(uid)}"
    fun conflict(uid: String) = "conflict/${Uri.encode(uid)}"
}

private val uidArgs = listOf(navArgument(Routes.UID_ARG) { type = NavType.StringType })

@Composable
fun CardvaultNavHost(onSignOut: () -> Unit, navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            ContactListScreen(
                onOpenContact = { navController.navigate(Routes.detail(it)) },
                onAdd = { navController.navigate(Routes.EDIT_NEW) },
                onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                onImport = { navController.navigate(Routes.IMPORT) },
                onSignOut = onSignOut,
            )
        }
        composable(Routes.DETAIL, uidArgs) { entry ->
            val uid = entry.arguments?.getString(Routes.UID_ARG).orEmpty()
            ContactDetailScreen(
                uid = uid,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Routes.edit(uid)) },
            )
        }
        composable(Routes.EDIT_NEW) {
            ContactEditScreen(uid = null, onDone = { navController.popBackStack() })
        }
        composable(Routes.EDIT, uidArgs) { entry ->
            val uid = entry.arguments?.getString(Routes.UID_ARG).orEmpty()
            ContactEditScreen(uid = uid, onDone = { navController.popBackStack() })
        }
        composable(Routes.CONFLICTS) {
            ConflictsScreen(
                onBack = { navController.popBackStack() },
                onOpenConflict = { navController.navigate(Routes.conflict(it)) },
                onCompareFiles = { navController.navigate(Routes.COMPARE) },
            )
        }
        composable(Routes.CONFLICT, uidArgs) { entry ->
            val uid = entry.arguments?.getString(Routes.UID_ARG).orEmpty()
            ConflictDetailScreen(uid = uid, onBack = { navController.popBackStack() })
        }
        composable(Routes.COMPARE) {
            CompareFilesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.IMPORT) {
            ImportScreen(onBack = { navController.popBackStack() })
        }
    }
}
