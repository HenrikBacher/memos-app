package nu.bacher.memos

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nu.bacher.memos.data.auth.SsoAuthenticator
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.data.settings.ThemePreferences
import nu.bacher.memos.ui.link.ProvideMemosUriHandler
import nu.bacher.memos.ui.navigation.MemosNavHost
import nu.bacher.memos.ui.navigation.NavLaunch
import nu.bacher.memos.ui.theme.MemosTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val memoRepository: MemoRepository by inject()
    private val themePreferences: ThemePreferences by inject()

    /**
     * Compose state for the current intent's deep-link payload. Lifted out of
     * onCreate so [onNewIntent] can update it when a notification or share
     * arrives while the activity is already in the foreground — without this
     * the new extras would be silently dropped (the existing Activity instance
     * is reused under launchMode=singleTask, and onCreate doesn't run again).
     */
    private var navLaunch by mutableStateOf<NavLaunch>(NavLaunch.None)

    /**
     * The SSO redirect URI the identity provider bounced us back with, for
     * the login screen to finish the exchange.
     *
     * Only the fallback route: an Auth Tab returns the redirect as an activity
     * result instead, straight to the login screen. This fires when the user's
     * browser doesn't implement Auth Tab and degrades to a plain Custom Tab,
     * so the redirect resolves through the manifest intent-filter and arrives
     * at [onNewIntent] — never a fresh onCreate, given launchMode=singleTask.
     */
    private var ssoRedirect by mutableStateOf<String?>(null)

    /**
     * Flushes the offline write queue whenever a default network becomes
     * available while the activity is started. The system also invokes
     * onAvailable right after registration when a network is already up, so
     * this doubles as the "flush on app open / return from background" hook —
     * no separate onResume flush needed.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Called on a binder thread; hop to the lifecycle scope.
            lifecycleScope.launch { flushPendingQueue() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        navLaunch = readLaunch(intent)
        ssoRedirect = readSsoRedirect(intent)

        setContent {
            // Read theme settings synchronously first so the very first frame
            // already reflects the user's choice — collecting only the flow
            // would briefly render with defaults before swapping.
            val initial = remember { themePreferences.read() }
            val theme by themePreferences.settingsFlow.collectAsState(initial = initial)
            MemosTheme(settings = theme) {
                ProvideMemosUriHandler {
                    MemosNavHost(
                        launch = navLaunch,
                        ssoRedirect = ssoRedirect,
                        onSsoRedirectConsumed = { ssoRedirect = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Required so subsequent getIntent() reads see the new payload; not
        // strictly load-bearing here since we read from [intent] directly,
        // but it keeps the Activity contract honest.
        setIntent(intent)
        val next = readLaunch(intent)
        if (next != NavLaunch.None) navLaunch = next
        readSsoRedirect(intent)?.let { ssoRedirect = it }
    }

    override fun onStart() {
        super.onStart()
        getSystemService<ConnectivityManager>()?.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStop() {
        getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(networkCallback)
        super.onStop()
    }

    private suspend fun flushPendingQueue() {
        try {
            memoRepository.syncPending()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline / transient — actions stay queued for the next network
            // regain or app open.
        }
    }

    private fun readLaunch(intent: Intent?): NavLaunch {
        intent ?: return NavLaunch.None
        val openMemoName = intent.getStringExtra(EXTRA_OPEN_MEMO_NAME)
        val openNew = intent.getBooleanExtra(EXTRA_OPEN_NEW_MEMO, false)
        val initialContent = intent.getStringExtra(Intent.EXTRA_TEXT)
        return when {
            openMemoName != null -> NavLaunch.OpenMemo(openMemoName)
            openNew -> NavLaunch.NewMemo(initialContent)
            else -> NavLaunch.None
        }
    }

    /**
     * The SSO redirect, if this Intent is one. Matched on the scheme declared
     * in the manifest — the query string is left intact for
     * [nu.bacher.memos.data.auth.SsoAuthenticator.parseRedirect] to read, so
     * only one place knows the callback's shape.
     */
    private fun readSsoRedirect(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        return data.toString().takeIf {
            data.scheme.equals(SsoAuthenticator.REDIRECT_SCHEME, ignoreCase = true)
        }
    }

    companion object {
        const val EXTRA_OPEN_MEMO_NAME = "open_memo_name"
        const val EXTRA_OPEN_NEW_MEMO = "open_new_memo"
    }
}
