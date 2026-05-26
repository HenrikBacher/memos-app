package nu.bacher.memos.data.repo

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.MemosJson
import nu.bacher.memos.data.db.MemoEntity
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun create_cleans_up_temp_row_when_caller_is_cancelled_mid_call() = runTest {
        val engineEntered = CompletableDeferred<Unit>()
        val engineNeverResponds = CompletableDeferred<Unit>()
        val engine = MockEngine { _ ->
            engineEntered.complete(Unit)
            // Suspend forever — the test will cancel the caller before this
            // ever completes, exercising the NonCancellable cleanup path.
            engineNeverResponds.await()
            respond("unreachable", HttpStatusCode.OK, jsonHeaders())
        }
        val dao = FakeMemoDao()
        val repo = repo(engine, dao)

        val job = launch { runCatching { repo.create("hi") } }
        engineEntered.await()
        // Temp row is visible while the API call is in flight.
        assertTrue(
            dao.getAll().any { it.name.startsWith("memos/local-") },
            "temp row should be inserted before API call returns",
        )
        job.cancel()
        job.join()
        // After cancellation, NonCancellable cleanup must have removed the
        // temp row so the cache doesn't leak phantom memos.
        assertEquals(emptyList(), dao.getAll())
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

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun uploadAttachment_streams_base64_json_envelope() = runTest {
        // 100 KB — large enough to span several chunks of the streaming encoder
        // so we exercise the boundary handling, not just a single small write.
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        var capturedBody: ByteArray? = null
        var capturedContentLength: Long? = null
        val engine = MockEngine { request ->
            capturedContentLength = request.body.contentLength
            capturedBody = drainBody(request.body)
            respond(
                """{"name":"attachments/x","filename":"a.bin","type":"application/octet-stream"}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val repo = repo(engine, FakeMemoDao())

        val result = repo.uploadAttachment(
            bytes = bytes,
            filename = "a.bin",
            type = "application/octet-stream",
            memoName = "memos/abc",
        )

        assertEquals("attachments/x", result.name)
        val body = assertNotNull(capturedBody)

        // Content-Length must be set (not chunked): some self-hosted memos
        // deployments sit behind proxies that reject chunked uploads.
        assertEquals(body.size.toLong(), capturedContentLength)

        // Parse the envelope and decode the content field — the streaming path
        // must produce a payload byte-identical to what kotlinx-serialization
        // would have produced from a {filename, type, memo, content} object.
        val root = Json.parseToJsonElement(body.decodeToString()).jsonObject
        assertEquals("a.bin", root.getValue("filename").jsonPrimitive.content)
        assertEquals("application/octet-stream", root.getValue("type").jsonPrimitive.content)
        assertEquals("memos/abc", root.getValue("memo").jsonPrimitive.content)
        val decoded = Base64.decode(root.getValue("content").jsonPrimitive.content)
        assertContentEquals(bytes, decoded)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun uploadAttachment_omits_memo_field_when_null() = runTest {
        var capturedBody: ByteArray? = null
        val engine = MockEngine { request ->
            capturedBody = drainBody(request.body)
            respond(
                """{"name":"attachments/x","filename":"a.png","type":"image/png"}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val repo = repo(engine, FakeMemoDao())

        repo.uploadAttachment(
            bytes = byteArrayOf(1, 2, 3),
            filename = "a.png",
            type = "image/png",
            memoName = null,
        )

        val root = Json.parseToJsonElement(capturedBody!!.decodeToString()).jsonObject
        // memos's server treats an unset memo differently from one set to ""
        // — the streaming envelope must omit the field entirely, matching
        // explicitNulls = false on MemosJson.
        assertNull(root["memo"])
        assertEquals("AQID", root.getValue("content").jsonPrimitive.content)
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

    /**
     * Drives a [OutgoingContent.WriteChannelContent] body and collects its
     * bytes. MockEngine hands us the request body untouched, so for streaming
     * uploads we have to actually run the writer to see what would have hit
     * the wire.
     */
    private suspend fun drainBody(content: OutgoingContent): ByteArray {
        require(content is OutgoingContent.WriteChannelContent) {
            "expected streaming body but got ${content::class.simpleName}"
        }
        return coroutineScope {
            val ch = ByteChannel(autoFlush = true)
            CoroutineScope(coroutineContext).launch {
                try {
                    content.writeTo(ch)
                } finally {
                    ch.flushAndClose()
                }
            }
            ch.readRemaining().readByteArray()
        }
    }

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
