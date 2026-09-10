package ca.liamstewart.tabcue.model

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import kotlin.math.roundToInt

/**
 * Turns an accent colour into the value `Content.setTabColor` has to be handed.
 *
 * The tab fill is **not** the colour the platform paints. `JBDefaultTabPainter.getCustomBackground`
 * does, for an unselected tab:
 *
 * ```
 * ColorUtil.alphaBlending(theme.inactiveColoredTabBackground, ours)
 * ```
 *
 * and `alphaBlending(a, b)` composites `a` *over* `b` using **a's** alpha. So the theme paints an
 * overlay on top of our colour. On branch 253 the default
 * `JBUI.CurrentTheme.DefaultTabs.inactiveColoredTabBackground()` is black at 7% alpha for light
 * themes and `#3C3F41` at **60%** for dark ones — meaning a dark theme keeps only 40% of whatever
 * colour we set. That, not the choice of swatch, is why tab colours looked washed out.
 *
 * Two things follow, and they are why an arbitrary user-picked colour is safe here:
 *
 * 1. The value handed to the painter is [compensate]d for the overlay, so the *rendered* result
 *    lands on [fillTarget] whatever the current theme's overlay happens to be.
 * 2. [fillTarget] is never the accent itself. It is the tab-strip background mixed part-way toward
 *    the accent, so the label — whose foreground `Content` exposes no API to change — keeps its
 *    contrast no matter how saturated a colour the user picks. The full-strength accent goes to
 *    the dot icon and the background tint, neither of which sits behind text.
 */
object ColorMath {

    /**
     * How far the rendered tab fill travels from the tab-strip background toward the accent.
     *
     * The ceiling here is label legibility, not taste: at 1.0 the tab is the raw accent and dark
     * theme label text stops resolving against a saturated fill.
     */
    const val FILL_WEIGHT: Double = 0.5

    /**
     * Overlay opacity past which compensation is abandoned.
     *
     * Solving for the pre-overlay colour divides by `1 - alpha`, so as the overlay approaches
     * opaque the required source runs further outside the gamut on every channel and clamping
     * makes the result arbitrary. Handing the target through unchanged degrades to "muted but
     * correct in hue" instead.
     */
    private const val MAX_COMPENSABLE_ALPHA = 0.85

    /** `#RRGGBB`. */
    private const val CUSTOM_ID_LENGTH = 7

    /** Whether a persisted `colorId` is a literal colour rather than a palette id. */
    fun isCustom(colorId: String?): Boolean =
        colorId != null && colorId.length == CUSTOM_ID_LENGTH && colorId[0] == '#'

    /**
     * The colour a custom `colorId` denotes, or null if it does not parse.
     *
     * `ColorUtil.fromHex` is declared to return non-null and throws on malformed input, so a
     * hand-edited settings file must not be allowed to propagate an exception into a tab restyle.
     */
    fun parseCustom(colorId: String?): Color? {
        if (!isCustom(colorId)) return null
        return runCatching { ColorUtil.fromHex(colorId!!) }.getOrNull()
    }

    /** The persisted form of a picked colour. Alpha is dropped — see [FILL_WEIGHT]'s callers. */
    fun toColorId(color: Color): String = "#" + ColorUtil.toHex(color).uppercase()

    /**
     * What to hand `setTabColor` so the painted result approximates [fillTarget].
     *
     * A **lazy** [JBColor], not a computed `Color`. Both inputs — the tab-strip background and the
     * painter's overlay — are theme-dependent, so a fixed colour would freeze the fill at whatever
     * the theme was when the tab was styled. Before the fill was derived, `setTabColor` was handed
     * a plain `JBColor` from the palette and adapted to a theme switch for free; resolving through
     * a supplier keeps that property instead of making it depend on a listener firing. `BaseLabel`
     * stores the colour by reference and `ColorUtil.alphaBlending` reads its channels at paint
     * time, so the supplier runs on every repaint.
     */
    fun tabFill(accent: Color): JBColor {
        // Memoised on the theme inputs themselves, which makes it self-invalidating: when the
        // theme changes, so does the key. JBColor does *not* cache its supplier — it calls
        // `Supplier.get()` on every channel read, and `ColorUtil.alphaBlending` reads six — so
        // without this, every repaint of a coloured tab recomputed the mix and the compensation
        // half a dozen times and allocated a Color for each. Only ever read on the EDT during
        // paint; a torn read would merely cause one extra recompute.
        var key: Triple<Int, Int, Int>? = null
        var fill: Color? = null
        return JBColor.lazy {
            val background = tabStripBackground()
            val overlay = tabColorOverlay()
            // The accent's own RGB is part of the key because a palette accent is itself a JBColor:
            // two themes could share tab chrome and still resolve the accent differently.
            val current = Triple(background.rgb, overlay.rgb, accent.rgb)
            fill.takeIf { key == current }
                ?: compensate(ColorUtil.mix(background, accent, FILL_WEIGHT), overlay).also {
                    key = current
                    fill = it
                }
        }
    }

    /**
     * The fill we want to end up seeing, before the platform's overlay is accounted for.
     *
     * [tabFill] inlines this so it can reuse the theme values it already read for its cache key.
     */
    fun fillTarget(accent: Color): Color =
        ColorUtil.mix(tabStripBackground(), accent, FILL_WEIGHT)

    /**
     * Inverts `alphaBlending(overlay, result)` for [target].
     *
     * `target = overlay*a + source*(1-a)`, so `source = (target - overlay*a) / (1-a)`, per channel
     * and clamped to the gamut.
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

    /**
     * Read live on every resolve: both are theme-dependent, and [tabFill] uses them as its cache
     * key precisely so that a theme switch recomputes without needing a listener.
     */
    private fun tabColorOverlay(): Color = JBUI.CurrentTheme.DefaultTabs.inactiveColoredTabBackground()

    private fun tabStripBackground(): Color = JBUI.CurrentTheme.ToolWindow.headerBackground()
}
