package ca.liamstewart.tabcue.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import kotlin.math.roundToInt

/**
 * Pure colour arithmetic.
 *
 * [ColorMath.fillTarget] is not exercised here because it reads live theme colours.
 * [ColorMath.tabFill] is, but only for its *type*: it hands back an unresolved lazy colour, so no
 * theme is needed until something paints it.
 */
class ColorMathTest {

    /**
     * The platform's blend, reimplemented independently of [ColorMath.compensate] so the round-trip
     * assertions below are checked against the formula rather than against the code under test.
     * Mirrors `ColorUtil.alphaBlending(overlay, source)`: overlay composited over source, using the
     * overlay's alpha, with an opaque source.
     */
    private fun blend(overlay: Color, source: Color): Color {
        val alpha = overlay.alpha / 255.0
        fun channel(o: Int, s: Int) = (o * alpha + s * (1 - alpha)).roundToInt().coerceIn(0, 255)
        return Color(
            channel(overlay.red, source.red),
            channel(overlay.green, source.green),
            channel(overlay.blue, source.blue),
        )
    }

    private fun assertClose(expected: Color, actual: Color, tolerance: Int = 2) {
        val deltas = listOf(
            expected.red - actual.red,
            expected.green - actual.green,
            expected.blue - actual.blue,
        )
        assertTrue(
            "expected ~$expected but was $actual (deltas $deltas)",
            deltas.all { kotlin.math.abs(it) <= tolerance },
        )
    }

    @Test
    fun `compensating for the 253 dark overlay recovers the target once blended`() {
        // JBUI.CurrentTheme.DefaultTabs.inactiveColoredTabBackground(), dark default: #3C3F41 @ 60%.
        val overlay = Color(0x3C, 0x3F, 0x41, (0.6 * 255).roundToInt())
        val target = Color(0x7A, 0x4B, 0x4D)

        val source = ColorMath.compensate(target, overlay)

        assertClose(target, blend(overlay, source))
    }

    @Test
    fun `compensating for the 253 light overlay recovers the target once blended`() {
        // Light default: black @ 7%.
        val overlay = Color(0, 0, 0, (0.07 * 255).roundToInt())
        val target = Color(0xEA, 0x80, 0x80)

        val source = ColorMath.compensate(target, overlay)

        assertClose(target, blend(overlay, source))
    }

    @Test
    fun `compensation pushes the source away from the overlay`() {
        val overlay = Color(0x3C, 0x3F, 0x41, (0.6 * 255).roundToInt())
        val target = Color(0x7A, 0x4B, 0x4D)

        val source = ColorMath.compensate(target, overlay)

        // The whole point: to land on a mid-tone under a 60% grey wash, the colour handed to the
        // painter has to be considerably more saturated than the colour we want to see.
        assertTrue("source red $source should exceed target red", source.red > target.red)
    }

    @Test
    fun `a fully transparent overlay needs no compensation`() {
        val target = Color(0x12, 0x34, 0x56)

        assertEquals(target, ColorMath.compensate(target, Color(0, 0, 0, 0)))
    }

    @Test
    fun `an almost opaque overlay gives up rather than clamping to an arbitrary colour`() {
        // Above MAX_COMPENSABLE_ALPHA the source contributes so little that solving for it lands
        // far outside the gamut on every channel, so the target is handed through unchanged.
        val overlay = Color(0x3C, 0x3F, 0x41, (0.95 * 255).roundToInt())
        val target = Color(0xE0, 0x52, 0x52)

        assertEquals(target, ColorMath.compensate(target, overlay))
    }

    @Test
    fun `compensation stays inside the gamut for unreachable targets`() {
        val overlay = Color(0xFF, 0xFF, 0xFF, (0.8 * 255).roundToInt())
        // Pure black is unreachable under a heavy white wash; clamping must still yield a colour.
        val source = ColorMath.compensate(Color.BLACK, overlay)

        listOf(source.red, source.green, source.blue).forEach {
            assertTrue("channel $it out of range", it in 0..255)
        }
    }

    @Test
    fun `custom ids are recognised and palette ids are not`() {
        assertTrue(ColorMath.isCustom("#FF0000"))
        assertFalse(ColorMath.isCustom("red"))
        assertFalse(ColorMath.isCustom(null))
        // Wrong length, so an id that merely starts with '#' is not mistaken for a colour.
        assertFalse(ColorMath.isCustom("#FF00"))
        assertFalse(ColorMath.isCustom("#FF000000"))
    }

    @Test
    fun `a picked colour round-trips through its persisted id`() {
        val color = Color(0x3B, 0x7D, 0xD8)

        val id = ColorMath.toColorId(color)

        assertEquals("#3B7DD8", id)
        assertEquals(color, ColorMath.parseCustom(id))
    }

    @Test
    fun `a malformed custom id resolves to null rather than throwing`() {
        // ColorUtil.fromHex is declared non-null and throws on bad input; a hand-edited settings
        // file must not be able to propagate that into a tab restyle.
        assertNull(ColorMath.parseCustom("#ZZZZZZ"))
        assertNull(ColorMath.parseCustom("red"))
        assertNull(ColorMath.parseCustom(null))
    }

    @Test
    fun `the palette resolves both a preset id and a custom literal`() {
        assertEquals(Color(0x3B, 0x7D, 0xD8), StylePalette.color("#3B7DD8"))
        assertNull(StylePalette.color("no-such-swatch"))
        assertNull(StylePalette.color(null))
    }

    @Test
    fun `a custom colour names itself in the menu`() {
        assertEquals("#3B7DD8", StylePalette.colorName("#3b7dd8"))
        assertEquals("Red", StylePalette.colorName("red"))
    }

    @Test
    fun `the tab fill resolves lazily so it follows a theme switch`() {
        // Load-bearing, not incidental. Both inputs to the fill are theme-dependent, so a computed
        // Color would freeze it at the theme in force when the tab was styled. Before the fill was
        // derived, setTabColor received a JBColor straight from the palette and re-resolved on
        // every repaint for free; returning a JBColor keeps that instead of making correctness
        // depend on a theme-change listener firing.
        val fill: Any = ColorMath.tabFill(Color(0x3B, 0x7D, 0xD8))

        assertTrue(
            "tabFill must return a lazily resolving JBColor, was ${fill.javaClass.name}",
            fill is com.intellij.ui.JBColor,
        )
    }
}
