package nu.bacher.memos.data.settings

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LayoutPreferencesTest {

    @Test
    fun defaults_to_GRID_when_unset() {
        val prefs = LayoutPreferences(MapSettings())
        assertEquals(MemoLayout.GRID, prefs.read())
    }

    @Test
    fun roundtrips_LIST_via_setter() {
        val prefs = LayoutPreferences(MapSettings())
        prefs.setLayout(MemoLayout.LIST)
        assertEquals(MemoLayout.LIST, prefs.read())
    }

    @Test
    fun falls_back_to_GRID_when_stored_value_is_unknown_enum_name() {
        // Defensive — protects against a future enum rename that left old prefs
        // on disk pointing at a now-missing variant.
        val settings = MapSettings().apply { putString("memo_list_layout", "MASONRY") }
        val prefs = LayoutPreferences(settings)
        assertEquals(MemoLayout.GRID, prefs.read())
    }

    @Test
    fun flow_emits_current_value() = runTest {
        val prefs = LayoutPreferences(MapSettings())
        assertEquals(MemoLayout.GRID, prefs.layoutFlow.first())
        prefs.setLayout(MemoLayout.LIST)
        assertEquals(MemoLayout.LIST, prefs.layoutFlow.first())
    }
}
