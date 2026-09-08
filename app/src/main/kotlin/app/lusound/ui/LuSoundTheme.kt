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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.convx.music.ui.component.shapes.ContinuousRoundedRectangle
import com.convx.music.ui.utils.rememberIosOverscrollFactory

/** Convx AppleTokens/Type.kt type scale, continuous shapes and theme-adaptive default surfaces (GPL-3.0). */
@Composable
fun LuSoundTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFFF526B), onPrimary = Color.White,
        primaryContainer = Color(0xFF4D2630), onPrimaryContainer = Color(0xFFFFD9DF),
        secondary = Color(0xFFC7C7CC), onSecondary = Color(0xFF1C1C1E),
        secondaryContainer = Color(0xFF3A3A3C), onSecondaryContainer = Color(0xFFF2F2F7),
        tertiary = Color(0xFFC7C7CC), tertiaryContainer = Color(0xFF3A3A3C),
        background = Color(0xFF121212), onBackground = Color(0xFFF2F2F7),
        surface = Color(0xFF1C1C1E), onSurface = Color(0xFFF2F2F7),
        surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFFAEAEB2),
        surfaceContainerLowest = Color(0xFF121212), surfaceContainerLow = Color(0xFF1A1A1A),
        surfaceContainer = Color(0xFF242426), surfaceContainerHigh = Color(0xFF2C2C2E),
        surfaceContainerHighest = Color(0xFF3A3A3C), outline = Color(0xFF8E8E93), outlineVariant = Color(0xFF3A3A3C),
    ) else lightColorScheme(
        primary = Color(0xFFB71B39), onPrimary = Color.White,
        primaryContainer = Color(0xFFFFD9DF), onPrimaryContainer = Color(0xFF51121F),
        secondary = Color(0xFF636366), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE5E5EA), onSecondaryContainer = Color(0xFF51121F),
        tertiary = Color(0xFF636366), tertiaryContainer = Color(0xFFE5E5EA),
        background = Color(0xFFF2F2F7), onBackground = Color(0xFF1C1C1E),
        surface = Color.White, onSurface = Color(0xFF1C1C1E),
        surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF636366),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F2F7),
        surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFE5E5EA),
        surfaceContainerHighest = Color(0xFFD1D1D6), outline = Color(0xFF8E8E93), outlineVariant = Color(0xFFD1D1D6),
    )
    CompositionLocalProvider(LocalOverscrollFactory provides rememberIosOverscrollFactory()) {
    MaterialTheme(colorScheme = colors, typography = Typography(
        headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    ), shapes = Shapes(
        extraSmall = ContinuousRoundedRectangle(10.dp), small = ContinuousRoundedRectangle(16.dp),
        medium = ContinuousRoundedRectangle(22.dp), large = ContinuousRoundedRectangle(28.dp), extraLarge = ContinuousRoundedRectangle(32.dp),
    ), content = content)
    }
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
