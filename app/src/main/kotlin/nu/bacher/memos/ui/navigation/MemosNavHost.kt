package nu.bacher.memos.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.ui.edit.MemoEditScreen
import nu.bacher.memos.ui.list.MemoListScreen
import nu.bacher.memos.ui.login.LoginScreen

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

@HiltViewModel
class RootViewModel @Inject constructor(
    authStore: AuthStore,
) : ViewModel() {
    val isAuthenticated: StateFlow<Boolean?> = authStore.config
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun MemosNavHost(
    initialMemoName: String? = null,
    openNewMemoOnStart: Boolean = false,
    initialContent: String? = null,
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val authState by rootViewModel.isAuthenticated.collectAsState()

    LaunchedEffect(authState) {
        val auth = authState ?: return@LaunchedEffect
        val start = when {
            !auth -> Routes.LOGIN
            openNewMemoOnStart -> Routes.edit(name = null, initial = initialContent)
            initialMemoName != null -> Routes.edit(name = initialMemoName)
            else -> Routes.LIST
        }
        navController.navigate(start) {
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
