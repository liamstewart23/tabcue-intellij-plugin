package ca.liamstewart.tabcue.terminal

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import ca.liamstewart.tabcue.model.MatchField
import ca.liamstewart.tabcue.model.ColorMath
import ca.liamstewart.tabcue.model.StylePalette
import ca.liamstewart.tabcue.model.StyleRule
import ca.liamstewart.tabcue.model.TabStyle
import ca.liamstewart.tabcue.settings.TabStyleSettings
import javax.swing.JPanel

/**
 * Drives the real service against a real project.
 *
 * This covers what neither the pure unit tests nor a GUI smoke test reach: service instantiation,
 * listener installation, and the full precedence chain (suppressed → manual → rule → auto) with
 * real persistence behind it. There is no terminal here, so tabs resolve no working directory —
 * which is exactly the fallback path worth exercising.
 */
class TabStyleServiceTest : BasePlatformTestCase() {

    private val service get() = TabStyleService.getInstance(project)
    private val settings get() = TabStyleSettings.getInstance(project)

    /**
     * Names are unique per test: overrides are persisted against the *project*, which
     * BasePlatformTestCase reuses across the methods in a class, so a shared name would let one
     * test's override decide another test's result.
     */
    private fun newContent(name: String): Content =
        ContentFactory.getInstance().createContent(JPanel(), "${getTestName(false)}-$name", false)

    override fun tearDown() {
        try {
            settings.setRules(emptyList())
            settings.autoAssignColors = false
        } finally {
            super.tearDown()
        }
    }

    fun testInstallIsIdempotent() {
        // The startup activity should never run twice, but a dynamic plugin reload makes it
        // possible, and double-registered listeners would double every restyle pass.
        service.install()
        service.install()
        service.install()
    }

    fun testManualStyleIsAppliedAndReadBack() {
        val content = newContent("a")

        service.setManualStyle(content) { it.copy(colorId = "red") }

        assertEquals("red", service.manualStyleFor(content).colorId)
        assertEquals(ColorMath.tabFill(StylePalette.color("red")!!), content.tabColor)
    }

    fun testManualStyleOutranksAMatchingRule() {
        settings.setRules(listOf(StyleRule(MatchField.TAB_TITLE, "*-bash", TabStyle(colorId = "green"))))
        val content = newContent("bash")

        service.restyle(content)
        assertEquals("the rule should apply first", ColorMath.tabFill(StylePalette.color("green")!!), content.tabColor)

        service.setManualStyle(content) { it.copy(colorId = "blue") }
        assertEquals(ColorMath.tabFill(StylePalette.color("blue")!!), content.tabColor)
    }

    fun testAManualStyleDoesNotAbsorbTheRulesColour() {
        // Starting from the rule's style would silently copy its color into the new override and
        // shadow every later edit to that rule.
        settings.setRules(
            listOf(StyleRule(MatchField.TAB_TITLE, "*-bash", TabStyle(colorId = "green", tintBackground = true)))
        )
        val content = newContent("bash")
        service.restyle(content)

        service.setManualStyle(content) { it.copy(emoji = "🚀") }

        val manual = service.manualStyleFor(content)
        assertEquals("🚀", manual.emoji)
        assertNull("the rule's color must not leak into the override", manual.colorId)
        assertFalse("nor its tint", manual.tintBackground)
    }

    fun testClearingBeatsRulesAndIsReversible() {
        settings.setRules(listOf(StyleRule(MatchField.TAB_TITLE, "*-bash", TabStyle(colorId = "green"))))
        val content = newContent("bash")
        service.restyle(content)
        assertNotNull(content.tabColor)

        service.clearManualStyle(content)

        // Deleting the override alone would let the rule repaint the tab immediately.
        assertTrue(service.isSuppressed(content))
        assertNull("a cleared tab must stay unstyled", content.tabColor)

        service.unsuppress(content)
        assertFalse(service.isSuppressed(content))
        assertEquals("rules apply again afterwards", ColorMath.tabFill(StylePalette.color("green")!!), content.tabColor)
    }

    fun testTwoTabsWithTheSameIdentityDoNotShareAnOverride() {
        // No terminal, so neither resolves a working directory and both fall back to the same
        // label. Without a discriminator they would collide and styling one would style both.
        val first = newContent("bash")
        val second = newContent("bash")
        service.restyle(first)
        service.restyle(second)

        service.setManualStyle(first) { it.copy(colorId = "red") }

        assertEquals("red", service.manualStyleFor(first).colorId)
        assertNull("the second tab must be untouched", service.manualStyleFor(second).colorId)
        assertNull(second.tabColor)
    }

    fun testTheFirstTabKeepsAnUnsuffixedKeyForBackwardsCompatibility() {
        val content = newContent("bash")
        // Styles written by a build that predates the discriminator are keyed without a suffix, so
        // the first tab must still produce the bare key or every existing style is orphaned.
        assertFalse(service.styleKeyFor(content).contains("#"))
    }

    fun testAutoAssignmentGivesDistinctColoursAndKeepsThemStable() {
        settings.autoAssignColors = true
        val first = newContent("one")
        val second = newContent("two")

        service.restyle(first)
        service.restyle(second)
        val firstColor = first.tabColor
        val secondColor = second.tabColor

        assertNotNull(firstColor)
        assertNotNull(secondColor)
        assertFalse("auto-assigned tabs should differ", firstColor == secondColor)

        // Re-resolving on every title change must not make a tab's colour drift.
        repeat(5) { service.restyle(first); service.restyle(second) }
        assertEquals(firstColor, first.tabColor)
        assertEquals(secondColor, second.tabColor)
    }

    fun testStyleKeyResolutionHasNoSideEffectOnRepeatedCalls() {
        val content = newContent("bash")
        // Called from action update() while the menu is built, so it must be pure — pinning there
        // would freeze the tab's identity at whatever the shell reported at that moment.
        val first = service.styleKeyFor(content)
        val second = service.styleKeyFor(content)
        assertEquals(first, second)
        assertTrue(service.manualStyleFor(content).isEmpty)
    }

    fun testRestylingAnUnknownContentDoesNotThrow() {
        // Nothing here is a real terminal tab, so every reflective lookup misses. The service must
        // degrade quietly rather than propagate.
        service.restyle(newContent("stranger"))
        service.restyleAllTabs()
    }

    fun testStylingATornDownTabIsIgnored() {
        val content = newContent("disposed")
        com.intellij.openapi.util.Disposer.dispose(content)

        service.setManualStyle(content) { it.copy(colorId = "red") }

        // Reachable in practice: the colour picker commits continuously while it is open, so a tab
        // closed mid-drag would otherwise be written. Once the session is torn down styleKeyFor
        // falls back to the weak `tab:<label>` identity, so the write would land under a key shared
        // with every other unresolved tab and a later tab could inherit it.
        assertEquals(TabStyle.EMPTY, service.manualStyleFor(content))
        assertNull(content.tabColor)
    }

    fun testAliveButUnattachedTabsAreStillStyled() {
        // Pins the distinction the guard has to make. An earlier version of it asked "has this left
        // every content manager", which is also true of a live but unattached tab -- and that
        // turned every style mutation into a silent no-op.
        val content = newContent("unattached")
        assertNull("precondition: this content is in no manager", content.manager)

        service.setManualStyle(content) { it.copy(colorId = "blue") }

        assertEquals("blue", service.manualStyleFor(content).colorId)
    }

    fun testAPinnedKeyIsReturnedWithoutReResolvingTheTab() {
        // Opening the context menu asks for the key once per item. Resolving it goes through a
        // reflective terminal lookup and a shell working-directory query, so a tab whose key is
        // already pinned must short-circuit -- the key must also stay stable, which is what makes
        // a hand-set style survive the shell changing directory.
        val content = newContent("pinned")
        service.setManualStyle(content) { it.copy(colorId = "teal") }

        val first = service.styleKeyFor(content)
        repeat(20) { assertEquals("the pinned key must not drift", first, service.styleKeyFor(content)) }
        assertEquals("teal", service.manualStyleFor(content).colorId)
    }
}
