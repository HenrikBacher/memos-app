package nu.bacher.memos.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuildSearchFilterTest {

    @Test
    fun empty_query_and_null_tag_returns_null() {
        assertNull(buildSearchFilter("", null))
        assertNull(buildSearchFilter("   ", null))
    }

    @Test
    fun query_only_emits_content_search_clause() {
        assertEquals(
            """content_search == ["hello"]""",
            buildSearchFilter("hello", null),
        )
    }

    @Test
    fun query_is_trimmed_so_leading_trailing_whitespace_doesnt_leak() {
        assertEquals(
            """content_search == ["hello"]""",
            buildSearchFilter("  hello  ", null),
        )
    }

    @Test
    fun tag_only_emits_tag_clause() {
        assertEquals(
            """tag in ["work"]""",
            buildSearchFilter("", "work"),
        )
    }

    @Test
    fun query_and_tag_are_joined_with_AND() {
        assertEquals(
            """content_search == ["foo"] && tag in ["work"]""",
            buildSearchFilter("foo", "work"),
        )
    }

    @Test
    fun double_quotes_inside_query_are_escaped() {
        // Memos's filter parser is CEL-ish: backslash and double-quote both
        // need escaping so a query like `say "hi"` doesn't terminate the
        // string literal early.
        assertEquals(
            """content_search == ["say \"hi\""]""",
            buildSearchFilter("""say "hi"""", null),
        )
    }

    @Test
    fun backslashes_inside_query_are_escaped_first() {
        assertEquals(
            """content_search == ["a\\b"]""",
            buildSearchFilter("""a\b""", null),
        )
    }
}
