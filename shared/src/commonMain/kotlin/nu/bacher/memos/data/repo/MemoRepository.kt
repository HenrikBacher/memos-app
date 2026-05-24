package nu.bacher.memos.data.repo

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import nu.bacher.memos.data.api.CreateMemoRequest
import nu.bacher.memos.data.api.MemoDto
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.UpdateMemoRequest
import nu.bacher.memos.data.api.memoUid

/**
 * Thin wrapper around the memos API. The list is cached in-memory for the
 * current process — refresh is explicit. We deliberately don't add a local
 * memo cache to disk (memos itself is the source of truth and supports
 * multi-device editing).
 */
class MemoRepository(
    private val api: MemosApi,
    private val verifyClientFactory: (serverUrl: String, token: String) -> HttpClient,
) {
    private val _memos = MutableStateFlow<List<MemoDto>>(emptyList())
    val memos: Flow<List<MemoDto>> = _memos.asStateFlow()

    suspend fun refresh(): Result<Unit> = runCatching {
        _memos.value = api.listMemos().memos
    }

    suspend fun get(name: String): MemoDto = api.getMemo(name.memoUid())

    suspend fun create(content: String, visibility: String = "PRIVATE"): MemoDto {
        val memo = api.createMemo(CreateMemoRequest(content = content, visibility = visibility))
        _memos.value = listOf(memo) + _memos.value
        return memo
    }

    suspend fun update(name: String, content: String): MemoDto {
        val updated = api.updateMemo(name.memoUid(), UpdateMemoRequest(content = content))
        _memos.value = _memos.value.map { if (it.name == updated.name) updated else it }
        return updated
    }

    suspend fun delete(name: String) {
        api.deleteMemo(name.memoUid())
        _memos.value = _memos.value.filterNot { it.name == name }
    }

    /**
     * Verifies a *candidate* server URL + token by listing memos directly,
     * without going through the AuthStore-backed default client. Used by login
     * so we can validate creds before saving them — avoids saving bad creds and
     * the navigation race where the settings listener would otherwise flip
     * isAuthenticated before verify completes.
     */
    suspend fun verifyCreds(serverUrl: String, token: String): Result<Unit> = runCatching {
        val client = verifyClientFactory(serverUrl, token)
        try {
            // Probing the memos-list endpoint with pageSize=1 — the older
            // /auth/status endpoint was removed in memos 0.22+, and listing
            // memos is the canonical "does this token work" check.
            // expectSuccess=true on the client converts a non-2xx into a throw.
            client.get("api/v1/memos") { parameter("pageSize", 1) }
        } finally {
            client.close()
        }
    }
}
