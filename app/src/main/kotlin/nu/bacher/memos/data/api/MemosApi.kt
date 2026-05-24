package nu.bacher.memos.data.api

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Subset of the memos v1 REST API needed by the app. Endpoints follow the
 * resource-name convention (e.g. memos/{uid}), so [name] in path arguments is
 * just the uid portion — we let Retrofit build "memos/{uid}".
 */
interface MemosApi {

    @GET("api/v1/memos")
    suspend fun listMemos(
        @Query("pageSize") pageSize: Int = 50,
        @Query("pageToken") pageToken: String? = null,
        @Query("filter") filter: String? = null,
    ): ListMemosResponse

    @POST("api/v1/memos")
    suspend fun createMemo(@Body request: CreateMemoRequest): MemoDto

    @GET("api/v1/memos/{uid}")
    suspend fun getMemo(@Path("uid") uid: String): MemoDto

    @PATCH("api/v1/memos/{uid}")
    suspend fun updateMemo(
        @Path("uid") uid: String,
        @Body request: UpdateMemoRequest,
    ): MemoDto

    @DELETE("api/v1/memos/{uid}")
    suspend fun deleteMemo(@Path("uid") uid: String)
}

/** Extract the uid from a resource name like "memos/abc123" → "abc123". */
fun String.memoUid(): String = substringAfter('/', this)
