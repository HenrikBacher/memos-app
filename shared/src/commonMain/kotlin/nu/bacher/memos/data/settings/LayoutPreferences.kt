package nu.bacher.memos.data.settings

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.getStringOrNullFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class MemoLayout {
    GRID,
    LIST,
}

/**
 * Persists the memo-list layout preference (grid vs. list) in shared
 * preferences. Backed by the same [ObservableSettings] instance that holds
 * [nu.bacher.memos.data.auth.AuthStore]; the auth store no longer clears the
 * whole settings file so this preference survives logout.
 */
@OptIn(ExperimentalSettingsApi::class)
class LayoutPreferences(private val settings: ObservableSettings) {

    val layoutFlow: Flow<MemoLayout> = settings.getStringOrNullFlow(KEY_LAYOUT).map(::decode)

    fun read(): MemoLayout = decode(settings.getStringOrNull(KEY_LAYOUT))

    fun setLayout(layout: MemoLayout) {
        settings.putString(KEY_LAYOUT, layout.name)
    }

    private fun decode(raw: String?): MemoLayout =
        raw?.let { name -> MemoLayout.entries.firstOrNull { it.name == name } } ?: MemoLayout.GRID

    private companion object {
        const val KEY_LAYOUT = "memo_list_layout"
    }
}
