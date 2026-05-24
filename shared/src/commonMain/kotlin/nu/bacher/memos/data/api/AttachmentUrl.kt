package nu.bacher.memos.data.api

import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import nu.bacher.memos.data.auth.AuthStore

/**
 * Resolves the URL the memos server serves this attachment's bytes from.
 *
 * External resources (S3, etc.) are served from `externalLink` verbatim;
 * server-hosted attachments are at `{server}/file/{attachment.name}/{filename}`
 * — see the memos `frontend/src/utils/attachment.ts` mapping.
 */
fun AttachmentDto.urlOrNull(authStore: AuthStore): String? {
    externalLink?.takeIf { it.isNotBlank() }?.let { return it }
    if (name.isBlank()) return null
    val server = authStore.read()?.serverUrl ?: return null
    val builder = URLBuilder().takeFrom(server.trimEnd('/'))
    // attachment.name is like "attachments/{id}" — appendPathSegments URL-encodes
    // each segment, so a stray '/' or space in a filename can't break the URL.
    val nameSegments = name.split('/').filter { it.isNotEmpty() }
    builder.appendPathSegments("file", *nameSegments.toTypedArray())
    if (filename.isNotBlank()) builder.appendPathSegments(filename)
    return builder.buildString()
}

fun AttachmentDto.isImage(): Boolean = type.startsWith("image/", ignoreCase = true)
fun AttachmentDto.isVideo(): Boolean = type.startsWith("video/", ignoreCase = true)
fun AttachmentDto.isAudio(): Boolean = type.startsWith("audio/", ignoreCase = true)
