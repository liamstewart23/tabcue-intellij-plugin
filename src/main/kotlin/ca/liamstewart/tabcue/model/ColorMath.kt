package ca.liamstewart.tabcue.model

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import kotlin.math.roundToInt

/**
 * Turns an accent colour into the value `Content.setTabColor` has to be handed.
 *
 * The platform does not paint what you pass it. For an unselected tab
 * `JBDefaultTabPainter.getCustomBackground` composites the theme's
 * `inactiveColoredTabBackground` *over* our colour, and on dark themes that overlay is 60% opaque,
 * so only 40% of the colour survives. That is why tab colours looked washed out, and why the value
 * handed to the painter is pre-[compensate]d for the overlay.
 *
 * The target is also never the accent itself but the tab-strip background mixed part-way toward it.
 * `Content` exposes no way to change the label's foreground, so keeping the fill close to the
 * background is what lets the user pick any colour without making the label unreadable. The
 * full-strength accent goes to the dot icon and the background tint, neither of which sits behind
 * text.
 */
object ColorMath {

    /** How far the fill travels from the tab-strip background toward the accent. */
    const val FILL_WEIGHT: Double = 0.5

    /**
     * Past this overlay opacity, compensation is abandoned and the target is used unchanged.
     * Solving for the pre-overlay colour divides by `1 - alpha`, so near-opaque overlays push the
     * result outside the gamut on every channel and clamping makes it arbitrary.
     */
    private const val MAX_COMPENSABLE_ALPHA = 0.85

    /** `#RRGGBB`. */
    private const val CUSTOM_ID_LENGTH = 7

    /** Whether a persisted `colorId` is a literal colour rather than a palette id. */
    fun isCustom(colorId: String?): Boolean =
        colorId != null && colorId.length == CUSTOM_ID_LENGTH && colorId[0] == '#'

    /** The colour a custom `colorId` denotes, or null if it does not parse. */
    fun parseCustom(colorId: String?): Color? {
        if (!isCustom(colorId)) return null
        // fromHex throws on malformed input, and a hand-edited settings file must not take a
        // restyle down with it.
        return runCatching { ColorUtil.fromHex(colorId!!) }.getOrNull()
    }

    fun toColorId(color: Color): String = "#" + ColorUtil.toHex(color).uppercase()

    /**
     * What to hand `setTabColor` so the painted result approximates [fillTarget].
     *
     * Lazy rather than computed, because both inputs are theme-dependent: a fixed colour would
     * freeze the fill at whatever theme was in force when the tab was styled.
     */
    fun tabFill(accent: Color): JBColor {
        // Memoised on the theme values themselves, so a theme change invalidates it for free.
        // JBColor calls its supplier on every channel read and alphaBlending reads six, so without
        // this the mix and compensation ran half a dozen times per repaint.
        var key: Triple<Int, Int, Int>? = null
        var fill: Color? = null
        return JBColor.lazy {
            val background = tabStripBackground()
            val overlay = tabColorOverlay()
            // The accent is in the key too: a palette accent is itself a JBColor, so two themes
            // could share tab chrome and still resolve it differently.
            val current = Triple(background.rgb, overlay.rgb, accent.rgb)
            fill.takeIf { key == current }
                ?: compensate(ColorUtil.mix(background, accent, FILL_WEIGHT), overlay).also {
                    key = current
                    fill = it
                }
        }
    }

    /** The fill we want to see, before the platform's overlay is accounted for. */
    fun fillTarget(accent: Color): Color =
        ColorUtil.mix(tabStripBackground(), accent, FILL_WEIGHT)

    /**
     * Inverts `alphaBlending(overlay, result)` for [target]:
     * `source = (target - overlay*a) / (1-a)`, per channel, clamped to the gamut.
     */
    fun compensate(target: Color, overlay: Color): Color {
        val alpha = overlay.alpha / 255.0
        if (alpha <= 0.0 || alpha >= MAX_COMPENSABLE_ALPHA) return target
        fun channel(targetValue: Int, overlayValue: Int): Int =
            ((targetValue - overlayValue * alpha) / (1.0 - alpha)).roundToInt().coerceIn(0, 255)
        return Color(
            channel(target.red, overlay.red),
            channel(target.green, overlay.green),
            channel(target.blue, overlay.blue),
        )
    }

    private fun tabColorOverlay(): Color = JBUI.CurrentTheme.DefaultTabs.inactiveColoredTabBackground()

    private fun tabStripBackground(): Color = JBUI.CurrentTheme.ToolWindow.headerBackground()
}
