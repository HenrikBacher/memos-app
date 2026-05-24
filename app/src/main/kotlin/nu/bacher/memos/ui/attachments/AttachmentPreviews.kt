package nu.bacher.memos.ui.attachments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import nu.bacher.memos.R
import nu.bacher.memos.data.api.AttachmentDto
import nu.bacher.memos.data.api.isAudio
import nu.bacher.memos.data.api.isImage
import nu.bacher.memos.data.api.isVideo
import nu.bacher.memos.data.api.urlOrNull
import nu.bacher.memos.data.auth.AuthStore
import org.koin.compose.koinInject

private const val PREVIEW_LIMIT = 4

/**
 * Compact, horizontally scrollable preview shown inside a memo card. Images
 * render as thumbnails; everything else gets a typed icon + filename tile.
 * Capped to [PREVIEW_LIMIT] tiles to keep card heights bounded — a trailing
 * "+N" tile signals overflow.
 */
@Composable
fun AttachmentCardPreview(
    attachments: List<AttachmentDto>,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    val authStore: AuthStore = koinInject()
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val visible = attachments.take(PREVIEW_LIMIT)
    val overflow = (attachments.size - visible.size).coerceAtLeast(0)
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items = visible, key = { it.name.ifEmpty { it.filename } }) { att ->
            AttachmentTile(
                attachment = att,
                authStore = authStore,
                onOpen = { url ->
                    if (att.isImage()) {
                        viewing = url to att.filename.ifBlank { null }
                    } else {
                        openExternally(context, url, att.type)
                    }
                },
            )
        }
        if (overflow > 0) {
            item(key = "__overflow__") {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.attachment_more, overflow),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
    viewing?.let { (url, desc) ->
        ImageViewerDialog(url = url, contentDescription = desc, onDismiss = { viewing = null })
    }
}

@Composable
private fun AttachmentTile(
    attachment: AttachmentDto,
    authStore: AuthStore,
    onOpen: (String) -> Unit,
) {
    val url = attachment.urlOrNull(authStore)
    val tappable: Modifier = if (url != null) Modifier.clickable { onOpen(url) } else Modifier
    if (attachment.isImage() && url != null) {
        AsyncImage(
            model = url,
            contentDescription = attachment.filename.ifBlank {
                stringResource(R.string.attachment_image)
            },
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .then(tappable),
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(tappable)
                .padding(horizontal = 10.dp),
        ) {
            Icon(
                attachment.typeIcon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = attachment.filename.ifBlank { attachment.type.ifBlank { "file" } },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(120.dp),
            )
        }
    }
}

/**
 * Full list rendering used on the edit screen. Each tile is full-width and
 * tappable — tapping fires ACTION_VIEW so the user can open the file in an
 * external viewer. (External viewers can't reuse our auth header, so private
 * attachments may fail there; that's a memos-side ACL question, not ours.)
 */
@Composable
fun AttachmentList(
    attachments: List<AttachmentDto>,
    modifier: Modifier = Modifier,
    onRemove: ((AttachmentDto) -> Unit)? = null,
) {
    if (attachments.isEmpty()) return
    val authStore: AuthStore = koinInject()
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<Pair<String, String?>?>(null) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (att in attachments) {
            AttachmentRow(
                attachment = att,
                authStore = authStore,
                onOpen = { url ->
                    if (att.isImage()) {
                        viewing = url to att.filename.ifBlank { null }
                    } else {
                        openExternally(context, url, att.type)
                    }
                },
                onRemove = onRemove?.let { { it(att) } },
            )
        }
    }
    viewing?.let { (url, desc) ->
        ImageViewerDialog(url = url, contentDescription = desc, onDismiss = { viewing = null })
    }
}

@Composable
private fun AttachmentRow(
    attachment: AttachmentDto,
    authStore: AuthStore,
    onOpen: (String) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val url = attachment.urlOrNull(authStore)
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (url != null) Modifier.clickable { onOpen(url) } else Modifier)
        .padding(8.dp)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier) {
        if (attachment.isImage() && url != null) {
            AsyncImage(
                model = url,
                contentDescription = attachment.filename,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    attachment.typeIcon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) { AttachmentText(attachment) }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.edit_remove_attachment),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AttachmentText(attachment: AttachmentDto) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            attachment.filename.ifBlank { attachment.name },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = buildString {
            if (attachment.type.isNotBlank()) append(attachment.type)
            if (attachment.size > 0) {
                if (isNotEmpty()) append(" · ")
                append(formatBytes(attachment.size))
            }
        }
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun AttachmentDto.typeIcon(): ImageVector = when {
    isVideo() -> Icons.Filled.Movie
    isAudio() -> Icons.Filled.AudioFile
    type.equals("application/pdf", ignoreCase = true) -> Icons.Filled.PictureAsPdf
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun openExternally(context: Context, url: String, mimeType: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (mimeType.isNotBlank()) {
            setDataAndType(Uri.parse(url), mimeType)
        } else {
            data = Uri.parse(url)
        }
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.attachment_open_failed, Toast.LENGTH_SHORT).show()
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024)} MB"
    else -> "${bytes / (1024L * 1024 * 1024)} GB"
}
