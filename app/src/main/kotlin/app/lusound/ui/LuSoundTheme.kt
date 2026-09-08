package app.lusound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Neutral, theme-adaptive surfaces keep every screen in the same Convx-inspired visual family. */
@Composable
fun LuSoundTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFE4EDEB), onPrimary = Color(0xFF172320),
        primaryContainer = Color(0xFF344842), onPrimaryContainer = Color(0xFFDCEEE7),
        secondary = Color(0xFFB6CCC4), onSecondary = Color(0xFF172320),
        secondaryContainer = Color(0xFF35453F), onSecondaryContainer = Color(0xFFE4EDEB),
        tertiary = Color(0xFFB6CCC4), tertiaryContainer = Color(0xFF35453F),
        background = Color(0xFF101313), onBackground = Color(0xFFF2F4F3),
        surface = Color(0xFF1B201F), onSurface = Color(0xFFF2F4F3),
        surfaceVariant = Color(0xFF303936), onSurfaceVariant = Color(0xFFB9C3BF),
        surfaceContainerLowest = Color(0xFF101313), surfaceContainerLow = Color(0xFF181D1B),
        surfaceContainer = Color(0xFF252C29), surfaceContainerHigh = Color(0xFF303835),
        surfaceContainerHighest = Color(0xFF3C4541), outline = Color(0xFF73817B), outlineVariant = Color(0xFF3C4943),
    ) else lightColorScheme(
        primary = Color(0xFF2E5145), onPrimary = Color.White,
        primaryContainer = Color(0xFFD5E6DE), onPrimaryContainer = Color(0xFF233C32),
        secondary = Color(0xFF496459), onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCE7E1), onSecondaryContainer = Color(0xFF233C32),
        tertiary = Color(0xFF496459), tertiaryContainer = Color(0xFFDCE7E1),
        background = Color(0xFFF1F4F1), onBackground = Color(0xFF1A211D),
        surface = Color(0xFFFAFCF9), onSurface = Color(0xFF1A211D),
        surfaceVariant = Color(0xFFE2E9E3), onSurfaceVariant = Color(0xFF526259),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F9F5),
        surfaceContainer = Color(0xFFE8EEE8), surfaceContainerHigh = Color(0xFFE1E9E2),
        surfaceContainerHighest = Color(0xFFD6E0D8), outline = Color(0xFF7A8C80), outlineVariant = Color(0xFFD0DBD2),
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(22.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp),
    ), content = content)
}

/** Shared artwork atmosphere; the backdrop renderer samples this together with scrolling content. */
@Composable
fun MusicAtmosphere(artwork: String?) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Artwork(artwork, Modifier.fillMaxSize().blur(96.dp))
        Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = if (isSystemInDarkTheme()) 0.76f else 0.9f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, colors.background.copy(alpha = 0.5f), Color.Transparent))))
    }
}
