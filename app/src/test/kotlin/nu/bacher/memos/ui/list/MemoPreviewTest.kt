package nu.bacher.memos.ui.list

import nu.bacher.memos.widget.widgetPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * First test in `:app`. The widget's preview formatting is pure string logic
 * with no Android dependencies, which makes it the natural thing to pin down
 * here — it renders on the home screen where nobody is watching for
 * regressions.
 */
class MemoPreviewTest {

    @Test
    fun collapses_newlines_into_one_line() {
        assertEquals("first second", widgetPreview("first\nsecond"))
    }

    @Test
    fun strips_leading_markdown_markers() {
        assertEquals("Heading item", widgetPreview("# Heading\n- item"))
    }

    @Test
    fun drops_blank_lines() {
        assertEquals("a b", widgetPreview("a\n\n\n   \nb"))
    }

    @Test
    fun truncates_long_content_with_an_ellipsis() {
        val preview = widgetPreview("x".repeat(500))
        assertTrue(preview.endsWith("…"), "expected an ellipsis, got: $preview")
        assertTrue(preview.length <= 91, "preview should stay near the char cap")
    }

    @Test
    fun leaves_short_content_untouched() {
        assertEquals("short note", widgetPreview("short note"))
    }
}
