package nu.bacher.memos.data.settings

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.getBooleanOrNullFlow
import com.russhwolf.settings.coroutines.getStringOrNullFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** When true, dynamic color schemes derived from the wallpaper are used (Android 12+). */
    val dynamicColor: Boolean = true,
)

/**
 * Persists Appearance settings — light/dark/system mode and the dynamic-color
 * opt-in. Backed by the same [ObservableSettings] as [LayoutPreferences] and
 * [nu.bacher.memos.data.auth.AuthStore]; the auth store does not wipe
 * unrelated keys on logout so these preferences survive sign-out.
 */
@OptIn(ExperimentalSettingsApi::class)
class ThemePreferences(private val settings: ObservableSettings) {

    val settingsFlow: Flow<ThemeSettings> = combine(
        settings.getStringOrNullFlow(KEY_MODE).map(::decodeMode),
        settings.getBooleanOrNullFlow(KEY_DYNAMIC).map { it ?: true },
    ) { mode, dynamic -> ThemeSettings(mode, dynamic) }

    fun read(): ThemeSettings = ThemeSettings(
        mode = decodeMode(settings.getStringOrNull(KEY_MODE)),
        dynamicColor = settings.getBooleanOrNull(KEY_DYNAMIC) ?: true,
    )

    fun setMode(mode: ThemeMode) {
        settings.putString(KEY_MODE, mode.name)
    }

    fun setDynamicColor(enabled: Boolean) {
        settings.putBoolean(KEY_DYNAMIC, enabled)
    }

    private fun decodeMode(raw: String?): ThemeMode =
        raw?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM

    private companion object {
        const val KEY_MODE = "theme_mode"
        const val KEY_DYNAMIC = "theme_dynamic_color"
    }
}
