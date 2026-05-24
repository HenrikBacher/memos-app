package nu.bacher.memos.data.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DtosTest {

    @Test
    fun memoUid_extracts_segment_after_first_slash() {
        assertEquals("abc123", "memos/abc123".memoUid())
        assertEquals("xyz", "memos/xyz".memoUid())
    }

    @Test
    fun memoUid_returns_full_string_when_no_slash() {
        // substringAfter with a default returns the receiver when delimiter missing —
        // worth pinning so a future "throw on missing" change doesn't slip past.
        assertEquals("bareid", "bareid".memoUid())
    }

    @Test
    fun memoDto_parses_attachments_field() {
        val json = """
            {
              "name": "memos/abc",
              "content": "hello",
              "attachments": [
                {"name": "attachments/x", "filename": "a.png", "type": "image/png", "size": 100}
              ]
            }
        """.trimIndent()
        val memo = MemosJson.decodeFromString<MemoDto>(json)
        assertEquals("memos/abc", memo.name)
        assertEquals(1, memo.attachments.size)
        assertEquals("attachments/x", memo.attachments[0].name)
        assertEquals("image/png", memo.attachments[0].type)
        assertEquals(100L, memo.attachments[0].size)
    }

    @Test
    fun memoDto_parses_resources_alias_for_older_servers() {
        // memos < 0.22 used "resources" for the same field; the @JsonNames alias
        // on MemoDto.attachments should accept either.
        val json = """
            {
              "name": "memos/abc",
              "resources": [
                {"name": "resources/x", "filename": "a.png", "type": "image/png"}
              ]
            }
        """.trimIndent()
        val memo = MemosJson.decodeFromString<MemoDto>(json)
        assertEquals(1, memo.attachments.size)
        assertEquals("resources/x", memo.attachments[0].name)
    }

    @Test
    fun memoDto_ignores_unknown_fields() {
        val json = """{"name":"memos/x","futureField":"unknown","content":"hi"}"""
        val memo = MemosJson.decodeFromString<MemoDto>(json)
        assertEquals("memos/x", memo.name)
        assertEquals("hi", memo.content)
    }

    @Test
    fun memoDto_defaults_for_missing_fields() {
        val memo = MemosJson.decodeFromString<MemoDto>("{}")
        assertEquals("", memo.name)
        assertEquals("PRIVATE", memo.visibility)
        assertFalse(memo.pinned)
        assertTrue(memo.tags.isEmpty())
        assertTrue(memo.attachments.isEmpty())
    }

    @Test
    fun createMemoRequest_omits_null_attachments() {
        val req = CreateMemoRequest(content = "hi")
        val json = MemosJson.encodeToString(CreateMemoRequest.serializer(), req)
        // explicitNulls=false + encodeDefaults=false on MemosJson — null/default fields
        // should not be serialized.
        assertFalse("attachments" in json)
    }

    @Test
    fun createMemoRequest_includes_attachment_refs_when_present() {
        val req = CreateMemoRequest(
            content = "hi",
            attachments = listOf(AttachmentRef("attachments/x")),
        )
        val json = MemosJson.encodeToString(CreateMemoRequest.serializer(), req)
        assertTrue("attachments/x" in json)
    }

    @Test
    fun updateMemoRequest_omits_unset_fields() {
        // Only `content` should be in the payload — the others are nullable defaults
        // and we rely on the server treating absent fields as "don't touch".
        val req = UpdateMemoRequest(content = "updated")
        val json = MemosJson.encodeToString(UpdateMemoRequest.serializer(), req)
        assertTrue("content" in json)
        assertFalse("visibility" in json)
        assertFalse("pinned" in json)
        assertFalse("attachments" in json)
    }
}
