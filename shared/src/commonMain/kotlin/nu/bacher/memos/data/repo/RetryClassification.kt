package nu.bacher.memos.data.repo

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException

/**
 * Whether [this] is the kind of failure that should keep the optimistic
 * cache write and enqueue the action for retry, vs. roll back immediately.
 *
 * Retriable: network outages, timeouts, 5xx. The server might accept the
 * request later, so we hold onto the user's edit.
 * Non-retriable: 4xx. The server actively rejected the request — auth
 * failure, validation error, conflict. Retrying won't fix it; the cache
 * needs to roll back and the error needs to surface to the user.
 *
 * Anything we can't classify (unexpected exceptions, programmer errors)
 * is treated as non-retriable so we don't silently swallow bugs.
 */
fun Throwable.isRetriable(): Boolean {
    if (this is ClientRequestException) return false // 4xx
    if (this is ServerResponseException) return true // 5xx
    if (this is ResponseException) return false      // 3xx + others Ktor surfaces as ResponseException
    if (this is HttpRequestTimeoutException) return true
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
        ) return true
        current = current.cause
    }
    return false
}
