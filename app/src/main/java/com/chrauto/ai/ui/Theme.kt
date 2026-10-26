package com.chrauto.ai.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chrauto.ai.overlay.MascotPainter
import com.chrauto.ai.overlay.Mood
import kotlin.math.min

/** Semantic colours. Every foreground/soft pair keeps at least 4.5:1 contrast in its theme. */
@Immutable
data class Palette(
    val accent: Color, val accentSoft: Color, val ink: Color, val muted: Color,
    val success: Color, val successSoft: Color, val danger: Color, val dangerSoft: Color,
    val warn: Color, val warnSoft: Color, val canvas: Color, val card: Color, val chip: Color, val line: Color
)

val LightPalette = Palette(
    accent = Color(0xFF2F5BD8), accentSoft = Color(0xFFE6ECFB), ink = Color(0xFF0F172A), muted = Color(0xFF526077),
    success = Color(0xFF167A4A), successSoft = Color(0xFFE2F4EA), danger = Color(0xFFC0262D), dangerSoft = Color(0xFFFBE7E8),
    warn = Color(0xFF8A5A00), warnSoft = Color(0xFFFBF0D9), canvas = Color(0xFFF5F7FB), card = Color(0xFFFFFFFF),
    chip = Color(0xFFEEF1F6), line = Color(0xFFDDE2EA)
)

val DarkPalette = Palette(
    accent = Color(0xFF8CAAFF), accentSoft = Color(0xFF1C2742), ink = Color(0xFFE6EAF2), muted = Color(0xFF9AA5B8),
    success = Color(0xFF5FD39A), successSoft = Color(0xFF13291F), danger = Color(0xFFFF8A8F), dangerSoft = Color(0xFF34181B),
    warn = Color(0xFFF2C063), warnSoft = Color(0xFF2E2412), canvas = Color(0xFF0B1220), card = Color(0xFF131B2C),
    chip = Color(0xFF1B2436), line = Color(0xFF263049)
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Theme-aware brand colours (read from the current [Palette]). */
object Brand {
    val Accent: Color @Composable get() = LocalPalette.current.accent
    val AccentSoft: Color @Composable get() = LocalPalette.current.accentSoft
    val Ink: Color @Composable get() = LocalPalette.current.ink
    val Muted: Color @Composable get() = LocalPalette.current.muted
    val Success: Color @Composable get() = LocalPalette.current.success
    val SuccessSoft: Color @Composable get() = LocalPalette.current.successSoft
    val Danger: Color @Composable get() = LocalPalette.current.danger
    val DangerSoft: Color @Composable get() = LocalPalette.current.dangerSoft
    val Warn: Color @Composable get() = LocalPalette.current.warn
    val WarnSoft: Color @Composable get() = LocalPalette.current.warnSoft
    val Canvas: Color @Composable get() = LocalPalette.current.canvas
    val Card: Color @Composable get() = LocalPalette.current.card
    val Chip: Color @Composable get() = LocalPalette.current.chip
    val Line: Color @Composable get() = LocalPalette.current.line
}

private fun scheme(p: Palette, dark: Boolean) = if (dark) darkColorScheme(
    primary = p.accent, onPrimary = Color(0xFF0B1220), primaryContainer = p.accentSoft, onPrimaryContainer = p.ink,
    secondary = p.accent, onSecondary = Color(0xFF0B1220), secondaryContainer = p.chip, onSecondaryContainer = p.ink,
    background = p.canvas, onBackground = p.ink, surface = p.card, onSurface = p.ink,
    surfaceVariant = p.chip, onSurfaceVariant = p.muted, outline = p.line, outlineVariant = p.line,
    error = p.danger, errorContainer = p.dangerSoft, surfaceContainer = p.card, surfaceContainerHigh = p.chip,
    surfaceContainerLow = p.card, surfaceContainerHighest = p.chip
) else lightColorScheme(
    primary = p.accent, onPrimary = Color.White, primaryContainer = p.accentSoft, onPrimaryContainer = p.ink,
    secondary = p.accent, onSecondary = Color.White, secondaryContainer = p.chip, onSecondaryContainer = p.ink,
    background = p.canvas, onBackground = p.ink, surface = p.card, onSurface = p.ink,
    surfaceVariant = p.chip, onSurfaceVariant = p.muted, outline = p.line, outlineVariant = p.line,
    error = p.danger, errorContainer = p.dangerSoft, surfaceContainer = p.card, surfaceContainerHigh = p.chip,
    surfaceContainerLow = p.card, surfaceContainerHighest = p.chip
)

private val type = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    )
}

@Composable
fun ChrautoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme(p, dark), typography = type, content = content)
    }
}

/** The Chrauto agent mark, shared with the floating assistant. */
@Composable
fun Mascot(mood: Mood, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val clock by rememberInfiniteTransition(label = "mark").animateFloat(
        initialValue = 0f, targetValue = 600f,
        animationSpec = infiniteRepeatable(tween(600_000, easing = LinearEasing)), label = "clock"
    )
    Canvas(modifier.size(size)) {
        val r = min(this.size.width, this.size.height) * 0.4f
        drawIntoCanvas { MascotPainter.draw(it.nativeCanvas, center.x, center.y + r * 0.08f, r, mood, clock, 0f) }
    }
}
