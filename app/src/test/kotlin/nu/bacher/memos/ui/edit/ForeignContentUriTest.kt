package nu.bacher.memos.ui.edit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Shared streams are untrusted input: another app must not be able to point
 * this app at its own private files and have them uploaded.
 */
class ForeignContentUriTest {

    private val own = "nu.bacher.memos"

    @Test
    fun accepts_content_uris_from_other_apps() {
        assertTrue(isForeignContentUri("content", "com.android.providers.media.module", own))
        assertTrue(isForeignContentUri("CONTENT", "com.example.gallery", own))
    }

    @Test
    fun rejects_file_uris() {
        assertFalse(isForeignContentUri("file", null, own))
    }

    @Test
    fun rejects_this_apps_own_providers() {
        assertFalse(isForeignContentUri("content", own, own))
    }

    @Test
    fun rejects_uris_without_a_scheme() {
        assertFalse(isForeignContentUri(null, "com.example.gallery", own))
    }
}
