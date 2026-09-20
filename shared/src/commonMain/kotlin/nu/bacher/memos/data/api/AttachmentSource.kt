package nu.bacher.memos.data.api

import kotlinx.io.RawSource

/**
 * A file the user picked, described well enough to stream it to the server
 * without ever holding it in memory.
 *
 * [openSource] is a factory, not a handle: [StreamingAttachmentContent] opens
 * it when it writes the request body and closes it afterwards, so a retried
 * request re-opens rather than replaying an exhausted stream. Platform code
 * supplies the lambda (on Android, reopening the `content://` URI).
 *
 * [byteCount] is the *declared* size, needed up front for the request's
 * Content-Length. It also lets callers reject an oversized file before a
 * single byte is read.
 */
data class AttachmentSource(
    val filename: String,
    val mimeType: String,
    val byteCount: Long,
    val openSource: () -> RawSource,
)
