package nu.bacher.memos.di

import nu.bacher.memos.data.auth.AuthStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that stamps a Bearer token onto requests that don't
 * already have one. Used by Coil's image fetcher so authenticated attachment
 * URLs work — public ones (S3, externalLink) tolerate the extra header fine.
 */
class MemosAuthInterceptor(private val authStore: AuthStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = authStore.read()?.token
        val authed = if (token != null && request.header("Authorization") == null) {
            request.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            request
        }
        return chain.proceed(authed)
    }
}
