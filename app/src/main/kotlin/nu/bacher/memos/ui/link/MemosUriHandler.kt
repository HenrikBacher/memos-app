package nu.bacher.memos.ui.link

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import nu.bacher.memos.R

/**
 * Provides a [UriHandler] that opens URIs via [Intent.ACTION_VIEW] and shows a
 * Toast if no app can handle them. The Compose default ([androidx.compose.ui.
 * platform.AndroidUriHandler]) throws [IllegalArgumentException] on failure,
 * which the markdown renderer's link interaction listener silently swallows —
 * so a missing browser would leave the user with no feedback.
 */
@Composable
fun ProvideMemosUriHandler(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val handler = remember(context) { MemosUriHandler(context) }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        content()
    }
}

private class MemosUriHandler(private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            // Required when the resolved context isn't an Activity (e.g. the
            // app's Application context from background work).
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.attachment_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
