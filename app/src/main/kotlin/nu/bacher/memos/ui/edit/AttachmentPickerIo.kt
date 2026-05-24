package nu.bacher.memos.ui.edit

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PickedFile(
    val bytes: ByteArray,
    val filename: String,
    val mimeType: String,
)

/**
 * Reads the bytes a content:// URI points at, plus its display name and MIME
 * type. Returns null if the URI can't be opened or the read fails. Always
 * called from the IO dispatcher — picked files can be tens of MB.
 */
internal suspend fun readPickedFile(context: Context, uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val bytes = runCatching {
        resolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull() ?: return@withContext null

    val filename = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "file"
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    PickedFile(bytes = bytes, filename = filename, mimeType = mime)
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    val resolver = context.contentResolver
    return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx) else null
        } else {
            null
        }
    }
}
