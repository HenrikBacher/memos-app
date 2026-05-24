package nu.bacher.memos.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Subset of the memos v1 REST API needed by the app. Endpoints follow the
 * resource-name convention (e.g. memos/{uid}). The Ktor client is configured
 * with a per-request host rewrite (see HttpClientFactory) so callers pass just
 * the path here.
 */
class MemosApi(private val client: HttpClient) {

    suspend fun listMemos(
        pageSize: Int = 50,
        pageToken: String? = null,
        filter: String? = null,
    ): ListMemosResponse = client.get("api/v1/memos") {
        parameter("pageSize", pageSize)
        pageToken?.let { parameter("pageToken", it) }
        filter?.let { parameter("filter", it) }
    }.body()

    suspend fun createMemo(request: CreateMemoRequest): MemoDto = client.post("api/v1/memos") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    suspend fun getMemo(uid: String): MemoDto = client.get("api/v1/memos/$uid").body()

    suspend fun updateMemo(uid: String, request: UpdateMemoRequest): MemoDto =
        client.patch("api/v1/memos/$uid") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun deleteMemo(uid: String) {
        client.delete("api/v1/memos/$uid")
    }

    suspend fun createAttachment(request: CreateAttachmentRequest): AttachmentDto =
        client.post("api/v1/attachments") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
}
