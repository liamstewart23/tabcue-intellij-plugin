package ca.liamstewart.tabcue.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowContextMenuActionBase
import com.intellij.ui.content.Content
import com.intellij.util.ui.ColorIcon
import ca.liamstewart.tabcue.model.ColorMath
import ca.liamstewart.tabcue.model.StylePalette
import ca.liamstewart.tabcue.model.TabStyle
import ca.liamstewart.tabcue.model.EmojiIcon
import ca.liamstewart.tabcue.settings.ColorPickerPopup
import ca.liamstewart.tabcue.settings.EmojiPopup
import ca.liamstewart.tabcue.settings.TabStyleConfigurable
import ca.liamstewart.tabcue.settings.TabStyleSettings
import ca.liamstewart.tabcue.terminal.TabStyleService
import javax.swing.Icon

/**
 * The "Tab Style" submenu on a terminal tab's right-click menu.
 *
 * Registered into the platform's `ToolWindowContextMenu` group, the same one the bundled terminal
 * uses for "Rename Session", and hidden for every tool window except the terminal.
 */
internal class TabStyleActionGroup : DefaultActionGroup(), DumbAware {

    /**
     * Built once, not per render.
     *
     * The children are stateless, each receiving the clicked [Content] through its own `update`,
     * so rebuilding twenty-odd actions and eight icons every time the submenu opens was pure waste
     * on the EDT during popup layout.
     */
    private val children: Array<AnAction> by lazy {
        arrayOf(
            // Three submenus rather than three separator-delimited sections in one list. Flat, the
            // menu came to 38 items and five separators, around 950px, which scrolls on a 1080p
            // display, and scrolling to reach a colour swatch is the opposite of quick. Grouping
            // also disambiguates the two "Custom…" entries, which read identically side by side.
            // "Tab Color" rather than "Color" only since "Text Color" joined it: two rows a few
            // pixels apart, one called "Color", is a coin toss for the reader.
            submenu(
                "Tab Color",
                buildList {
                    add(SetColorAction(null, "No Color", null))
                    StylePalette.colors.forEach { swatch ->
                        add(SetColorAction(swatch.id, swatch.displayName, ColorIcon(14, swatch.color, true)))
                    }
                    add(Separator.create())
                    add(CustomColorAction())
                },
            ),
            // The label colour, which is the only cue that survives on the *selected* tab: the
            // platform paints its own background over a selected tab and discards ours.
            submenu(
                "Text Color",
                buildList {
                    add(SetTextColorAction(null, "Default", null))
                    StylePalette.textColors.forEach { choice ->
                        add(SetTextColorAction(choice.id, choice.displayName, ColorIcon(14, choice.color, true)))
                    }
                    add(Separator.create())
                    add(CustomTextColorAction())
                },
            ),
            submenu(
                "Icon",
                buildList {
                    add(SetIconAction(null, "No Icon", null))
                    StylePalette.icons.forEach { choice ->
                        add(SetIconAction(choice.id, choice.displayName, choice.icon))
                    }
                },
            ),
            // The common emoji are still one click away once the submenu is open, with no dialog,
            // was the point of moving them out of a modal in the first place.
            submenu(
                "Emoji",
                buildList {
                    StylePalette.emojiChoices.forEach { choice ->
                        add(SetEmojiAction(choice.emoji, choice.displayName))
                    }
                    add(Separator.create())
                    add(CustomEmojiAction())
                },
            ),
            Separator.create(),
            ToggleTintAction(),
            ClearStyleAction(),
            RestoreRulesAction(),
            Separator.create(),
            ConfigureRulesAction(),
        )
    }

    /**
     * A nested popup group.
     *
     * The children stay [ToolWindowContextMenuActionBase]s, so each is still handed the clicked
     * `Content` through the supported callback, since nesting changes the presentation, not the data
     * context. `setText(…, false)` because a swatch or icon name is not a mnemonic carrier.
     */
    private fun submenu(text: String, children: List<AnAction>): AnAction =
        DefaultActionGroup().apply {
            templatePresentation.setText(text, false)
            isPopup = true
            addAll(children)
        }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        // Only the tool window is checked here. Resolving the clicked Content would mean calling
        // ToolWindowContextMenuActionBase.getContextContent, which is @ApiStatus.Internal (Plugin
        // Verifier flags it), and it is unnecessary: each child extends
        // ToolWindowContextMenuActionBase and is handed the Content through the supported
        // update(event, toolWindow, content) callback, hiding itself when there is none.
        val toolWindow = e.getData(PlatformDataKeys.TOOL_WINDOW)
        e.presentation.isEnabledAndVisible =
            e.project != null && toolWindow?.id == TabStyleService.TERMINAL_TOOL_WINDOW_ID
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = children
}

private abstract class TabStyleAction(text: String, icon: Icon?) :
    ToolWindowContextMenuActionBase(), DumbAware {

    init {
        templatePresentation.text = text
        templatePresentation.icon = icon
    }

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    final override fun update(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        val project = e.project
        if (project == null || content == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = true
        updateFor(e, TabStyleService.getInstance(project), content)
    }

    final override fun actionPerformed(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        val project = e.project ?: return
        if (content == null) return
        performOn(e, TabStyleService.getInstance(project), content)
    }

    /** [manual] is the user's own override only, never what a rule happens to be painting. */
    protected open fun updateFor(e: AnActionEvent, service: TabStyleService, content: Content) = Unit

    protected abstract fun performOn(e: AnActionEvent, service: TabStyleService, content: Content)
}

private class SetColorAction(private val colorId: String?, text: String, icon: Icon?) :
    TabStyleAction(text, icon) {

    override fun updateFor(e: AnActionEvent, service: TabStyleService, content: Content) {
        Toggleable.setSelected(e.presentation, service.manualStyleFor(content).colorId == colorId)
    }

    override fun performOn(e: AnActionEvent, service: TabStyleService, content: Content) {
        service.setManualStyle(content) { it.copy(colorId = colorId) }
    }
}

/**
 * Any colour outside the palette, chosen from the platform colour picker.
 *
 * Stored as `#RRGGBB` in the same `colorId` field the palette ids use, so nothing else in the
 * plugin has to know the difference and no settings migration was needed.
 */
private class CustomColorAction : TabStyleAction("Custom…", null) {

    override fun updateFor(e: AnActionEvent, service: TabStyleService, content: Content) {
        val custom = service.manualStyleFor(content).colorId?.takeIf { ColorMath.isCustom(it) }
        // setText(text, false): the single-argument overload treats '_' and '&' as mnemonic
        // markers, and the emoji field accepts any string, so an emoji of "_" would vanish from the
        // menu and underline the next character instead.
        e.presentation.setText(if (custom == null) "Custom…" else "Custom ($custom)…", false)
        // Shows the chosen colour in the menu, the way the preset entries show theirs.
        e.presentation.icon = ColorMath.parseCustom(custom)?.let { ColorIcon(14, it, true) }
        Toggleable.setSelected(e.presentation, custom != null)
    }

    override fun performOn(e: AnActionEvent, service: TabStyleService, content: Content) {
        val current = StylePalette.color(service.manualStyleFor(content).colorId)
        ColorPickerPopup.show(e, service.project, current) { chosen ->
            service.setManualStyle(content) { it.copy(colorId = ColorMath.toColorId(chosen)) }
        }
    }
}

/** A preset label colour, or Default to hand the label back to the theme. */
private class SetTextColorAction(private val textColorId: String?, text: String, icon: Icon?) :
    TabStyleAction(text, icon) {

    override fun updateFor(e: AnActionEvent, service: TabStyleService, content: Content) {
        Toggleable.setSelected(e.presentation, service.manualStyleFor(content).textColorId == textColorId)
    }

    override fun performOn(e: AnActionEvent, service: TabStyleService, content: Content) {
        service.setManualStyle(content) { it.copy(textColorId = textColorId) }
    }
}

/** Any label colour, through the IDE's own picker. */
private class CustomTextColorAction : TabStyleAction("Custom…", null) {

    override fun updateFor(e: AnActionEvent, service: TabStyleService, content: Content) {
        val id = service.manualStyleFor(content).textColorId
        val custom = id?.takeIf { ColorMath.isCustom(it) }
        e.presentation.setText(if (custom == null) "Custom…" else "Custom ($custom)…", false)
        e.presentation.icon = custom?.let { ColorMath.parseCustom(it) }?.let { ColorIcon(14, it, true) }
        Toggleable.setSelected(e.presentation, custom != null)
    }

    override fun performOn(e: AnActionEvent, service: TabStyleService, content: Content) {
        val project = e.project ?: return
        val current = StylePalette.textColor(service.manualStyleFor(content).textColorId)
        ColorPickerPopup.show(e, project, current) { picked ->
            service.setManualStyle(content) { it.copy(textColorId = ColorMath.toColorId(picked)) }
        }
    }
}

private class SetIconAction(private val iconId: String?, text: String, icon: Icon?) :
    TabStyleAction(text, icon) {

    override fun updateFor(e: AnActionEvent, service: TabStyleService, content: Content) {
        val manual = service.manualStyleFor(content)
        // An emoji outranks a chosen icon, so nothing here is "current" while one is set.
        Toggleable.setSelected(e.presentation, manual.emoji.isNullOrBlank() && manual.iconId == iconId)
    }

    override fun performOn(e: AnActionEvent, service: TabStyleService, content: Content) {
        // Choosing a built-in icon clears any emoji, otherwise the emoji would keep winning.
        service.setManualStyle(content) { it.copy(iconId = iconId, emoji = null) }
    }
}

/**
 * One of the curated emoji, applied directly from the menu.
 *
 * The row is `[glyph] Name`, not `[glyph] glyph`: passing the emoji as both the icon and the text
 * drew it twice side by side on every row, which read as a rendering fault rather than a choice.
 */
private class SetEmojiAction(private val emoji: String, displayName: String) :
    TabStyleAction(displayName, EmojiIcon(emoji, size = 13)) {

    override fun updateFor(e: AnActionEvent, service: TabStyleService, content: Content) {
        Toggleable.setSelected(e.presentation, service.manualStyleFor(content).emoji == emoji)
    }

    override fun performOn(e: AnActionEvent, service: TabStyleService, content: Content) {
        // An emoji replaces a built-in icon rather than stacking with it.
        service.setManualStyle(content) { it.copy(emoji = emoji, iconId = null) }
    }
}

/** Anything outside the curated set, entered in a popup rather than a modal dialog. */
private class CustomEmojiAction : TabStyleAction("Custom…", null) {

    override fun updateFor(e: AnActionEvent, service: TabStyleService, content: Content) {
        val emoji = service.manualStyleFor(content).emoji?.takeIf { it.isNotBlank() }
        val custom = emoji?.takeUnless { it in StylePalette.emojis }
        // setText(text, false): the single-argument overload treats '_' and '&' as mnemonic
        // markers, and the emoji field accepts any string, so an emoji of "_" would vanish from the
        // menu and underline the next character instead.
        e.presentation.setText(if (custom == null) "Custom…" else "Custom ($custom)…", false)
        Toggleable.setSelected(e.presentation, custom != null)
    }

    override fun performOn(e: AnActionEvent, service: TabStyleService, content: Content) {
        EmojiPopup.show(e, service.manualStyleFor(content).emoji) { chosen ->
            service.setManualStyle(content) { it.copy(emoji = chosen, iconId = null) }
        }
    }
}

private class ToggleTintAction : TabStyleAction("Tint Terminal Background", null) {

    override fun updateFor(e: AnActionEvent, service: TabStyleService, content: Content) {
        val manual = service.manualStyleFor(content)
        val allowed = TabStyleSettings.getInstance(service.project).allowBackgroundTint
        Toggleable.setSelected(e.presentation, manual.tintBackground)
        // Previously this only checked for a colour, so with tinting switched off in settings the
        // item still toggled, showed a checkmark and did nothing.
        e.presentation.isEnabled = allowed && manual.colorId != null
        e.presentation.description = when {
            !allowed -> "Turn on \"Allow tinting the terminal background\" in settings first"
            manual.colorId == null -> "Pick a tab color first, because the tint is derived from it"
            else -> "Tint this session's output area"
        }
    }

    override fun performOn(e: AnActionEvent, service: TabStyleService, content: Content) {
        service.setManualStyle(content) { it.copy(tintBackground = !it.tintBackground) }
    }
}

/**
 * Marks a tab as explicitly unstyled.
 *
 * Distinct from removing the override: without an explicit marker the next restyle would re-match
 * the rules and paint the tab straight back, which reads as the menu item being broken.
 */
private class ClearStyleAction : TabStyleAction("Clear Style", null) {

    override fun updateFor(e: AnActionEvent, service: TabStyleService, content: Content) {
        val showsSomething = !service.effectiveStyleFor(content).isEmpty
        e.presentation.isEnabled = showsSomething || !service.isSuppressed(content)
        e.presentation.description = "Leave this tab unstyled, ignoring any matching rule"
    }

    override fun performOn(e: AnActionEvent, service: TabStyleService, content: Content) {
        service.clearManualStyle(content)
    }
}

/** The way back from "Clear Style", which otherwise permanently opts a tab out of the rules. */
private class RestoreRulesAction : TabStyleAction("Use Rules Again", null) {

    override fun updateFor(e: AnActionEvent, service: TabStyleService, content: Content) {
        e.presentation.isEnabledAndVisible = service.isSuppressed(content)
    }

    override fun performOn(e: AnActionEvent, service: TabStyleService, content: Content) {
        service.unsuppress(content)
    }
}

/** Without this the rules feature has no discoverable path from the menu that uses it. */
private class ConfigureRulesAction : TabStyleAction("Configure Rules…", null) {

    override fun performOn(e: AnActionEvent, service: TabStyleService, content: Content) {
        ShowSettingsUtil.getInstance().showSettingsDialog(service.project, TabStyleConfigurable::class.java)
    }
}
