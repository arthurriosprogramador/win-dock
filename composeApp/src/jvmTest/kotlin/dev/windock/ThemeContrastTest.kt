package dev.windock

import androidx.compose.ui.graphics.compositeOver
import dev.windock.mvi.DockTheme
import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeContrastTest {
    @Test fun textRemainsReadableAcrossOpaqueThemeGradients() {
        DockTheme.entries.filterNot { it.isCapsule() }.forEach { theme ->
            repeat(if (theme == DockTheme.RANDOM) 4 else 1) { index ->
                val palette = themePalette(theme, index)
                val ink = barInk(theme, palette)
                gradientSamples(palette).forEach { background ->
                    val surface = ink.traySurface.compositeOver(background)
                    assertTrue(contrastRatio(ink.content, surface) >= 4.5f, "$theme content")
                    assertTrue(contrastRatio(ink.muted, surface) >= 4.5f, "$theme muted")
                    val hovered = ink.hover.copy(alpha = .14f).compositeOver(surface)
                    assertTrue(contrastRatio(ink.content, hovered) >= 4.5f, "$theme hovered content")
                    assertTrue(contrastRatio(ink.muted, hovered) >= 4.5f, "$theme hovered muted")
                    assertTrue(contrastRatio(ink.accent, background) >= 3f, "$theme indicator")
                }
            }
        }
    }
}
