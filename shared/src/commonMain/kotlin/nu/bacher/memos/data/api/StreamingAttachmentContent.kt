package nu.bacher.memos.data.api

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readAtMostTo
import kotlinx.serialization.builtins.serializer

/**
 * Streams the attachment-create JSON envelope and a base64-encoded source body
 * directly to the wire. Lets us upload large files without holding the source
 * bytes, the base64 string, and the JSON envelope in memory all at once — see
 * MemoRepository.uploadAttachment.
 *
 * The chunk size is a multiple of 3 so each Base64.encode call lines up on a
 * triplet boundary; only the final, partial chunk emits "=" padding. Encoding
 * a non-final chunk on a non-triplet boundary would emit padding mid-stream,
 * producing an invalid base64 string.
 */
internal class StreamingAttachmentContent(
    filename: String,
    type: String,
    memo: String?,
    private val byteCount: Long,
    private val openSource: () -> RawSource,
) : OutgoingContent.WriteChannelContent() {

    private val prefixBytes: ByteArray = buildPrefix(filename, type, memo)

    override val contentType: ContentType = ContentType.Application.Json

    override val contentLength: Long =
        prefixBytes.size.toLong() + base64EncodedLength(byteCount) + SUFFIX.size.toLong()

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun writeTo(channel: ByteWriteChannel) {
        channel.writeFully(prefixBytes)

        val source: Source = openSource().buffered()
        val buf = ByteArray(CHUNK_BYTES)
        try {
            while (true) {
                var filled = 0
                while (filled < CHUNK_BYTES) {
                    val n = source.readAtMostTo(buf, filled, CHUNK_BYTES)
                    if (n <= 0) break
                    filled += n
                }
                if (filled == 0) break
                channel.writeFully(Base64.encode(buf, 0, filled).encodeToByteArray())
                if (filled < CHUNK_BYTES) break
            }
        } finally {
            source.close()
        }

        channel.writeFully(SUFFIX)
    }

    private companion object {
        // 21845 * 3. ~64 KiB of source bytes → ~85 KiB of base64 per chunk.
        const val CHUNK_BYTES: Int = 65535

        val SUFFIX: ByteArray = "\"}}".encodeToByteArray()

        // Standard padded base64: groups of 3 bytes → 4 chars, last group padded.
        fun base64EncodedLength(byteCount: Long): Long = 4L * ((byteCount + 2) / 3)

        fun buildPrefix(filename: String, type: String, memo: String?): ByteArray {
            val sb = StringBuilder()
            sb.append("{\"attachment\":{\"filename\":")
            sb.append(MemosJson.encodeToString(String.serializer(), filename))
            sb.append(",\"type\":")
            sb.append(MemosJson.encodeToString(String.serializer(), type))
            if (memo != null) {
                sb.append(",\"memo\":")
                sb.append(MemosJson.encodeToString(String.serializer(), memo))
            }
            sb.append(",\"content\":\"")
            return sb.toString().encodeToByteArray()
        }
    }
}
