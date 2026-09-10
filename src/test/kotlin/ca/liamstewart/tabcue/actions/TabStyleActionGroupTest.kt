package ca.liamstewart.tabcue.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.wm.ToolWindowContextMenuActionBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Pins the shape of the right-click menu.
 *
 * Flat, this menu reached 38 items and five separators, roughly 950px, which scrolls on a 1080p
 * display. The structure is therefore load-bearing UX, not incidental, and it is the kind of thing
 * that regresses silently as swatches and emoji are added.
 *
 * What this cannot check is that a leaf inside a nested popup still receives the clicked `Content`.
 * That depends on the platform passing one `DataContext` down the whole group tree while expanding
 * it, which is how nested context menus work throughout the IDE but is not reachable headlessly.
 * The leaves are asserted to still *be* [ToolWindowContextMenuActionBase]s, which is the half that
 * can be verified here.
 */
class TabStyleActionGroupTest : BasePlatformTestCase() {

    private fun topLevel(): Array<AnAction> = TabStyleActionGroup().getChildren(null)

    private fun submenu(text: String): ActionGroup =
        topLevel()
            .filterIsInstance<ActionGroup>()
            .single { it.templatePresentation.text == text }

    fun testTheMenuIsGroupedRatherThanOneLongList() {
        val rows = topLevel()

        // Four submenus, a rule/state block and the settings link, short enough that nothing
        // scrolls at any sane screen height. At exactly the cap: the next addition has to go
        // inside a submenu rather than onto the top level, which is the point of the assertion.
        assertTrue("top level should stay small, was ${rows.size}", rows.size <= 10)
        listOf("Tab Color", "Text Color", "Icon", "Emoji").forEach { name ->
            assertTrue("$name should be a popup submenu", submenu(name).isPopup)
        }
    }

    fun testEveryLeafCanStillReceiveTheClickedTab() {
        val leaves = topLevel().flatMap { action ->
            if (action is ActionGroup) action.getChildren(null).toList() else listOf(action)
        }.filter { it !is Separator && it !is ActionGroup }

        assertTrue("expected leaf actions", leaves.isNotEmpty())
        leaves.forEach {
            assertTrue(
                "${it.templatePresentation.text} must be a ToolWindowContextMenuActionBase to be " +
                    "handed the Content",
                it is ToolWindowContextMenuActionBase,
            )
        }
    }

    fun testNoTwoItemsInTheSameMenuShareALabel() {
        // The regression this guards: a "Custom…" for colours and a "Custom…" for emoji sat side by
        // side in the flat menu, indistinguishable. Grouping is what disambiguates them, so the
        // property is worth asserting rather than assuming.
        (listOf(null) + listOf("Tab Color", "Text Color", "Icon", "Emoji")).forEach { group ->
            val actions = if (group == null) topLevel() else submenu(group).getChildren(null)
            val labels = actions.filter { it !is Separator }.mapNotNull { it.templatePresentation.text }
            val duplicated = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue("duplicate labels in ${group ?: "the top level"}: $duplicated", duplicated.isEmpty())
        }
    }

    fun testEveryPaletteEntryIsReachable() {
        // Guards against a swatch or emoji being added to the palette but not to the menu.
        val colors = submenu("Tab Color").getChildren(null)
        val textColors = submenu("Text Color").getChildren(null)
        val emoji = submenu("Emoji").getChildren(null)

        assertEquals(
            "8 swatches + No Color + Custom",
            ca.liamstewart.tabcue.model.StylePalette.colors.size + 2,
            colors.count { it !is Separator },
        )
        assertEquals(
            "every curated emoji + Custom",
            ca.liamstewart.tabcue.model.StylePalette.emojis.size + 1,
            emoji.count { it !is Separator },
        )
        assertEquals(
            "every text colour + Default + Custom",
            ca.liamstewart.tabcue.model.StylePalette.textColors.size + 2,
            textColors.count { it !is Separator },
        )
    }

    fun testEmojiRowsAreNamedRatherThanShowingTheGlyphTwice() {
        // The regression: SetEmojiAction passed the emoji as both the row's icon and its text, so
        // every row drew the same glyph side by side. The row text must be a name, never a glyph.
        val rows = submenu("Emoji").getChildren(null)
            .filter { it !is Separator }
            .mapNotNull { it.templatePresentation.text }

        assertTrue("expected emoji rows", rows.isNotEmpty())
        rows.forEach { text ->
            assertFalse(
                "row text should be a name, not the emoji itself: $text",
                text in ca.liamstewart.tabcue.model.StylePalette.emojis,
            )
        }
    }
}
