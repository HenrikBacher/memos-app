package nu.bacher.memos.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nu.bacher.memos.MainActivity
import nu.bacher.memos.R
import nu.bacher.memos.data.auth.AuthStore
import nu.bacher.memos.data.repo.MemoRepository
import nu.bacher.memos.ui.edit.MemoEditViewModel
import nu.bacher.memos.ui.edit.readPickedFile
import org.koin.android.ext.android.inject

/**
 * Receives ACTION_SEND / ACTION_SEND_MULTIPLE. Text is posted directly as a
 * new memo; shared images/videos are uploaded as attachments first and the
 * memo is created referencing them. If the user isn't logged in, text is
 * handed off to MainActivity's new-memo screen (attachments can't be — the
 * upload needs credentials, so the user is asked to sign in and share again).
 */
class ShareReceiverActivity : ComponentActivity() {

    private val memoRepo: MemoRepository by inject()
    private val authStore: AuthStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val streams = readStreams(intent)

        if (text.isEmpty() && subject.isEmpty() && streams.isEmpty()) {
            finish()
            return
        }

        val content = listOf(subject, text).filter { it.isNotEmpty() }.joinToString("\n\n")

        if (authStore.read() == null) {
            if (streams.isNotEmpty()) {
                Toast.makeText(this, getString(R.string.share_sign_in_first), Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } else {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(MainActivity.EXTRA_OPEN_NEW_MEMO, true)
                    putExtra(Intent.EXTRA_TEXT, content)
                })
            }
            finish()
            return
        }

        Toast.makeText(this, getString(R.string.share_saving), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val msg = try {
                val attachments = streams.map { uri ->
                    val picked = readPickedFile(this@ShareReceiverActivity, uri)
                        ?: error("could not read shared stream $uri")
                    check(picked.bytes.size <= MemoEditViewModel.MAX_ATTACHMENT_BYTES) {
                        "shared file exceeds attachment size limit"
                    }
                    memoRepo.uploadAttachment(picked.bytes, picked.filename, picked.mimeType)
                }
                memoRepo.create(content, attachments = attachments)
                R.string.share_saved
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                R.string.share_failed
            }
            Toast.makeText(this@ShareReceiverActivity, getString(msg), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun readStreams(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}
