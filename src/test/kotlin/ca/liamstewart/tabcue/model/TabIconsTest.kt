package ca.liamstewart.tabcue.model

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage

/**
 * Guards the icons this plugin draws itself.
 *
 * These are painted with raw `Graphics2D`, so a mistake shows up as an invisible icon rather than
 * an exception — the tab would just look unstyled. Rendering into a [BufferedImage] and counting
 * non-transparent pixels catches that headlessly.
 */
class TabIconsTest : BasePlatformTestCase() {

    private fun paintedPixels(icon: javax.swing.Icon): Int {
        val image = BufferedImage(
            (icon.iconWidth * 2).coerceAtLeast(1),
            (icon.iconHeight * 2).coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB,
        )
        val g = image.createGraphics()
        try {
            icon.paintIcon(null, g, 0, 0)
        } finally {
            g.dispose()
        }
        var painted = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) ushr 24) != 0) painted++
            }
        }
        return painted
    }

    fun testDotIconActuallyPaints() {
        val color = StylePalette.color("red")!!
        assertTrue("the colour dot must draw something", paintedPixels(DotIcon(color)) > 0)
    }

    fun testEmojiIconPaintsASupplementaryPlaneEmoji() {
        // U+1F680, a surrogate pair — the case most likely to render as nothing.
        assertTrue("🚀 must draw something", paintedPixels(EmojiIcon("🚀")) > 0)
    }

    fun testEmojiIconPaintsAZeroWidthJoinerSequence() {
        assertTrue("ZWJ sequences must draw", paintedPixels(EmojiIcon("👩‍💻")) > 0)
    }

    fun testEmojiIconHandlesPlainTextWithoutFailing() {
        assertTrue("plain text is allowed too", paintedPixels(EmojiIcon("dev")) > 0)
    }

    fun testIconsHaveANonZeroSize() {
        // A zero-size icon would be silently invisible in the tab strip.
        assertTrue(EmojiIcon("🚀").iconWidth > 0)
        assertTrue(EmojiIcon("🚀").iconHeight > 0)
        assertTrue(DotIcon(StylePalette.color("blue")!!).iconWidth > 0)
    }

    fun testIconsCompareByValueSoUnchangedRestylesFireNoEvents() {
        // ContentImpl.setIcon fires a property change only when the value differs, so equality has
        // to be structural — a fresh instance per restyle would otherwise relayout the tab strip.
        assertEquals(EmojiIcon("🚀"), EmojiIcon("🚀"))
        assertEquals(DotIcon(StylePalette.color("red")!!), DotIcon(StylePalette.color("red")!!))
        assertFalse(EmojiIcon("🚀") == EmojiIcon("🔥"))
    }

    fun testNormaliseEmojiCutsToOneGraphemeAndTrims() {
        // What is stored must equal what is drawn. EmojiIcon has always painted only the first
        // cluster, so before this the settings file could hold a whole pasted paragraph while the
        // tab showed a single character.
        assertEquals("🚀", normaliseEmoji("  🚀  "))
        assertEquals("a", normaliseEmoji("abc"))
        assertNull(normaliseEmoji("   "))
        assertNull(normaliseEmoji(""))
        assertNull(normaliseEmoji(null))
    }

    fun testNormaliseEmojiKeepsAMultiCodePointCluster() {
        // A flag is a pair of regional indicators and a ZWJ sequence is longer still; cutting by
        // `char` or by code point would split them into halves that render as garbage.
        assertEquals("🇨🇦", normaliseEmoji("🇨🇦"))
        assertEquals(1, normaliseEmoji("🇨🇦🇬🇧")!!.let { java.text.BreakIterator.getCharacterInstance().run {
            setText(it)
            var count = 0
            while (next() != java.text.BreakIterator.DONE) count++
            count
        } })
    }
}
