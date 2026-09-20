package nu.bacher.memos.data.repo

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException

/**
 * Coarse buckets for HTTP/network failures. Single source of truth — both
 * retry policy ([isRetriable]) and user-facing error messages dispatch off
 * this so a new exception type only needs to be classified once.
 */
enum class ErrorKind {
    NETWORK,
    AUTH,

    /**
     * 429. Retriable like [SERVER], but deliberately *not* [SERVER]: a rate
     * limiter says nothing about whether the payload is acceptable, so it must
     * not burn the queue's poison budget (see `MemoRepository.syncPending`).
     */
    RATE_LIMIT,
    SERVER,
    OTHER,
}

fun Throwable.classify(): ErrorKind {
    if (this is ClientRequestException) {
        return when (response.status.value) {
            401, 403 -> ErrorKind.AUTH
            // 408 is the server telling us the request didn't arrive in time —
            // the same shape of failure as a stalled socket, and just as
            // retriable.
            408 -> ErrorKind.NETWORK
            429 -> ErrorKind.RATE_LIMIT
            else -> ErrorKind.OTHER
        }
    }
    if (this is ServerResponseException) return ErrorKind.SERVER
    if (this is ResponseException) return ErrorKind.OTHER // 3xx + anything else Ktor surfaces here
    if (this is HttpRequestTimeoutException) return ErrorKind.NETWORK
    // Network-layer failures show up as IOException (or a platform-specific
    // subclass thereof). Match by name to stay common-source-set friendly —
    // java.io.IOException isn't visible from commonMain without androidMain
    // glue and we'd rather not split this helper.
    var current: Throwable? = this
    while (current != null) {
        val name = current::class.qualifiedName
        if (name != null && (
                name.endsWith("IOException") ||
                    name.endsWith("UnresolvedAddressException") ||
                    name.endsWith("ConnectException") ||
                    name.endsWith("SocketTimeoutException") ||
                    name.endsWith("UnknownHostException")
                )
        ) return ErrorKind.NETWORK
        current = current.cause
    }
    return ErrorKind.OTHER
}

/**
 * Whether [this] is the kind of failure that should keep the optimistic
 * cache write and enqueue the action for retry, vs. roll back immediately.
 *
 * Retriable: network outages, timeouts, 429, 5xx. The server might accept the
 * request later, so we hold onto the user's edit.
 * Non-retriable: the rest of the 4xx range (incl. auth). The server actively
 * rejected the request — retrying won't fix it; the cache needs to roll back
 * and the error needs to surface to the user.
 */
fun Throwable.isRetriable(): Boolean = when (classify()) {
    ErrorKind.NETWORK, ErrorKind.RATE_LIMIT, ErrorKind.SERVER -> true
    ErrorKind.AUTH, ErrorKind.OTHER -> false
}
