package dev.windock

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import dev.windock.mvi.DockTheme

internal data class BarInk(
    val content: Color, val muted: Color, val accent: Color, val border: Color,
    val hover: Color = accent, val traySurface: Color = Color.Transparent,
)

internal fun contrastRatio(a: Color, b: Color): Float {
    val first = a.luminance()
    val second = b.luminance()
    return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
}

/** Check the gradient between its stops as well as its endpoints, especially for random palettes. */
internal fun gradientSamples(palette: List<Color>): List<Color> =
    palette.zipWithNext().flatMap { (left, right) -> (0..8).map { lerp(left, right, it / 8f) } }

internal fun barInk(theme: DockTheme, palette: List<Color>): BarInk {
    val samples = gradientSamples(palette)
    fun score(color: Color) = samples.minOf { contrastRatio(color, it) }
    val content = listOf(Color(0xFFF7F8FC), Color(0xFF161A22)).maxBy(::score)
    val lightText = content.luminance() > .5f
    val hover = when (theme) {
        DockTheme.HALLOWEEN_GHOST, DockTheme.HALLOWEEN_ZOMBIES, DockTheme.HALLOWEEN_TRICK_OR_TREAT -> Color(0xFFA675E0)
        DockTheme.CHRISTMAS, DockTheme.CHRISTMAS_WORKSHOP, DockTheme.CHRISTMAS_COZY -> Color(0xFFE6B94F)
        DockTheme.AUTUMN -> Color(0xFFB36A20)
        else -> Blue
    }
    // Indicators sit directly on the bar; lift/lower their luminance without losing the theme hue.
    val accent = (0..10).map { lerp(hover, content, it / 10f) }
        .firstOrNull { score(it) >= 3f } ?: content
    fun readable(color: Color, surface: Color) = samples.all { background ->
        val base = surface.compositeOver(background)
        contrastRatio(color, base) >= 4.5f &&
            contrastRatio(color, hover.copy(alpha = .14f).compositeOver(base)) >= 4.5f
    }
    val surface = if (!readable(content, Color.Transparent))
        (if (lightText) Color(0xFF161A22) else Color.White).copy(alpha = .96f) else Color.Transparent
    val mutedCandidate = if (lightText) Color(0xFFCCD1DC) else Color(0xFF454C59)
    val muted = if (readable(mutedCandidate, surface))
        mutedCandidate else content
    return BarInk(content, muted, accent, Color.White.copy(alpha = if (lightText) .28f else .92f), hover, surface)
}
