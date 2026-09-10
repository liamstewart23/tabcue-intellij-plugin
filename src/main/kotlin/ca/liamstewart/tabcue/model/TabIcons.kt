package ca.liamstewart.tabcue.model

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.scale.JBUIScale
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.UIManager

/**
 * Icons this plugin draws itself.
 *
 * These matter more than they first appear. `Content.setTabColor` only survives on an *unselected,
 * non-hovered* tab: `JBDefaultTabPainter.getCustomBackground` replaces the custom colour with
 * `theme.underlinedTabBackground` when the tab is selected, and alpha-blends the theme's hover
 * colour over it when hovered. `BaseLabel` paints the icon independently of the tab background, so
 * an icon is the only per-tab cue that holds in every state.
 */

/** A filled circle in the tab's colour — the colour cue that survives selection and hover. */
class DotIcon(private val color: java.awt.Color, private val size: Int = 10) : Icon {

    override fun getIconWidth(): Int = JBUIScale.scale(size)

    override fun getIconHeight(): Int = JBUIScale.scale(size)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, iconWidth, iconHeight)
        } finally {
            g2.dispose()
        }
    }

    override fun equals(other: Any?): Boolean = other is DotIcon && other.color == color && other.size == size

    override fun hashCode(): Int = 31 * color.hashCode() + size
}

/**
 * Renders an emoji (or any short string) as a tab icon.
 *
 * The platform has no emoji icon type, so this draws the text directly. On macOS the glyphs come
 * from Apple Color Emoji, which the JetBrains Runtime renders in colour through `drawString`.
 */
class EmojiIcon(text: String, private val size: Int = 14) : Icon {

    /**
     * Trimmed to a single grapheme cluster.
     *
     * `paintIcon` draws with `drawString`, and Swing clips to the enclosing *label*, not to the
     * icon's declared bounds — so pasting a long string into the emoji field would paint straight
     * across the tab title and into the neighbouring tabs. One cluster, not one `char`, so a
     * flag or a ZWJ sequence survives intact.
     */
    private val text: String = normaliseEmoji(text).orEmpty()

    override fun getIconWidth(): Int = JBUIScale.scale(size + 2)

    override fun getIconHeight(): Int = JBUIScale.scale(size + 2)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // Defensive: Swing clips to the label, not to us, so a mis-measured glyph would
            // otherwise bleed into the adjacent tab.
            g2.clipRect(x, y, iconWidth, iconHeight)
            g2.font = fontFor(JBUIScale.scale(size), text)
            val metrics = g2.fontMetrics
            val textWidth = metrics.stringWidth(text)
            val drawX = x + (iconWidth - textWidth) / 2
            val drawY = y + (iconHeight - metrics.height) / 2 + metrics.ascent
            g2.drawString(text, drawX, drawY)
        } finally {
            g2.dispose()
        }
    }

    override fun equals(other: Any?): Boolean = other is EmojiIcon && other.text == text && other.size == size

    override fun hashCode(): Int = 31 * text.hashCode() + size

    private companion object {
        private val cache = java.util.concurrent.ConcurrentHashMap<Int, Font>()



        /**
         * Prefers Apple Color Emoji, but verifies it can actually render the glyph.
         *
         * `Font(name, ...)` silently substitutes a default family when the name is unknown, so an
         * unchecked font would render tofu with no diagnostic. The UI font is a better fallback
         * because AWT font substitution will still find an emoji face for it.
         */
        fun fontFor(pixels: Int, text: String): Font {
            val uiFont = (UIManager.getFont("Label.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, pixels))
                .deriveFont(pixels.toFloat())
            if (!SystemInfo.isMac) return uiFont
            val emojiFont = cache.computeIfAbsent(pixels) { px -> Font("Apple Color Emoji", Font.PLAIN, px) }
            return if (emojiFont.canDisplayUpTo(text) == -1) emojiFont else uiFont
        }
    }
}

/**
 * The canonical form of a user-entered emoji: trimmed, and cut to a single grapheme cluster.
 *
 * Applied on the way *in* as well as at paint time. [EmojiIcon] has always drawn only the first
 * cluster, so without this the settings file could hold a whole pasted paragraph while the tab
 * showed one character — an unbounded string persisted per tab, and a stored value that did not
 * match what the user could see. One cluster rather than one `char`, so a flag or a ZWJ sequence
 * survives intact.
 */
internal fun normaliseEmoji(text: String?): String? {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val iterator = java.text.BreakIterator.getCharacterInstance()
    iterator.setText(trimmed)
    val end = iterator.next()
    return if (end == java.text.BreakIterator.DONE) trimmed else trimmed.substring(0, end)
}
