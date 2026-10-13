package dev.gmitch215.drift.studio.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioThemeTest {
    private fun channel(v: Float) =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    private fun luminance(c: Color) =
        0.2126f * channel(c.red) + 0.7152f * channel(c.green) + 0.0722f * channel(c.blue)

    private fun ratio(a: Color, b: Color): Float {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun theRatioMatchesTheWcagDefinition() {
        assertEquals(21f, ratio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, ratio(Color.White, Color.White), 0.001f)
    }

    @Test
    fun textMeetsAaOnEveryBackgroundInBothThemes() {
        for ((name, palette) in listOf("light" to LightPalette, "dark" to DarkPalette)) {
            for ((fg, bg) in palette.textPairs) {
                val r = ratio(fg, bg)
                assertTrue(r >= 4.5f, "$name text pair $fg on $bg is $r")
            }
        }
    }

    @Test
    fun marksAndControlEdgesMeetThreeToOneInBothThemes() {
        for ((name, palette) in listOf("light" to LightPalette, "dark" to DarkPalette)) {
            for ((fg, bg) in palette.graphicPairs) {
                val r = ratio(fg, bg)
                assertTrue(r >= 3f, "$name graphic pair $fg on $bg is $r")
            }
        }
    }
}
