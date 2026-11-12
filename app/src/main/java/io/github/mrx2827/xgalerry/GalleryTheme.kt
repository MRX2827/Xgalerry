// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mrx2827.xgalerry

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

internal enum class ThemeMode(val title: Int) {
    SYSTEM(R.string.theme_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF315739), onPrimary = Color.White,
    primaryContainer = Color(0xFFCBEDC7), onPrimaryContainer = Color(0xFF112F1A),
    secondary = Color(0xFF53664E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEBD6), onSecondaryContainer = Color(0xFF21341E),
    tertiary = Color(0xFF316572), tertiaryContainer = Color(0xFFBCECF8),
    background = Color(0xFFF5F8F0), onBackground = Color(0xFF171E17),
    surface = Color(0xFFF5F8F0), onSurface = Color(0xFF171E17),
    onSurfaceVariant = Color(0xFF424D40), outline = Color(0xFF697666),
    outlineVariant = Color(0xFFC4D0BF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEEF4EB),
    surfaceContainer = Color(0xFFE7EFE4), surfaceContainerHigh = Color(0xFFE0EBDF),
    surfaceContainerHighest = Color(0xFFD9E5D8)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAED2AD), onPrimary = Color(0xFF163721),
    primaryContainer = Color(0xFF2E4C34), onPrimaryContainer = Color(0xFFCBEDC7),
    secondary = Color(0xFFBED1B7), onSecondary = Color(0xFF293D27),
    secondaryContainer = Color(0xFF394837), onSecondaryContainer = Color(0xFFDCEBD6),
    tertiary = Color(0xFFA0D0DE), tertiaryContainer = Color(0xFF254F5A),
    background = Color(0xFF111612), onBackground = Color(0xFFE2EAE0),
    surface = Color(0xFF111612), onSurface = Color(0xFFE2EAE0),
    onSurfaceVariant = Color(0xFFBECABA), outline = Color(0xFF899687),
    outlineVariant = Color(0xFF414D41),
    surfaceContainerLowest = Color(0xFF0B100C), surfaceContainerLow = Color(0xFF171F19),
    surfaceContainer = Color(0xFF1E2720), surfaceContainerHigh = Color(0xFF283129),
    surfaceContainerHighest = Color(0xFF323D33)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GalleryTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable (Boolean) -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = remember(context, dark, dynamic) {
        when {
            dynamic && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
            dynamic && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
            dark -> DarkColors
            else -> LightColors
        }
    }
    MaterialExpressiveTheme(colorScheme = colors) { content(dark) }
}
