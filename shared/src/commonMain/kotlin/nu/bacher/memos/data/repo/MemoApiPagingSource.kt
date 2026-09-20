package nu.bacher.memos.data.repo

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import nu.bacher.memos.data.api.MemoDto
import nu.bacher.memos.data.api.MemosApi

/**
 * Pages memos directly from the server, no Room involvement.
 *
 * Used for server-side search: when the user types a query into the list
 * screen, the cached + RemoteMediator path is bypassed because the cache
 * only knows about pages the user has already scrolled into.
 *
 * When the network is down, the *first* page falls back to [offlineFallback]
 * (a LIKE scan of the cache) rather than surfacing a paging error — search is
 * the one place where partial local results beat a dead end. The fallback page
 * is terminal: there's no meaningful "next page" of a cache scan, and a
 * subsequent APPEND would just fail again. Non-network errors (a server that
 * rejects the `content_search` filter, an expired token) still surface, since
 * cached results would paper over a problem the user needs to see.
 *
 * Keys are the opaque [nu.bacher.memos.data.api.ListMemosResponse.nextPageToken]
 * the memos API hands back; [getRefreshKey] always returns null so a refresh
 * restarts from the first page (the natural read for search results — there
 * is no stable "anchor item" to scroll back to under a changing filter).
 */
class MemoApiPagingSource(
    private val api: MemosApi,
    private val filter: String?,
    private val offlineFallback: suspend () -> List<MemoDto>,
) : PagingSource<String, MemoDto>() {

    override fun getRefreshKey(state: PagingState<String, MemoDto>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, MemoDto> {
        return try {
            val response = api.listMemos(
                pageSize = params.loadSize,
                pageToken = params.key,
                filter = filter,
            )
            LoadResult.Page(
                data = response.memos,
                prevKey = null,
                nextKey = response.nextPageToken?.takeIf { it.isNotBlank() },
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (params.key == null && t.classify() == ErrorKind.NETWORK) {
                val cached = offlineFallback()
                if (cached.isNotEmpty()) {
                    return LoadResult.Page(data = cached, prevKey = null, nextKey = null)
                }
            }
            LoadResult.Error(t)
        }
    }
}
