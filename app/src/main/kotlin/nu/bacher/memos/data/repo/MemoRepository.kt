package nu.bacher.memos.data.repo

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import nu.bacher.memos.data.api.CreateMemoRequest
import nu.bacher.memos.data.api.MemoDto
import nu.bacher.memos.data.api.MemosApi
import nu.bacher.memos.data.api.UpdateMemoRequest
import nu.bacher.memos.data.api.memoUid
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Thin wrapper around the memos API. The list is cached in-memory for the
 * current process — refresh is explicit. We deliberately don't add a local
 * memo cache to disk (memos itself is the source of truth and supports
 * multi-device editing).
 */
@Singleton
class MemoRepository @Inject constructor(
    private val api: MemosApi,
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
     * Verifies a *candidate* server URL + token by hitting auth/status directly,
     * without going through the Hilt-provided OkHttp client (which routes via
     * AuthInterceptor using whatever is currently in AuthStore). Used by login
     * so we can validate creds before saving them — avoids saving bad creds and
     * the navigation race where the SharedPreferences listener would otherwise
     * flip isAuthenticated before verify completes.
     */
    suspend fun verifyCreds(serverUrl: String, token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                // Hit the memos-list endpoint with pageSize=1 instead of
                // /auth/status — the latter was removed in memos 0.22+, and
                // listing memos is the canonical "does this token work" probe.
                val request = Request.Builder()
                    .url("${serverUrl.trimEnd('/')}/api/v1/memos?pageSize=1")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code} ${resp.message}")
                    }
                }
            }
        }
}
