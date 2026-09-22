package nu.bacher.memos.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.data.repo.ReminderRepository
import nu.bacher.memos.data.settings.ThemeMode
import nu.bacher.memos.data.settings.ThemePreferences

class SettingsViewModel(
    private val themePrefs: ThemePreferences,
    private val authStore: AuthStore,
    private val memoRepo: MemoRepository,
    private val reminderRepo: ReminderRepository,
) : ViewModel() {

    val theme = themePrefs.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = themePrefs.read(),
    )

    fun setThemeMode(mode: ThemeMode) = themePrefs.setMode(mode)

    fun setDynamicColor(enabled: Boolean) = themePrefs.setDynamicColor(enabled)

    /**
     * Sign out and wipe everything the previous account left on the device.
     *
     * Auth is cleared first so no in-flight sync or refresh can refill the
     * cache behind the wipe. That also navigates away and clears this
     * ViewModel, so the whole sequence runs NonCancellable — otherwise the
     * wipe could be cancelled halfway. Coil's image caches are cleared by
     * MemosApp, which watches for the sign-out.
     */
    fun logout() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                authStore.clear()
                memoRepo.clearCache()
                reminderRepo.clearAll()
            }
        }
    }
}
