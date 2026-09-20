package nu.bacher.memos.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import nu.bacher.memos.ui.edit.MemoEditScreen
import nu.bacher.memos.ui.list.MemoListScreen
import nu.bacher.memos.ui.login.LoginScreen
import nu.bacher.memos.ui.settings.LicensesScreen
import nu.bacher.memos.ui.settings.SettingsScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * Where the user should land when MainActivity is started or re-started by a
 * deep-link Intent. [MainActivity] re-derives this from each Intent and
 * forwards the value to [MemosNavHost], which navigates whenever the value
 * changes (so a foreground reminder tap actually opens the memo instead of
 * being dropped).
 */
sealed interface NavLaunch {
    data object None : NavLaunch
    data class OpenMemo(val memoName: String) : NavLaunch
    data class NewMemo(val initialContent: String?) : NavLaunch
}

@Serializable private data object Splash
@Serializable private data object Login
@Serializable private data object MemoList
@Serializable private data object Settings
@Serializable private data object Licenses
@Serializable private data class MemoEdit(
    val name: String? = null,
    val initial: String? = null,
    val startInEditMode: Boolean = false,
)

@Composable
fun MemosNavHost(
    launch: NavLaunch = NavLaunch.None,
    /**
     * SSO callback URI from the identity provider, or null. Not a [NavLaunch]
     * variant: it doesn't pick a destination, it completes work already in
     * flight on the login screen.
     */
    ssoRedirect: String? = null,
    /**
     * Called once [ssoRedirect] has been handed to the login screen. Without
     * it the value would sit in MainActivity for the life of the process and
     * re-fire — a stale redirect greeting the user with a failure the next
     * time they land on the login screen, after a logout.
     */
    onSsoRedirectConsumed: () -> Unit = {},
    rootViewModel: RootViewModel = koinViewModel(),
) {
    val navController = rememberNavController()
    val authState by rootViewModel.isAuthenticated.collectAsState()

    // Re-navigate on (auth-resolution, launch) changes. Two triggers:
    //   1. Initial auth resolution — pick the start destination.
    //   2. A new Intent arriving via onNewIntent (e.g. notification while
    //      foregrounded) bumps [launch] and we route to the right screen
    //      without dropping the deep link.
    LaunchedEffect(authState, launch) {
        val auth = authState ?: return@LaunchedEffect
        if (!auth) {
            // Signed out: the stack may hold screens this user can't see.
            navController.navigate(Login) { popUpTo(0) { inclusive = true } }
            return@LaunchedEffect
        }
        // Signed in. Rebuild the stack with the list at its root, then push
        // the deep-link destination on top of it — so Back from a memo opened
        // by a notification or share lands on the list rather than dropping
        // the user out of the app.
        navController.navigate(MemoList) { popUpTo(0) { inclusive = true } }
        when (launch) {
            is NavLaunch.NewMemo ->
                navController.navigate(MemoEdit(name = null, initial = launch.initialContent))
            is NavLaunch.OpenMemo ->
                navController.navigate(MemoEdit(name = launch.memoName))
            NavLaunch.None -> Unit
        }
    }

    NavHost(
        navController = navController,
        startDestination = Splash,
    ) {
        composable<Splash> { /* empty splash while we resolve auth */ }

        composable<Login> {
            LoginScreen(
                ssoRedirect = ssoRedirect,
                onSsoRedirectConsumed = onSsoRedirectConsumed,
                onLoggedIn = {
                    navController.navigate(MemoList) {
                        popUpTo<Login> { inclusive = true }
                    }
                },
            )
        }

        composable<MemoList> {
            MemoListScreen(
                onOpenMemo = { name -> navController.navigate(MemoEdit(name = name)) },
                onEditMemo = { name ->
                    navController.navigate(MemoEdit(name = name, startInEditMode = true))
                },
                onCreateMemo = { navController.navigate(MemoEdit()) },
                onOpenSettings = { navController.navigate(Settings) },
            )
        }

        composable<Settings> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenLicenses = { navController.navigate(Licenses) },
                onLoggedOut = {
                    navController.navigate(Login) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable<Licenses> {
            LicensesScreen(onBack = { navController.popBackStack() })
        }

        composable<MemoEdit> { backStackEntry ->
            val args = backStackEntry.toRoute<MemoEdit>()
            MemoEditScreen(
                memoName = args.name,
                initialContent = args.initial,
                startInEditMode = args.startInEditMode,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
