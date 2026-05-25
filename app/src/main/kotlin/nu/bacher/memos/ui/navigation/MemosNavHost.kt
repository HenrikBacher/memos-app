package nu.bacher.memos.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

object Routes {
    const val LOGIN = "login"
    const val LIST = "list"
    const val EDIT_ROUTE = "edit?name={name}&initial={initial}"
    fun edit(name: String? = null, initial: String? = null): String {
        val n = name?.let { java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val i = initial?.let { java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        return "edit?name=$n&initial=$i"
    }
}

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
        val target = when {
            !auth -> Routes.LOGIN
            launch is NavLaunch.NewMemo -> Routes.edit(name = null, initial = launch.initialContent)
            launch is NavLaunch.OpenMemo -> Routes.edit(name = launch.memoName)
            else -> Routes.LIST
        }
        navController.navigate(target) {
            popUpTo(0) { inclusive = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "splash",
    ) {
        composable("splash") { /* empty splash while we resolve auth */ }

        composable(Routes.LOGIN) {
            LoginScreen(onLoggedIn = {
                navController.navigate(Routes.LIST) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            })
        }

        composable(Routes.LIST) {
            MemoListScreen(
                onOpenMemo = { name -> navController.navigate(Routes.edit(name)) },
                onCreateMemo = { navController.navigate(Routes.edit(null)) },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.EDIT_ROUTE,
            arguments = listOf(
                navArgument("name") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                },
                navArgument("initial") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                },
            ),
        ) { backStackEntry ->
            fun decode(arg: String) = backStackEntry.arguments?.getString(arg)
                ?.takeIf { it.isNotBlank() }
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            MemoEditScreen(
                memoName = decode("name"),
                initialContent = decode("initial"),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
