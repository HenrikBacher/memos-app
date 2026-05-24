package nu.bacher.memos.data.api

import android.util.Log
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import nu.bacher.memos.data.auth.AuthStore

/**
 * Rewrites every request to point at the configured memos server and attaches
 * the bearer token. The Retrofit base URL is a dummy localhost URL —
 * the real host/scheme/port come from [AuthStore].
 *
 * If no credentials are present we throw a clear IOException instead of letting
 * the request fall through to the dummy localhost URL (which would surface as
 * a confusing "cleartext to localhost" NetworkSecurityConfig error).
 */
class AuthInterceptor(
    private val authStore: AuthStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val config = authStore.read()
            ?: throw IOException("Not logged in to memos — open the app and sign in again")

        val baseUrl = (config.serverUrl + "/").toHttpUrlOrNull()
            ?: throw IOException("Invalid memos server URL: ${config.serverUrl}")

        val rewritten = req.url.newBuilder()
            .scheme(baseUrl.scheme)
            .host(baseUrl.host)
            .port(baseUrl.port)
            .build()

        Log.d(TAG, "${req.method} ${req.url} -> $rewritten")

        val newReq = req.newBuilder()
            .url(rewritten)
            .header("Authorization", "Bearer ${config.token}")
            .header("Accept", "application/json")
            .build()

        return chain.proceed(newReq)
    }

    private companion object {
        const val TAG = "AuthInterceptor"
    }
}
