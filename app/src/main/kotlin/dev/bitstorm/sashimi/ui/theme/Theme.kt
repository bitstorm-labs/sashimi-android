package dev.bitstorm.sashimi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark-only color scheme built from the ported tokens. The iOS app is dark-only,
// so there is deliberately no light scheme.
private val SashimiColorScheme =
    darkColorScheme(
        primary = SashimiAccent,
        onPrimary = SashimiTextPrimary,
        secondary = SashimiLink,
        onSecondary = SashimiTextPrimary,
        tertiary = SashimiBadge,
        background = SashimiBackground,
        onBackground = SashimiTextPrimary,
        surface = SashimiCard,
        onSurface = SashimiTextPrimary,
        surfaceVariant = SashimiCard,
        onSurfaceVariant = SashimiTextSecondary,
        outline = SashimiTextTertiary,
        error = Color(0xFFCF6679),
    )

@Composable
fun SashimiTheme(
    // Parameter kept for API symmetry; the app forces dark regardless of the
    // system setting to match the iOS dark-only design.
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SashimiColorScheme,
        typography = Typography(),
        content = content,
    )
}
