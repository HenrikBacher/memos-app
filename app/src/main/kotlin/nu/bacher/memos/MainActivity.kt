package nu.bacher.memos

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.ui.link.ProvideMemosUriHandler
import nu.bacher.memos.ui.navigation.MemosNavHost
import nu.bacher.memos.ui.navigation.NavLaunch
import nu.bacher.memos.ui.theme.MemosTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val memoRepository: MemoRepository by inject()

    /**
     * Compose state for the current intent's deep-link payload. Lifted out of
     * onCreate so [onNewIntent] can update it when a notification or share
     * arrives while the activity is already in the foreground — without this
     * the new extras would be silently dropped (the existing Activity instance
     * is reused under launchMode=singleTask, and onCreate doesn't run again).
     */
    private var navLaunch by mutableStateOf<NavLaunch>(NavLaunch.None)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        navLaunch = readLaunch(intent)

        setContent {
            MemosTheme {
                ProvideMemosUriHandler {
                    MemosNavHost(launch = navLaunch)
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
    }

    override fun onResume() {
        super.onResume()
        // Flush the offline write queue on every resume. A no-op when the
        // queue is empty; when it's not, the user gets their pending writes
        // pushed as soon as they come back to the app from background.
        lifecycleScope.launch {
            try {
                memoRepository.syncPending()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline / transient — actions stay queued for the next resume.
            }
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

    companion object {
        const val EXTRA_OPEN_MEMO_NAME = "open_memo_name"
        const val EXTRA_OPEN_NEW_MEMO = "open_new_memo"
    }
}
