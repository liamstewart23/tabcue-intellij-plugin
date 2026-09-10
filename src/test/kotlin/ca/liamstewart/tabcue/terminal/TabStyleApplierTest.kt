package ca.liamstewart.tabcue.terminal

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import ca.liamstewart.tabcue.model.DotIcon
import ca.liamstewart.tabcue.settings.TINT_STRENGTH_DEFAULT
import ca.liamstewart.tabcue.model.EmojiIcon
import ca.liamstewart.tabcue.model.ColorMath
import ca.liamstewart.tabcue.model.StylePalette
import ca.liamstewart.tabcue.model.TabStyle
import javax.swing.JPanel

/**
 * Exercises the applier against a real [Content] from the platform's own [ContentFactory].
 *
 * This is the assertion that matters most in the project. `Content.setTabColor` / `getTabColor` are
 * declared as **no-op `default` methods** on the interface and only do anything on `ContentImpl`,
 * so a round-trip through a factory-built content is what proves the mechanism actually holds,
 * and it would catch the platform quietly dropping the API in a future release.
 */
class TabStyleApplierTest : BasePlatformTestCase() {

    private fun newContent(): Content =
        ContentFactory.getInstance().createContent(JPanel(), "bash", false)

    private fun applyStyle(
        content: Content,
        style: TabStyle,
        colorAsDot: Boolean = false,
    ) = TabStyleApplier.apply(
        content = content,
        style = style,
        tintEnabled = false,
        colorAsDot = colorAsDot,
        tintStrength = TINT_STRENGTH_DEFAULT,
    )

    /**
     * The tab label colour is reasserted while the tab paints, so withdrawing it has to reach the
     * facade even on a tab whose label was never found. Recording the request only on success left
     * a cleared style still asking for a colour.
     */
    fun testWithdrawingALabelColourReachesTheFacadeWithNoLabelPresent() {
        val content = newContent()
        // No window ancestor in the fixture, so the label search cannot succeed here.
        applyStyle(content, TabStyle(textColorId = "white"))
        assertEquals(StylePalette.textColor("white"), TabLabelFacade.wantedColor(content))

        applyStyle(content, TabStyle.EMPTY)
        assertNull("a style with no text colour must withdraw the request", TabLabelFacade.wantedColor(content))
    }

    fun testTabColourIsTheDerivedFillNotTheRawAccent() {
        val content = newContent()
        val accent = StylePalette.color("red")
        assertNotNull("palette must resolve a known id", accent)

        applyStyle(content, TabStyle(colorId = "red"))

        // The accent is what the dot and the tint use. What reaches the painter is the derived,
        // overlay-compensated fill: handing it the accent directly is what made tab colours look
        // washed out, since the painter composites a translucent theme overlay on top.
        assertEquals(ColorMath.tabFill(accent!!), content.tabColor)
        assertFalse(
            "the fill must not be the accent itself, or the label's contrast is unprotected",
            accent == content.tabColor,
        )
    }

    fun testACustomHexColourIsAppliedLikeAPresetOne() {
        val content = newContent()

        applyStyle(content, TabStyle(colorId = "#3B7DD8"))

        // A picked colour travels in the same colorId field as a palette id, so it must reach the
        // painter by exactly the same path, including the derivation and the compensation.
        assertEquals(ColorMath.tabFill(java.awt.Color(0x3B, 0x7D, 0xD8)), content.tabColor)
    }

    fun testAnUnparseableColourIdLeavesTheTabUnstyled() {
        val content = newContent()

        applyStyle(content, TabStyle(colorId = "#ZZZZZZ"))

        // A hand-edited settings file must degrade to "no colour", not throw out of a restyle.
        assertNull(content.tabColor)
    }

    fun testIconIsSetAndOptedInForRendering() {
        val content = newContent()

        applyStyle(content, TabStyle(iconId = "run"))

        assertNotNull("icon must be applied", content.icon)
        // Without these two keys BaseLabel either skips the icon entirely or waters it down.
        assertEquals(true, content.getUserData(ToolWindow.SHOW_CONTENT_ICON))
        assertEquals(false, content.getUserData(ToolWindowContentUi.NOT_SELECTED_TAB_ICON_TRANSPARENT))
    }

    fun testIconOptInFlagIsSetBeforeTheIconItself() {
        val content = newContent()
        val seen = mutableListOf<String>()
        // BaseLabel reads SHOW_CONTENT_ICON while handling the icon property change, so the flag
        // has to already be true by then, or the icon only shows on a later tab update,
        // which showed up as "the icon appears only once you click the tab".
        content.addPropertyChangeListener { event ->
            if (event.propertyName == Content.PROP_ICON) {
                seen += "icon:" + content.getUserData(ToolWindow.SHOW_CONTENT_ICON).toString()
            }
        }

        applyStyle(content, TabStyle(iconId = "run"))

        assertEquals(listOf("icon:true"), seen)
    }

    fun testUnknownIdsDegradeToNoStyling() {
        val content = newContent()

        applyStyle(content, TabStyle(colorId = "chartreuse", iconId = "nonesuch"))

        // A stale id from an older settings file must not throw or paint something arbitrary.
        assertNull(content.tabColor)
        assertNull(content.icon)
    }

    fun testColourAloneStillYieldsAnIconSoItSurvivesSelectionAndHover() {
        val content = newContent()

        applyStyle(content, TabStyle(colorId = "red"), colorAsDot = true)

        // The platform's tab painter drops a custom tab colour on the selected tab and paints its
        // hover colour over it on hover, so a colour-only style needs the dot to stay visible.
        assertTrue("colour should fall back to a dot icon", content.icon is DotIcon)
        assertEquals(true, content.getUserData(ToolWindow.SHOW_CONTENT_ICON))
    }

    fun testEmojiOutranksAChosenIcon() {
        val content = newContent()

        applyStyle(content, TabStyle(iconId = "run", emoji = "🚀"))

        assertTrue("emoji must win over the built-in icon", content.icon is EmojiIcon)
    }

    fun testTheColourDotYieldsToAnIconSomebodyElseSet() {
        val content = newContent()
        // PhpStorm 2026.2 brands a Codex or Junie tab with its vendor logo. The dot is only our
        // own stand-in for the tab colour, so overwriting real information with it is a downgrade.
        val foreign = com.intellij.icons.AllIcons.General.Information
        content.icon = foreign

        applyStyle(content, TabStyle(colorId = "red"), colorAsDot = true)

        assertSame("the dot must not replace an icon we do not own", foreign, content.icon)
        assertEquals(
            "the colour itself still applies",
            ColorMath.tabFill(StylePalette.color("red")!!),
            content.tabColor,
        )
    }

    fun testAnExplicitEmojiStillWinsOverAForeignIcon() {
        val content = newContent()
        val foreign = com.intellij.icons.AllIcons.General.Information
        content.icon = foreign

        applyStyle(content, TabStyle(colorId = "red", emoji = "\uD83D\uDE80"), colorAsDot = true)
        // Unlike the dot, this was asked for by name, so it takes the tab over.
        assertTrue("a chosen emoji must win", content.icon is EmojiIcon)

        applyStyle(content, TabStyle.EMPTY, colorAsDot = true)
        assertSame("and hand the tab back when it is cleared", foreign, content.icon)
    }

    fun testADotIsDroppedOnceSomethingElseClaimsTheIcon() {
        val content = newContent()
        applyStyle(content, TabStyle(colorId = "red"), colorAsDot = true)
        assertTrue("a tab with no icon of its own gets the dot", content.icon is DotIcon)

        // The IDE sets the agent logo just after creating the tab, which is just after our first
        // restyle. The style has not changed, so the change-detection cache would otherwise skip
        // this pass and the next one would paint the dot straight back over the logo.
        val foreign = com.intellij.icons.AllIcons.General.Information
        content.icon = foreign
        applyStyle(content, TabStyle(colorId = "red"), colorAsDot = true)

        assertSame("the dot must step aside", foreign, content.icon)
        // And stay stepped aside, rather than alternating with the logo on every shell prompt.
        applyStyle(content, TabStyle(colorId = "red"), colorAsDot = true)
        assertSame(foreign, content.icon)
    }

    fun testToolwindowTitleIsNeverBlankSoTheTabStripIsNotHidden() {
        val content = newContent()

        // Documents why there is no "force the tab strip" workaround. TabContentLayout hides the
        // strip for a lone tab only when getToolwindowTitle() is blank, but ContentImpl falls back
        // to the display name, which the terminal always sets. So the condition cannot trigger, and
        // any code forcing a title would be dead weight that also stops the header tracking renames.
        assertFalse(
            "ContentImpl must fall back to the display name",
            content.toolwindowTitle.isNullOrBlank(),
        )
        assertEquals("bash", content.toolwindowTitle)
    }

    fun testReapplyingAnUnchangedStyleFiresNoEvents() {
        val content = newContent()
        applyStyle(content, TabStyle(colorId = "red"), colorAsDot = true)

        var events = 0
        content.addPropertyChangeListener { events++ }
        // The service re-resolves on every terminal title change, so an unchanged result must be a
        // no-op, or every shell prompt would trigger a relayout and a Swing tree walk.
        repeat(5) { applyStyle(content, TabStyle(colorId = "red"), colorAsDot = true) }

        assertEquals("unchanged re-apply must not touch the content", 0, events)
    }

    fun testChangingTheStyleStillAppliesAfterANoOpApply() {
        val content = newContent()
        applyStyle(content, TabStyle(colorId = "red"), colorAsDot = true)
        applyStyle(content, TabStyle(colorId = "red"), colorAsDot = true)

        applyStyle(content, TabStyle(colorId = "green"), colorAsDot = true)

        assertEquals(ColorMath.tabFill(StylePalette.color("green")!!), content.tabColor)
    }

    fun testNoIconMeansTheOptInFlagIsRemovedNotSetFalse() {
        val content = newContent()
        applyStyle(content, TabStyle(iconId = "run"))
        assertEquals(true, content.getUserData(ToolWindow.SHOW_CONTENT_ICON))

        applyStyle(content, TabStyle(colorId = "red"), colorAsDot = false)

        assertNull(
            "a lingering false would suppress the terminal's own icons",
            content.getUserData(ToolWindow.SHOW_CONTENT_ICON),
        )
    }

    fun testAnUnappliedTintIsNotMemoisedSoItCanBeRetried() {
        val content = newContent()

        // A plain JPanel has no terminal editor, so the tint cannot land. The change-detection
        // cache must not record this as done, or the retry on the next tick, and every later
        // title change, would be skipped and the tint would never appear.
        TabStyleApplier.apply(
            content = content,
            style = TabStyle(colorId = "red", tintBackground = true),
            tintEnabled = true,
            colorAsDot = true,
            tintStrength = TINT_STRENGTH_DEFAULT,
        )

        assertNull("a partial apply must not be cached", TabStyleApplier.appliedStyle(content))
        // The parts that did work are still applied.
        assertEquals(ColorMath.tabFill(StylePalette.color("red")!!), content.tabColor)
    }

    fun testATintThatIsNotRequestedStillMemoises() {
        val content = newContent()

        TabStyleApplier.apply(
            content = content,
            style = TabStyle(colorId = "red"),
            tintEnabled = true,
            colorAsDot = true,
            tintStrength = TINT_STRENGTH_DEFAULT,
        )

        assertNotNull("nothing was pending, so this is a complete apply", TabStyleApplier.appliedStyle(content))
    }

    fun testClearingRemovesEverythingWeSet() {
        val content = newContent()
        applyStyle(content, TabStyle("red", "run"), colorAsDot = true)
        assertNotNull(content.tabColor)

        TabStyleApplier.reset(content)

        assertNull("colour must be cleared", content.tabColor)
        assertNull("icon must be cleared", content.icon)
        assertNull("applied-style marker must be cleared", TabStyleApplier.appliedStyle(content))
        // Left behind, these would permanently suppress any icon the terminal later wants to show,
        // and would keep our icon classes reachable so the plugin classloader could not unload.
        assertNull(content.getUserData(ToolWindow.SHOW_CONTENT_ICON))
        assertNull(content.getUserData(ToolWindowContentUi.NOT_SELECTED_TAB_ICON_TRANSPARENT))
    }

    fun testResetLeavesNoMarkersBehind() {
        // reset runs on plugin unload, where a leftover marker is not merely untidy: a stale
        // "we own this icon" would make the next load clear an icon it never set.
        val content = newContent()
        val foreign = com.intellij.icons.AllIcons.General.Information
        content.icon = foreign

        applyStyle(content, TabStyle(colorId = "red", emoji = "\uD83D\uDE80", textColorId = "white"))
        TabStyleApplier.reset(content)

        assertNull("tab colour should be gone", content.tabColor)
        assertNull("applied style should be forgotten", TabStyleApplier.appliedStyle(content))
        // The foreign icon is restored by the reset's own apply pass, then ownership is dropped.
        assertSame("the icon we replaced should be back", foreign, content.icon)
        // And it goes back to rendering the way the platform would draw it on its own.
        assertNull(content.getUserData(ToolWindowContentUi.NOT_SELECTED_TAB_ICON_TRANSPARENT))
        assertEquals(true, content.getUserData(ToolWindow.SHOW_CONTENT_ICON))
        // Left behind, a wanted colour would have the label listener recolour the tab after unload.
        assertNull(TabLabelFacade.wantedColor(content))
    }
}
