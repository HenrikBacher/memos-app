package nu.bacher.memos.data.repo

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.MemosJson
import nu.bacher.memos.data.db.MemoEntity

/**
 * Tests for the offline write queue. Covers:
 *  - Retriable failures (5xx) keep the optimistic cache write and enqueue.
 *  - syncPending() flushes successfully on next online attempt.
 *  - syncPending() drops actions the server permanently rejects.
 *  - Edit-on-pending-create merges into the queued payload instead of
 *    issuing a doomed PATCH against a non-existent server resource.
 *  - Delete-on-pending-create wipes both the cache row and the queued
 *    action, no API call required.
 */
class OfflineQueueTest {

    @Test
    fun create_queues_action_on_retriable_failure_and_keeps_temp_row() = runTest {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.InternalServerError)
        }
        val dao = FakeMemoDao()
        val pending = FakePendingActionDao()
        val repo = repo(engine, dao, pending)

        // Should NOT throw — retriable failures preserve the user's edit.
        val result = repo.create("hello world")

        assertTrue(result.name.startsWith(MemoRepository.TEMP_NAME_PREFIX))
        // Temp row still present.
        assertEquals(listOf(result.name), dao.getAll().map { it.name })
        // Queued action carries the create payload.
        val queued = pending.rows.single()
        assertEquals(PendingActionType.CREATE.storedValue, queued.type)
        assertEquals(result.name, queued.memoName)
    }

    @Test
    fun syncPending_flushes_queued_create_and_replaces_temp_with_server_row() = runTest {
        // First call fails (queues), second succeeds (flushes).
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            if (calls == 1) respondError(HttpStatusCode.InternalServerError)
            else respond(
                """{"name":"memos/server-1","content":"hello"}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val dao = FakeMemoDao()
        val pending = FakePendingActionDao()
        val repo = repo(engine, dao, pending)

        repo.create("hello")
        assertEquals(1, pending.rows.size, "create should have queued one action")

        repo.syncPending()

        // Server row replaced temp row; queue is empty.
        val rows = dao.getAll()
        assertEquals(listOf("memos/server-1"), rows.map { it.name })
        assertEquals(0, pending.rows.size)
    }

    @Test
    fun syncPending_drops_action_on_non_retriable_failure_during_replay() = runTest {
        // First call queues (5xx), second permanently rejects (400) — the
        // action should be dropped so we don't loop forever.
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            if (calls == 1) respondError(HttpStatusCode.InternalServerError)
            else respondError(HttpStatusCode.BadRequest)
        }
        val dao = FakeMemoDao()
        val pending = FakePendingActionDao()
        val repo = repo(engine, dao, pending)

        repo.create("hello")
        repo.syncPending()

        // Queue is empty (action dropped). Cache row stays as the user's
        // record of what they intended — refresh will reconcile.
        assertEquals(0, pending.rows.size)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun syncPending_keeps_action_when_replay_is_still_failing_retriably() = runTest {
        // Network still down on retry — action should remain queued and
        // attempts counter should advance.
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.InternalServerError)
        }
        val dao = FakeMemoDao()
        val pending = FakePendingActionDao()
        val repo = repo(engine, dao, pending)

        repo.create("hello")
        assertEquals(1, pending.rows.size)
        val initialAttempts = pending.rows.single().attempts

        repo.syncPending()

        assertEquals(1, pending.rows.size, "action should still be queued")
        val after = pending.rows.single()
        assertEquals(initialAttempts + 1, after.attempts)
        assertNotNull(after.lastAttemptEpochMs)
    }

    @Test
    fun update_on_pending_temp_memo_merges_into_pending_create() = runTest {
        // First create queues; subsequent update should NOT hit the network.
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            respondError(HttpStatusCode.InternalServerError)
        }
        val dao = FakeMemoDao()
        val pending = FakePendingActionDao()
        val repo = repo(engine, dao, pending)

        val created = repo.create("v1")
        assertEquals(1, calls, "create should have made one API attempt")
        val tempName = created.name

        val updated = repo.update(tempName, content = "v2", visibility = "PUBLIC")

        assertEquals(1, calls, "update of pending-temp memo must not call the API")
        assertEquals("v2", updated.content)
        assertEquals("PUBLIC", updated.visibility)
        // Cache reflects the updated content.
        assertEquals("v2", dao.get(tempName)!!.content)
        // Queue still has exactly one CREATE — the merged payload carries v2.
        val queued = pending.rows.single()
        assertEquals(PendingActionType.CREATE.storedValue, queued.type)
        // Payload must encode the latest content; we don't parse it here, a
        // plain substring check is enough to catch a regression where the
        // payload kept the stale v1 string.
        assertTrue(
            queued.payloadJson.contains("v2"),
            "merged payload should carry latest content, was: ${queued.payloadJson}",
        )
        assertTrue(!queued.payloadJson.contains("\"v1\""))
    }

    @Test
    fun delete_on_pending_temp_memo_drops_action_and_row_without_api_call() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            respondError(HttpStatusCode.InternalServerError)
        }
        val dao = FakeMemoDao()
        val pending = FakePendingActionDao()
        val repo = repo(engine, dao, pending)

        val created = repo.create("hello")
        assertEquals(1, calls)

        repo.delete(created.name)

        assertEquals(1, calls, "delete of pending-temp memo must not call the API")
        assertEquals(0, pending.rows.size, "pending CREATE should be dropped")
        assertEquals(0, dao.getAll().size, "cache row should be removed")
    }

    @Test
    fun update_queues_when_api_fails_retriably_and_keeps_optimistic_write() = runTest {
        val prior = entity("memos/x").copy(content = "old", visibility = "PRIVATE")
        val dao = FakeMemoDao().also { it.replaceAll(listOf(prior)) }
        val pending = FakePendingActionDao()
        val engine = MockEngine { _ -> respondError(HttpStatusCode.InternalServerError) }
        val repo = repo(engine, dao, pending)

        val saved = repo.update("memos/x", content = "new", visibility = "PUBLIC")

        // Returned DTO matches the optimistic write.
        assertEquals("new", saved.content)
        assertEquals("PUBLIC", saved.visibility)
        // Cache holds the optimistic state (not the prior).
        assertEquals("new", dao.get("memos/x")!!.content)
        // Queued UPDATE for replay.
        val queued = pending.rows.single()
        assertEquals(PendingActionType.UPDATE.storedValue, queued.type)
        assertEquals("memos/x", queued.memoName)
    }

    @Test
    fun update_collapses_repeated_retries_to_a_single_queued_action() = runTest {
        val prior = entity("memos/x").copy(content = "v0")
        val dao = FakeMemoDao().also { it.replaceAll(listOf(prior)) }
        val pending = FakePendingActionDao()
        val engine = MockEngine { _ -> respondError(HttpStatusCode.InternalServerError) }
        val repo = repo(engine, dao, pending)

        repo.update("memos/x", content = "v1")
        repo.update("memos/x", content = "v2")
        repo.update("memos/x", content = "v3")

        // Only the latest UPDATE is worth replaying — earlier ones would be
        // overwritten anyway.
        val queued = pending.rows.single()
        assertEquals(PendingActionType.UPDATE.storedValue, queued.type)
        assertTrue(queued.payloadJson.contains("v3"))
    }

    @Test
    fun delete_queues_when_api_fails_retriably_and_keeps_cache_empty() = runTest {
        val prior = entity("memos/x")
        val dao = FakeMemoDao().also { it.replaceAll(listOf(prior)) }
        val pending = FakePendingActionDao()
        val engine = MockEngine { _ -> respondError(HttpStatusCode.InternalServerError) }
        val repo = repo(engine, dao, pending)

        repo.delete("memos/x")

        assertEquals(0, dao.getAll().size, "cache row should remain deleted")
        val queued = pending.rows.single()
        assertEquals(PendingActionType.DELETE.storedValue, queued.type)
        assertEquals("memos/x", queued.memoName)
    }

    // --- helpers (duplicated minimally from MemoRepositoryTest so this file
    // is self-contained and we don't end up tangling test fixtures) ---

    private fun repo(
        engine: MockEngine,
        dao: FakeMemoDao,
        pendingDao: FakePendingActionDao = FakePendingActionDao(),
    ): MemoRepository {
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(MemosJson) }
        }
        return MemoRepository(
            api = MemosApi(client),
            dao = dao,
            pendingActionDao = pendingDao,
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
