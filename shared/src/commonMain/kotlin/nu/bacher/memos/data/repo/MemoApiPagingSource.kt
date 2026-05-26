package nu.bacher.memos.data.repo

import androidx.paging.PagingSource
import androidx.paging.PagingState
import nu.bacher.memos.data.api.MemoDto
import nu.bacher.memos.data.api.MemosApi

/**
 * Pages memos directly from the server, no Room involvement.
 *
 * Used for server-side search: when the user types a query into the list
 * screen, the cached + RemoteMediator path is bypassed because the cache
 * only knows about pages the user has already scrolled into. The trade-off
 * is that this source needs network — when offline, the user sees a paging
 * error and can clear the query to return to the cached view.
 *
 * Keys are the opaque [nu.bacher.memos.data.api.ListMemosResponse.nextPageToken]
 * the memos API hands back; [getRefreshKey] always returns null so a refresh
 * restarts from the first page (the natural read for search results — there
 * is no stable "anchor item" to scroll back to under a changing filter).
 */
class MemoApiPagingSource(
    private val api: MemosApi,
    private val filter: String?,
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
            LoadResult.Error(t)
        }
    }
}
