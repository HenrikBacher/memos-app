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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import nu.bacher.memos.data.api.MemoState
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.MemosJson
import nu.bacher.memos.data.db.LocalMemoName
import nu.bacher.memos.data.db.MemoEntity
import nu.bacher.memos.data.db.PendingActionEntity

/**
 * Covers the cache/search behaviour the offline-first paths depend on and
 * that no other suite exercises:
 *  - `replaceAll` keeps unsynced temp rows across a server refresh, and
 *    scopes its eviction to one lifecycle state.
 *  - Abandoning a queued action also drops its temp row, so the orphan sweep
 *    can't resurrect it forever.
 *  - `searchCached` is the offline fallback's actual matcher.
 *  - `setPinned` writes through optimistically.
 */
class CacheAndSearchTest {

    @Test
    fun replaceAll_keeps_unsynced_temp_rows() = runTest {
        val dao = FakeMemoDao()
        val temp = memoEntity("${LocalMemoName.PREFIX}1", order = 0).copy(content = "unsynced")
        dao.upsertAll(listOf(temp, memoEntity("memos/old", order = 1)))

        dao.replaceAll(
            memos = listOf(memoEntity("memos/fresh")),
            tempPrefix = LocalMemoName.PREFIX,
            archived = false,
        )

        val names = dao.getAll().map { it.name }
        // The temp row survives; the stale server row is replaced.
        assertEquals(listOf("${LocalMemoName.PREFIX}1", "memos/fresh"), names)
        // And it stays at the top, where insertAtTop originally put it.
        assertEquals(0, dao.get("${LocalMemoName.PREFIX}1")!!.orderInList)
        assertEquals(1, dao.get("memos/fresh")!!.orderInList)
    }

    @Test
    fun replaceAll_does_not_evict_the_other_lifecycle_state() = runTest {
        val dao = FakeMemoDao()
        dao.upsertAll(
            listOf(
                memoEntity("memos/active", order = 0),
                memoEntity("memos/archived", order = 1).copy(state = MemoState.ARCHIVED),
            ),
        )

        // Refreshing the active view must leave archived rows cached.
        dao.replaceAll(
            memos = listOf(memoEntity("memos/active2")),
            tempPrefix = LocalMemoName.PREFIX,
            archived = false,
        )

        val names = dao.getAll().map { it.name }.toSet()
        assertEquals(setOf("memos/archived", "memos/active2"), names)
    }

    @Test
    fun refreshing_one_state_does_not_renumber_the_other() = runTest {
        val dao = FakeMemoDao()
        dao.upsertAll(
            listOf(
                memoEntity("memos/archived-a", order = 0).copy(state = MemoState.ARCHIVED),
                memoEntity("memos/archived-b", order = 1).copy(state = MemoState.ARCHIVED),
                memoEntity("memos/active", order = 2),
            ),
        )

        dao.replaceAll(
            memos = listOf(memoEntity("memos/active2")),
            tempPrefix = LocalMemoName.PREFIX,
            archived = false,
        )

        // The archived partition is untouched — same rows, same order. A
        // refresh that rewrote them would churn the other view's PagingSource
        // for no reason.
        assertEquals(0, dao.get("memos/archived-a")!!.orderInList)
        assertEquals(1, dao.get("memos/archived-b")!!.orderInList)
    }

    @Test
    fun permanently_rejected_create_flags_its_row_so_the_sweep_cannot_requeue_it() = runTest {
        // 400 on replay: the server will never accept this memo.
        val engine = MockEngine { _ -> respondError(HttpStatusCode.BadRequest) }
        val dao = FakeMemoDao()
        val pending = FakePendingActionDao()
        val repo = testMemoRepository(engine, dao, pending)

        val tempName = "${LocalMemoName.PREFIX}1"
        // cachedAtEpochMs = 0 puts it far past the orphan age threshold.
        dao.upsertAll(listOf(memoEntity(tempName).copy(content = "doomed", cachedAtEpochMs = 0)))
        pending.insert(
            PendingActionEntity(
                type = PendingActionType.CREATE.storedValue,
                memoName = tempName,
                payloadJson = MemosJson.encodeToString(
                    PendingPayload.Create.serializer(),
                    PendingPayload.Create("doomed", "PRIVATE", emptyList()),
                ),
                createdAtEpochMs = 0,
            ),
        )

        repo.syncPending()

        // Action dropped; the row is kept (it is the user's only copy) but
        // flagged so it can't be re-adopted.
        assertTrue(pending.rows.isEmpty(), "action should be dropped")
        assertTrue(dao.get(tempName)!!.syncFailed, "abandoned row should be flagged")

        // The whole point: a second sync must not resurrect it.
        repo.syncPending()
        assertTrue(pending.rows.isEmpty(), "orphan sweep must not re-enqueue a dropped create")
    }

    @Test
    fun searchCached_matches_content_and_escapes_like_wildcards() = runTest {
        val dao = FakeMemoDao()
        dao.upsertAll(
            listOf(
                memoEntity("memos/a", order = 0).copy(content = "buy milk"),
                memoEntity("memos/b", order = 1).copy(content = "sell bread"),
                memoEntity("memos/c", order = 2).copy(content = "100% done"),
            ),
        )

        assertEquals(
            listOf("memos/a"),
            dao.searchCached(escapeLike("milk"), tag = null, limit = 50).map { it.name },
        )
        // '%' is a LIKE wildcard; escaped it must match literally, not everything.
        assertEquals(
            listOf("memos/c"),
            dao.searchCached(escapeLike("100%"), tag = null, limit = 50).map { it.name },
        )
    }

    @Test
    fun searchCached_filters_by_tag() = runTest {
        val dao = FakeMemoDao()
        dao.upsertAll(
            listOf(
                memoEntity("memos/a", order = 0).copy(content = "note one", tagsCsv = "work"),
                memoEntity("memos/b", order = 1).copy(content = "note two", tagsCsv = "home"),
                memoEntity("memos/c", order = 2).copy(content = "note three #work"),
            ),
        )

        val hits = dao.searchCached(escapeLike("note"), tag = "work", limit = 50).map { it.name }
        // Matches the tag column and an inline #tag, mirroring tagMatches.
        assertEquals(listOf("memos/a", "memos/c"), hits)
    }

    @Test
    fun setPinned_writes_through_and_updates_the_cache() = runTest {
        val engine = MockEngine { _ ->
            respond(
                """{"name":"memos/x","content":"hi","pinned":true}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val dao = FakeMemoDao()
        dao.upsertAll(listOf(memoEntity("memos/x").copy(pinned = false)))
        val repo = testMemoRepository(engine, dao)

        val saved = repo.setPinned("memos/x", true)

        assertTrue(saved.pinned)
        assertTrue(dao.get("memos/x")!!.pinned)
    }

    @Test
    fun setPinned_queues_and_keeps_optimistic_pin_when_offline() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.ServiceUnavailable) }
        val dao = FakeMemoDao()
        dao.upsertAll(listOf(memoEntity("memos/x").copy(pinned = false)))
        val pending = FakePendingActionDao()
        val repo = testMemoRepository(engine, dao, pending)

        repo.setPinned("memos/x", true)

        assertTrue(dao.get("memos/x")!!.pinned, "optimistic pin should stick")
        val queued = pending.rows.single()
        assertEquals(PendingActionType.UPDATE.storedValue, queued.type)
        val payload = MemosJson.decodeFromString(
            PendingPayload.Update.serializer(),
            queued.payloadJson,
        )
        assertEquals(true, payload.pinned)
        assertFalse(queued.memoName.isEmpty())
    }



}
