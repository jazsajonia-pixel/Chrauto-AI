package com.chrauto.ai.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

object Brand {
    val Violet = Color(0xFF6C4CF1)
    val VioletSoft = Color(0xFFEDE8FF)
    val Pink = Color(0xFFFF7EB6)
    val PinkSoft = Color(0xFFFFE6F1)
    val Ink = Color(0xFF241B4F)
    val Muted = Color(0xFF6E6889)
    val Mint = Color(0xFF1FB57A)
    val MintSoft = Color(0xFFDDF7EC)
    val Coral = Color(0xFFE5484D)
    val CoralSoft = Color(0xFFFFE7E7)
    val Amber = Color(0xFFB7791F)
    val AmberSoft = Color(0xFFFFF3D6)
    val Canvas = Color(0xFFFBF8FF)
}

private val colors = lightColorScheme(
    primary = Brand.Violet, onPrimary = Color.White, primaryContainer = Brand.VioletSoft, onPrimaryContainer = Brand.Ink,
    secondary = Brand.Pink, onSecondary = Color.White, secondaryContainer = Brand.PinkSoft, onSecondaryContainer = Brand.Ink,
    background = Brand.Canvas, onBackground = Brand.Ink, surface = Color.White, onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFF3F0FA), onSurfaceVariant = Brand.Muted, outline = Color(0xFFD9D3EE),
    outlineVariant = Color(0xFFE9E4F5), error = Brand.Coral, errorContainer = Brand.CoralSoft
)

private val type = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    )
}

@Composable
fun ChrautoTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = type, content = content)

/** Animated mascot shared with the floating assistant. */
@Composable
fun Mascot(mood: Mood, size: Dp = 64.dp, modifier: Modifier = Modifier) {
    val clock by rememberInfiniteTransition(label = "mascot").animateFloat(
        initialValue = 0f, targetValue = 600f,
        animationSpec = infiniteRepeatable(tween(600_000, easing = LinearEasing)), label = "clock"
    )
    Canvas(modifier.size(size)) {
        val phase = clock % 3.6f
        val blink = when { phase < 0.14f -> phase / 0.14f; phase < 0.28f -> (0.28f - phase) / 0.14f; else -> 0f }
        val r = min(this.size.width, this.size.height) * 0.36f
        val bob = r * 0.06f * kotlin.math.sin(clock * 2.4f)
        drawIntoCanvas { MascotPainter.draw(it.nativeCanvas, center.x, center.y + r * 0.08f + bob, r, mood, clock, blink) }
    }
}
