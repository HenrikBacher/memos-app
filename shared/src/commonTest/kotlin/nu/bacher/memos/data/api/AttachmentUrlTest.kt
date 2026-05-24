package nu.bacher.memos.data.api

import com.russhwolf.settings.MapSettings
import nu.bacher.memos.data.auth.AuthStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentUrlTest {

    private fun authStoreWith(server: String = "https://memos.example.com"): AuthStore =
        AuthStore(MapSettings()).also { it.save(server, "tok") }

    @Test
    fun urlOrNull_builds_server_file_url_from_name_and_filename() {
        val att = AttachmentDto(
            name = "attachments/abc123",
            filename = "photo.png",
            type = "image/png",
        )
        assertEquals(
            "https://memos.example.com/file/attachments/abc123/photo.png",
            att.urlOrNull(authStoreWith()),
        )
    }

    @Test
    fun urlOrNull_prefers_external_link_when_present() {
        val att = AttachmentDto(
            name = "attachments/abc",
            filename = "photo.png",
            externalLink = "https://cdn.example.com/x.png",
        )
        assertEquals("https://cdn.example.com/x.png", att.urlOrNull(authStoreWith()))
    }

    @Test
    fun urlOrNull_trims_trailing_slash_on_server_url() {
        val att = AttachmentDto(name = "attachments/x", filename = "a.png")
        assertEquals(
            "https://memos.example.com/file/attachments/x/a.png",
            att.urlOrNull(authStoreWith("https://memos.example.com/")),
        )
    }

    @Test
    fun urlOrNull_returns_null_when_not_logged_in() {
        val att = AttachmentDto(name = "attachments/x", filename = "a.png")
        // AuthStore with no saved credentials → read() returns null.
        assertNull(att.urlOrNull(AuthStore(MapSettings())))
    }

    @Test
    fun urlOrNull_returns_null_when_name_blank_and_no_external_link() {
        val att = AttachmentDto(name = "", filename = "a.png")
        assertNull(att.urlOrNull(authStoreWith()))
    }

    @Test
    fun urlOrNull_url_encodes_filename_with_spaces() {
        val att = AttachmentDto(name = "attachments/abc", filename = "my photo.png")
        val url = att.urlOrNull(authStoreWith())
        // Ktor appendPathSegments encodes spaces as %20 per RFC 3986.
        assertEquals(
            "https://memos.example.com/file/attachments/abc/my%20photo.png",
            url,
        )
    }

    @Test
    fun isImage_recognizes_image_mime_types_case_insensitively() {
        assertTrue(AttachmentDto(type = "image/png").isImage())
        assertTrue(AttachmentDto(type = "IMAGE/JPEG").isImage())
        assertFalse(AttachmentDto(type = "application/pdf").isImage())
        assertFalse(AttachmentDto(type = "").isImage())
    }

    @Test
    fun isVideo_and_isAudio() {
        assertTrue(AttachmentDto(type = "video/mp4").isVideo())
        assertFalse(AttachmentDto(type = "image/png").isVideo())
        assertTrue(AttachmentDto(type = "audio/mpeg").isAudio())
        assertFalse(AttachmentDto(type = "video/mp4").isAudio())
    }
}
