package nu.bacher.memos.ui.edit

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.asSource
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
        // Application-scoped: this resolver is captured by the returned
        // openSource lambda, which outlives the picking screen (the upload
        // runs on viewModelScope). An Activity's resolver would pin the
        // destroyed Activity and its view hierarchy for the whole upload.
        val resolver = context.applicationContext.contentResolver
        val (metaName, metaSize) = queryMeta(resolver, uri)

        // Content-Length has to be exact for the streamed upload. Most
        // providers declare SIZE; for those that don't, the file descriptor
        // usually knows, and only if that fails too do we resort to reading
        // the whole thing once just to count it.
        val size = metaSize ?: descriptorLength(resolver, uri) ?: measure(resolver, uri)
            ?: return@withContext null

        AttachmentSource(
            filename = metaName ?: uri.lastPathSegment ?: "file",
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
            byteCount = size,
            openSource = { resolver.openSource(uri) },
        )
    }

private fun ContentResolver.openSource(uri: Uri): RawSource =
    (openInputStream(uri) ?: error("could not open $uri")).asSource()

/** Display name and size, either of which the provider may withhold. */
private fun queryMeta(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    return resolver.query(uri, projection, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null to null
        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else null
        val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
        name to size?.takeIf { it > 0 }
    } ?: (null to null)
}

/** Size from the file descriptor — one binder call, no read. */
private fun descriptorLength(resolver: ContentResolver, uri: Uri): Long? = runCatching {
    resolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
        fd.length.takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH && it > 0 }
    }
}.getOrNull()

/** Last resort: count the bytes behind [uri] without retaining them. */
private fun measure(resolver: ContentResolver, uri: Uri): Long? = runCatching {
    resolver.openInputStream(uri)?.use { it.transferTo(OutputStream.nullOutputStream()) }
}.getOrNull()
