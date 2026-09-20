package nu.bacher.memos.data.repo

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.fail
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.MemosJson
import nu.bacher.memos.data.db.MemoEntity

/**
 * Shared harness for the repository and ViewModel suites. Each test file used
 * to carry its own copy of these four helpers; a new column on [MemoEntity] or
 * a new [MemoRepository] constructor parameter then meant editing five files.
 */

/** Ktor client configured exactly as production configures it. */
internal fun testHttpClient(engine: MockEngine): HttpClient = HttpClient(engine) {
    expectSuccess = true
    install(ContentNegotiation) { json(MemosJson) }
}

/**
 * Repository over [engine]. [verifyClient] is the client handed to
 * `verifyCreds`; the default fails the test, since most suites never exercise
 * the login path and a silent call would be a surprise.
 */
internal fun testMemoRepository(
    engine: MockEngine,
    dao: FakeMemoDao = FakeMemoDao(),
    pendingDao: FakePendingActionDao = FakePendingActionDao(),
    verifyClient: HttpClient? = null,
): MemoRepository {
    val client = testHttpClient(engine)
    return MemoRepository(
        api = MemosApi(client),
        dao = dao,
        pendingActionDao = pendingDao,
        verifyClientFactory = { _, _ ->
            verifyClient ?: fail("verifyCreds is not exercised by this test")
        },
    )
}

internal fun jsonHeaders(): Headers = headersOf(HttpHeaders.ContentType, "application/json")

/** Minimal cached memo. Per-test variation goes through `.copy(...)`. */
internal fun memoEntity(name: String, order: Int = 0) = MemoEntity(
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
