package nu.bacher.memos.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.data.settings.ThemeMode
import nu.bacher.memos.data.settings.ThemePreferences
import nu.bacher.memos.data.settings.ThemeSettings

class SettingsViewModel(
    private val themePrefs: ThemePreferences,
    private val authStore: AuthStore,
    private val memoRepo: MemoRepository,
) : ViewModel() {

    val theme = themePrefs.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = themePrefs.read(),
    )

    fun setThemeMode(mode: ThemeMode) = themePrefs.setMode(mode)

    fun setDynamicColor(enabled: Boolean) = themePrefs.setDynamicColor(enabled)

    /**
     * Mirror of [nu.bacher.memos.ui.list.MemoListViewModel.logout]. Kept here
     * so the settings screen can drive sign-out without a back-and-forth
     * through the list view-model.
     */
    fun logout() {
        viewModelScope.launch { memoRepo.clearCache() }
        authStore.clear()
    }

    @Suppress("unused")
    fun currentTheme(): ThemeSettings = themePrefs.read()
}
