package de.bookmarksplus.desktop.ui

import de.bookmarksplus.i18n.tr
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

data class AccentPreset(val key: String, val color: Color) {
    val name: String get() = tr(key)
}

val ACCENTS = listOf(
    AccentPreset("color.red", Color(0xFFE5484D)),
    AccentPreset("color.orange", Color(0xFFF76B15)),
    AccentPreset("color.amber", Color(0xFFFFB224)),
    AccentPreset("color.green", Color(0xFF30A46C)),
    AccentPreset("color.teal", Color(0xFF12A594)),
    AccentPreset("color.blue", Color(0xFF3E63DD)),
    AccentPreset("color.violet", Color(0xFF8E4EC6)),
    AccentPreset("color.pink", Color(0xFFD6409F)),
)

val DEFAULT_ACCENT = ACCENTS.first().color

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
        surface = Palette.Surface,
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
        titleMedium = base.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.2.sp),
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
        ),
        content = content,
    )
}

/** Slightly darkened variant of a tag color for backgrounds. */
fun Color.tint(alpha: Float = 0.18f): Color = copy(alpha = alpha).compositeOver(Palette.Surface)

val SectionLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = Palette.TextFaint)
