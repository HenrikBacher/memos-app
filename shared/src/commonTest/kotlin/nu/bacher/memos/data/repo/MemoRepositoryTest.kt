package nu.bacher.memos.data.repo

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.MemosJson
import nu.bacher.memos.data.db.MemoEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class MemoRepositoryTest {

    @Test
    fun refresh_paginates_until_nextPageToken_is_blank() = runTest {
        val pageTokensSeen = mutableListOf<String?>()
        val engine = MockEngine { request ->
            val token = request.url.parameters["pageToken"]
            pageTokensSeen += token
            val body = when (token) {
                null -> """{"memos":[{"name":"memos/a"},{"name":"memos/b"}],"nextPageToken":"t1"}"""
                "t1" -> """{"memos":[{"name":"memos/c"}],"nextPageToken":"t2"}"""
                "t2" -> """{"memos":[{"name":"memos/d"}],"nextPageToken":""}"""
                else -> fail("unexpected pageToken=$token")
            }
            respond(body, HttpStatusCode.OK, jsonHeaders())
        }
        val repo = repo(engine, FakeMemoDao())

        val result = repo.refresh()
        assertTrue(result.isSuccess)

        // Three sequential calls: first with no token, then t1, then t2.
        assertEquals(listOf(null, "t1", "t2"), pageTokensSeen)
        assertEquals(
            listOf("memos/a", "memos/b", "memos/c", "memos/d"),
            repo.daoOrNull()?.getAll()?.map { it.name },
        )
    }

    @Test
    fun refresh_replaces_cache_rather_than_appending() = runTest {
        val responses = mutableListOf(
            """{"memos":[{"name":"memos/a"},{"name":"memos/b"}]}""",
            """{"memos":[{"name":"memos/a"}]}""",
        )
        val engine = MockEngine { _ ->
            respond(responses.removeAt(0), HttpStatusCode.OK, jsonHeaders())
        }
        val dao = FakeMemoDao()
        val repo = repo(engine, dao)

        repo.refresh()
        assertEquals(2, dao.getAll().size)
        repo.refresh()
        // Second response omitted "memos/b" — the local cache must drop it,
        // not accumulate stale rows.
        assertEquals(listOf("memos/a"), dao.getAll().map { it.name })
    }

    @Test
    fun create_prepends_new_memo_and_shifts_existing_orders() = runTest {
        val engine = MockEngine { _ ->
            respond(
                """{"name":"memos/new","content":"hello"}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val dao = FakeMemoDao().also {
            it.replaceAll(
                listOf(
                    entity("memos/old1", order = 0),
                    entity("memos/old2", order = 1),
                ),
            )
        }
        val repo = repo(engine, dao)

        repo.create("hello")

        val rows = dao.getAll()
        assertEquals(3, rows.size)
        // New memo lands at order 0; existing rows shift down by one.
        assertEquals("memos/new", rows[0].name)
        assertEquals(0, rows[0].orderInList)
        assertEquals("memos/old1", rows[1].name)
        assertEquals(1, rows[1].orderInList)
        assertEquals("memos/old2", rows[2].name)
        assertEquals(2, rows[2].orderInList)
    }

    @Test
    fun uploadAttachment_base64_encodes_bytes_in_request_body() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedBody = (request.body as? TextContent)?.text
            respond(
                """{"name":"attachments/x","filename":"a.png","type":"image/png"}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val repo = repo(engine, FakeMemoDao())

        val result = repo.uploadAttachment(
            bytes = byteArrayOf(1, 2, 3),
            filename = "a.png",
            type = "image/png",
        )

        assertEquals("attachments/x", result.name)
        assertNotNull(capturedBody)
        // Base64 of bytes 0x01 0x02 0x03 → "AQID". Standard Base64 alphabet.
        assertTrue("AQID" in capturedBody!!, "body did not contain expected base64: $capturedBody")
        assertTrue("\"filename\":\"a.png\"" in capturedBody!!)
    }

    @Test
    fun clearCache_empties_the_dao() = runTest {
        val dao = FakeMemoDao().also {
            it.replaceAll(listOf(entity("memos/a"), entity("memos/b")))
        }
        val repo = repo(MockEngine { fail("network must not be called") }, dao)

        repo.clearCache()
        assertEquals(emptyList(), dao.getAll())
    }

    // --- helpers ---

    private fun repo(engine: MockEngine, dao: FakeMemoDao): MemoRepository {
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(MemosJson) }
        }
        return MemoRepository(
            api = MemosApi(client),
            dao = dao,
            verifyClientFactory = { _, _ -> fail("verifyCreds is not exercised here") },
        ).also { _daoRefs[it] = dao }
    }

    /**
     * Test-only handle to read back the DAO a repo was constructed with — the
     * repo doesn't expose it. We thread it through a weak-ish side table keyed
     * by the repo instance.
     */
    private val _daoRefs = mutableMapOf<MemoRepository, FakeMemoDao>()
    private fun MemoRepository.daoOrNull(): FakeMemoDao? = _daoRefs[this]

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun entity(name: String, order: Int = 0) = MemoEntity(
        name = name,
        uid = null,
        content = "",
        visibility = "PRIVATE",
        state = null,
        pinned = false,
        createTime = null,
        updateTime = null,
        displayTime = null,
        creator = null,
        tagsCsv = "",
        attachmentsJson = "",
        orderInList = order,
        cachedAtEpochMs = 0,
    )
}
