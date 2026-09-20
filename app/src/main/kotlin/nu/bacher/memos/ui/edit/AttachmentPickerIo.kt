package nu.bacher.memos.ui.edit

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.asSource
import nu.bacher.memos.data.api.AttachmentSource

/**
 * Describes what a `content://` URI points at — display name, MIME type, and
 * size — and hands back a factory that reopens the stream on demand.
 *
 * Nothing is read into memory: the upload streams straight off the
 * ContentResolver (see [nu.bacher.memos.data.api.StreamingAttachmentContent]),
 * which is what keeps a 20 MB attachment off the heap.
 *
 * Returns null if the URI can't be read at all. Every provider call is
 * guarded: a revoked URI grant (resuming against a stale picker URI after
 * process death) throws SecurityException, and providers that dislike the
 * projection throw IllegalArgumentException — none of which should propagate
 * into the caller's coroutine and take the screen down.
 *
 * [sizeLimit] bounds the last-resort measurement: a source that declares no
 * size is read only far enough to prove it is over the limit, never in full.
 *
 * Always called from the IO dispatcher — the metadata queries hit a provider.
 */
internal suspend fun attachmentSourceFor(
    context: Context,
    uri: Uri,
    sizeLimit: Long,
): AttachmentSource? = withContext(Dispatchers.IO) {
    // Application-scoped: this resolver is captured by the returned openSource
    // lambda, which outlives the picking screen (the upload runs on
    // viewModelScope). An Activity's resolver would pin the destroyed Activity
    // and its view hierarchy for the whole upload.
    val resolver = context.applicationContext.contentResolver
    val (metaName, metaSize) = queryMeta(resolver, uri)

    // Content-Length has to be exact for the streamed upload. Most providers
    // declare SIZE; for those that don't, the file descriptor usually knows,
    // and only if that fails too do we read to count.
    val size = metaSize
        ?: descriptorLength(resolver, uri)
        ?: measure(resolver, uri, sizeLimit)
        ?: return@withContext null

    AttachmentSource(
        filename = metaName ?: uri.lastPathSegment ?: "file",
        mimeType = runCatching { resolver.getType(uri) }.getOrNull()
            ?: "application/octet-stream",
        byteCount = size,
        openSource = { resolver.openSource(uri) },
    )
}

private fun ContentResolver.openSource(uri: Uri): RawSource =
    (openInputStream(uri) ?: error("could not open $uri")).asSource()

/** Display name and size, either of which the provider may withhold. */
private fun queryMeta(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> = runCatching {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    resolver.query(uri, projection, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null to null
        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else null
        // A declared 0 is a real answer (empty file), not "unknown" — only a
        // negative value means the provider is refusing to say.
        val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
        name to size?.takeIf { it >= 0 }
    }
}.getOrNull() ?: (null to null)

/** Size from the file descriptor — one binder call, no read. */
private fun descriptorLength(resolver: ContentResolver, uri: Uri): Long? = runCatching {
    resolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
        fd.length.takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH && it >= 0 }
    }
}.getOrNull()

/**
 * Last resort: count the bytes behind [uri] without retaining them, stopping
 * as soon as the total exceeds [limit].
 *
 * The cap matters — the caller rejects oversized attachments *after* this
 * returns, so without it picking a multi-gigabyte file would stream the whole
 * thing off the provider just to be told it is too large.
 */
private fun measure(resolver: ContentResolver, uri: Uri, limit: Long): Long? = runCatching {
    resolver.openInputStream(uri)?.use { stream ->
        val buf = ByteArray(DISCARD_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            // Over the limit is all the caller needs to know to reject it.
            if (total > limit) break
        }
        total
    }
}.getOrNull()

private const val DISCARD_BUFFER_BYTES = 64 * 1024
