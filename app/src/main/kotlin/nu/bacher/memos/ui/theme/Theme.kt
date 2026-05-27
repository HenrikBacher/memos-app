package nu.bacher.memos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import nu.bacher.memos.data.settings.ThemeMode
import nu.bacher.memos.data.settings.ThemeSettings

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun MemosTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val darkTheme = when (settings.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (settings.dynamicColor) {
        // minSdk = 34 (Android 14), so dynamic color is always available — no
        // SDK_INT guard needed.
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) DarkColors else LightColors
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
