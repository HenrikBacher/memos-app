package nu.bacher.memos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Memo card swatches. Each entry has a light variant (pastel, used in light
 * theme) and a darker tinted variant for dark theme — the swatches are picked
 * the same way regardless of theme, so a memo keeps its identity across modes.
 *
 * Text colour is handled separately by [memoOnCardColor] so contrast always
 * works.
 */
data class MemoSwatch(val light: Color, val dark: Color)

val MemoSwatches = listOf(
    MemoSwatch(Color(0xFFFFF59D), Color(0xFF6B5A1A)), // yellow
    MemoSwatch(Color(0xFFCCFF90), Color(0xFF3B5B1F)), // green
    MemoSwatch(Color(0xFFB3E5FC), Color(0xFF1F4B5F)), // blue
    MemoSwatch(Color(0xFFF8BBD0), Color(0xFF5B2A3D)), // pink
    MemoSwatch(Color(0xFFFFD180), Color(0xFF6B4D1A)), // orange
    MemoSwatch(Color(0xFFE1BEE7), Color(0xFF4D2F55)), // purple
    MemoSwatch(Color(0xFFE0E0E0), Color(0xFF3D3D3D)), // neutral
)

@Composable
fun memoCardColor(key: Any): Color {
    val swatch = MemoSwatches[key.hashCode().mod(MemoSwatches.size)]
    return if (isSystemInDarkTheme()) swatch.dark else swatch.light
}

@Composable
fun memoOnCardColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFF1F1F1) else Color(0xFF1A1A1A)
