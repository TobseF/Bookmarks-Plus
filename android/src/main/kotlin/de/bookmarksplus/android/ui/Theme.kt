package de.bookmarksplus.android.ui

import de.bookmarksplus.android.R
import de.bookmarksplus.android.str

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors as in the desktop app (dark theme, adjustable accent color, red as default). */
object Palette {
    val Background = Color(0xFF0E0E11)
    val Sidebar = Color(0xFF131317)
    val Surface = Color(0xFF17171C)
    val SurfaceHigh = Color(0xFF1E1E25)
    val SurfaceHighest = Color(0xFF26262E)
    val Outline = Color(0xFF2C2C35)
    val OutlineSoft = Color(0xFF222229)
    val Text = Color(0xFFE9E9EE)
    val TextMuted = Color(0xFF9B9BA8)
    val TextFaint = Color(0xFF6B6B78)
    val Star = Color(0xFFF5C451)
}

data class AccentPreset(@androidx.annotation.StringRes val nameRes: Int, val color: Color) {
    val name: String get() = str(nameRes)
}

val ACCENTS = listOf(
    AccentPreset(R.string.color_red, Color(0xFFE5484D)),
    AccentPreset(R.string.color_orange, Color(0xFFF76B15)),
    AccentPreset(R.string.color_amber, Color(0xFFFFB224)),
    AccentPreset(R.string.color_green, Color(0xFF30A46C)),
    AccentPreset(R.string.color_teal, Color(0xFF12A594)),
    AccentPreset(R.string.color_blue, Color(0xFF3E63DD)),
    AccentPreset(R.string.color_violet, Color(0xFF8E4EC6)),
    AccentPreset(R.string.color_pink, Color(0xFFD6409F)),
)

@Composable
fun AppTheme(accent: Color, content: @Composable () -> Unit) {
    val container = accent.copy(alpha = 0.18f).compositeOver(Palette.Surface)
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = container,
        onPrimaryContainer = lerp(accent, Color.White, 0.55f),
        secondary = accent,
        onSecondary = Color.White,
        secondaryContainer = container,
        onSecondaryContainer = lerp(accent, Color.White, 0.55f),
        tertiary = accent,
        background = Palette.Background,
        onBackground = Palette.Text,
        surface = Palette.Background,
        onSurface = Palette.Text,
        surfaceVariant = Palette.SurfaceHigh,
        onSurfaceVariant = Palette.TextMuted,
        surfaceContainerLowest = Palette.Background,
        surfaceContainerLow = Palette.Sidebar,
        surfaceContainer = Palette.Surface,
        surfaceContainerHigh = Palette.SurfaceHigh,
        surfaceContainerHighest = Palette.SurfaceHighest,
        outline = Palette.Outline,
        outlineVariant = Palette.OutlineSoft,
        inverseSurface = Palette.Text,
        inverseOnSurface = Palette.Background,
    )
    val base = Typography()
    val typography = base.copy(
        titleLarge = base.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
