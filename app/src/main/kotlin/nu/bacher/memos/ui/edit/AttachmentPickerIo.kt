package nu.bacher.memos.ui.edit

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.asSource
import kotlinx.io.buffered
import nu.bacher.memos.data.api.AttachmentSource

/**
 * Describes what a `content://` URI points at — display name, MIME type, and
 * size — and hands back a factory that reopens the stream on demand.
 *
 * Nothing is read into memory here: the upload streams straight off the
 * ContentResolver (see [nu.bacher.memos.data.api.StreamingAttachmentContent]),
 * which is what keeps a 20 MB attachment off the heap. Returns null if the URI
 * can't be opened or its size can't be established.
 *
 * Always called from the IO dispatcher — the metadata queries hit a provider.
 */
internal suspend fun attachmentSourceFor(context: Context, uri: Uri): AttachmentSource? =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val meta = queryMeta(context, uri)
        val filename = meta?.first ?: uri.lastPathSegment ?: "file"
        val mime = resolver.getType(uri) ?: "application/octet-stream"

        // Providers may report no size (SIZE null, or a stream with no
        // backing file). Content-Length has to be exact for the streamed
        // upload, so measure it by draining a throwaway read rather than
        // buffering the file to find out how big it is.
        val size = meta?.second ?: measure(context, uri) ?: return@withContext null

        val open: () -> RawSource = {
            val stream = resolver.openInputStream(uri)
                ?: error("could not open $uri")
            stream.asSource()
        }
        // Fail here rather than at upload time if the URI can't be opened at all.
        runCatching { open().close() }.getOrElse { return@withContext null }

        AttachmentSource(
            filename = filename,
            mimeType = mime,
            byteCount = size,
            openSource = open,
        )
    }

/** Display name + size, either of which the provider may withhold. */
private fun queryMeta(context: Context, uri: Uri): Pair<String?, Long?>? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else null
        val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
        name to size?.takeIf { it > 0 }
    }
}

/** Counts the bytes behind [uri] without retaining them. */
private fun measure(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val source = stream.asSource().buffered()
        val buf = ByteArray(DISCARD_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val n = source.readAtMostTo(buf, 0, buf.size)
            if (n <= 0) break
            total += n
        }
        total
    }
}.getOrNull()

private const val DISCARD_BUFFER_BYTES = 64 * 1024
