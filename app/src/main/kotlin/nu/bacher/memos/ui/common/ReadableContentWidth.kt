package nu.bacher.memos.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A comfortable maximum line length for text-heavy, single-column content.
 * Past roughly this width prose and form fields stop being scannable.
 */
val ReadableContentMaxWidth: Dp = 720.dp

/**
 * Caps an element's width to [maxWidth] and centers it horizontally within the
 * space its parent offers. On phones this is a no-op (the screen is narrower
 * than the cap); on tablets, foldables, and resizable desktop/free-form windows
 * it keeps single-column content from stretching into unreadably long lines.
 *
 * Needed now that we target API level 37 (Android 17), which removes the
 * large-screen orientation/resizability opt-out — the app must look right at
 * any width. Multi-column surfaces (e.g. the memo grid) intentionally don't use
 * this: they should gain columns with width rather than stay narrow and center.
 */
fun Modifier.readableContentWidth(maxWidth: Dp = ReadableContentMaxWidth): Modifier =
    this
        .fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = maxWidth)
        .fillMaxWidth()
