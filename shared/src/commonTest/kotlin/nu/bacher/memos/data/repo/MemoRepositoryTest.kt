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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class MemoRepositoryTest {

    @Test
    fun create_optimistically_inserts_temp_row_then_replaces_with_server_row() = runTest {
        val engine = MockEngine { _ ->
            respond(
                """{"name":"memos/server","content":"hello"}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val dao = FakeMemoDao().also {
            it.replaceAll(listOf(entity("memos/old", order = 0)))
        }
        val repo = repo(engine, dao)

        val saved = repo.create("hello")

        assertEquals("memos/server", saved.name)
        val rows = dao.getAll()
        assertEquals(2, rows.size)
        // Server row sits at the top, old row shifted down.
        assertEquals("memos/server", rows[0].name)
        assertEquals(0, rows[0].orderInList)
        assertEquals("memos/old", rows[1].name)
        // No leftover temp row.
        assertTrue(rows.none { it.name.startsWith("memos/local-") })
    }

    @Test
    fun create_rolls_back_temp_row_when_api_fails() = runTest {
        val engine = MockEngine { _ ->
            respond("nope", HttpStatusCode.InternalServerError, jsonHeaders())
        }
        val dao = FakeMemoDao().also {
            it.replaceAll(listOf(entity("memos/old", order = 0)))
        }
        val repo = repo(engine, dao)

        assertFailsWith<Throwable> { repo.create("boom") }

        // Cache is back to its pre-call state.
        val rows = dao.getAll()
        assertEquals(listOf("memos/old"), rows.map { it.name })
        assertTrue(rows.none { it.name.startsWith("memos/local-") })
    }

    @Test
    fun update_writes_to_cache_first_and_rolls_back_on_failure() = runTest {
        val prior = entity("memos/x", order = 5).copy(content = "old", visibility = "PRIVATE")
        val dao = FakeMemoDao().also { it.replaceAll(listOf(prior)) }

        var capturedDuringCall: MemoEntity? = null
        val engine = MockEngine { _ ->
            // Confirms the DAO already reflects the optimistic write while the
            // API request is in flight.
            capturedDuringCall = dao.getAll().first { it.name == "memos/x" }
            respond("nope", HttpStatusCode.InternalServerError, jsonHeaders())
        }
        val repo = repo(engine, dao)

        assertFailsWith<Throwable> {
            repo.update("memos/x", content = "new", visibility = "PUBLIC")
        }

        // Optimistic write was observable mid-flight.
        val midFlight = capturedDuringCall
        assertNotNull(midFlight)
        assertEquals("new", midFlight.content)
        assertEquals("PUBLIC", midFlight.visibility)

        // After failure, cache restored.
        val after = dao.get("memos/x")!!
        assertEquals("old", after.content)
        assertEquals("PRIVATE", after.visibility)
        assertEquals(5, after.orderInList)
    }

    @Test
    fun delete_removes_from_cache_first_and_restores_on_failure() = runTest {
        val prior = entity("memos/x", order = 3)
        val dao = FakeMemoDao().also { it.replaceAll(listOf(prior)) }

        var observedDuringCall: List<String> = emptyList()
        val engine = MockEngine { _ ->
            observedDuringCall = dao.getAll().map { it.name }
            respond("nope", HttpStatusCode.InternalServerError, jsonHeaders())
        }
        val repo = repo(engine, dao)

        assertFailsWith<Throwable> { repo.delete("memos/x") }

        // Cache was empty while the API call was in flight.
        assertEquals(emptyList(), observedDuringCall)
        // After failure, the row is back.
        assertEquals(listOf("memos/x"), dao.getAll().map { it.name })
    }

    @Test
    fun delete_succeeds_and_leaves_cache_empty_on_2xx() = runTest {
        val dao = FakeMemoDao().also { it.replaceAll(listOf(entity("memos/x"))) }
        val engine = MockEngine { _ -> respond("", HttpStatusCode.OK, jsonHeaders()) }
        val repo = repo(engine, dao)

        repo.delete("memos/x")
        assertEquals(emptyList(), dao.getAll())
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
        )
    }

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
