package nu.bacher.memos.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import nu.bacher.memos.data.auth.AuthStore

class RootViewModel(authStore: AuthStore) : ViewModel() {
    val isAuthenticated: StateFlow<Boolean?> = authStore.config
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
