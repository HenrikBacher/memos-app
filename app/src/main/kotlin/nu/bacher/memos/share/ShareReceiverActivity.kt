package nu.bacher.memos.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import nu.bacher.memos.MainActivity
import nu.bacher.memos.R
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.repo.MemoRepository
import org.koin.android.ext.android.inject

/**
 * Receives ACTION_SEND text. If the user is logged in, the text is posted
 * directly as a new memo. Otherwise we hand them off to MainActivity so they
 * can sign in first.
 */
class ShareReceiverActivity : ComponentActivity() {

    private val memoRepo: MemoRepository by inject()
    private val authStore: AuthStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()

        if (text.isEmpty() && subject.isEmpty()) {
            finish()
            return
        }

        val content = listOf(subject, text).filter { it.isNotEmpty() }.joinToString("\n\n")

        if (authStore.read() == null) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(MainActivity.EXTRA_OPEN_NEW_MEMO, true)
                putExtra(Intent.EXTRA_TEXT, content)
            })
            finish()
            return
        }

        Toast.makeText(this, getString(R.string.share_saving), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val msg = runCatching { memoRepo.create(content) }
                .fold({ R.string.share_saved }, { R.string.share_failed })
            Toast.makeText(this@ShareReceiverActivity, getString(msg), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
