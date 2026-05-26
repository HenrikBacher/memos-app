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
@Serializable private data class MemoEdit(
    val name: String? = null,
    val initial: String? = null,
)

@Composable
fun MemosNavHost(
    launch: NavLaunch = NavLaunch.None,
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
        val target: Any = when {
            !auth -> Login
            launch is NavLaunch.NewMemo -> MemoEdit(name = null, initial = launch.initialContent)
            launch is NavLaunch.OpenMemo -> MemoEdit(name = launch.memoName)
            else -> MemoList
        }
        navController.navigate(target) {
            popUpTo(0) { inclusive = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Splash,
    ) {
        composable<Splash> { /* empty splash while we resolve auth */ }

        composable<Login> {
            LoginScreen(onLoggedIn = {
                navController.navigate(MemoList) {
                    popUpTo<Login> { inclusive = true }
                }
            })
        }

        composable<MemoList> {
            MemoListScreen(
                onOpenMemo = { name -> navController.navigate(MemoEdit(name = name)) },
                onCreateMemo = { navController.navigate(MemoEdit()) },
                onLogout = {
                    navController.navigate(Login) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable<MemoEdit> { backStackEntry ->
            val args = backStackEntry.toRoute<MemoEdit>()
            MemoEditScreen(
                memoName = args.name,
                initialContent = args.initial,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
