package nu.bacher.memos.di

import nu.bacher.memos.data.auth.AuthStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that stamps the Memos bearer token onto image requests
 * that target the user's configured Memos host — and only that host.
 *
 * Memos markdown can reference arbitrary external image URLs (e.g.
 * tracking pixels). Coil shares this OkHttp client across all image
 * fetches, so attaching the token unconditionally would leak it to any
 * third-party host referenced from a memo. Compare hosts and skip
 * non-matches.
 */
class MemosAuthInterceptor(private val authStore: AuthStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)

        val config = authStore.read() ?: return chain.proceed(request)
        val serverHost = config.serverUrl.toHttpUrlOrNull()?.host ?: return chain.proceed(request)
        if (!request.url.host.equals(serverHost, ignoreCase = true)) {
            return chain.proceed(request)
        }

        val authed = request.newBuilder()
            .header("Authorization", "Bearer ${config.token}")
            .build()
        return chain.proceed(authed)
    }
}
