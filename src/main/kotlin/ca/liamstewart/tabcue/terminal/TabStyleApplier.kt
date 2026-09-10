package ca.liamstewart.tabcue.terminal

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.ui.ColorUtil
import com.intellij.ui.content.Content
import ca.liamstewart.tabcue.model.ColorMath
import ca.liamstewart.tabcue.model.StylePalette
import ca.liamstewart.tabcue.model.TabStyle
import ca.liamstewart.tabcue.settings.TINT_STRENGTH_DEFAULT
import ca.liamstewart.tabcue.util.guarded
import ca.liamstewart.tabcue.util.quietly
import java.awt.Color
import javax.swing.Icon

/**
 * Writes a [TabStyle] onto a tab's [Content].
 *
 * Everything here except the background tint is supported platform API. `ContentImpl.setTabColor`
 * fires `PROP_TAB_COLOR`, which `ToolWindowContentUi`'s property listener turns into a relayout, so
 * there is no need to repaint anything by hand.
 */
object TabStyleApplier {

    private val LOG = logger<TabStyleApplier>()

    /**
     * Everything that affects the rendered result.
     *
     * Compared before doing any work: [TabStyleService] re-resolves on every terminal title change,
     * which for a shell that reports its title per prompt means many times a minute. Without this
     * guard each of those would walk the tab's whole Swing tree looking for the output editor and
     * re-fire property changes that cause a relayout.
     */
    private data class Applied(
        val style: TabStyle,
        val colorAsDot: Boolean,
        val tintEnabled: Boolean,
        /**
         * How many output editors the tab had.
         *
         * Splitting a tinted tab adds a pane without changing any of the fields above, so without
         * this the early-out would skip the new pane and leave the tab half tinted.
         */
        val editorCount: Int,
        /** Included so changing the strength in settings actually repaints. */
        val tintStrength: Int,
    )

    private val APPLIED = Key.create<Applied>("TabCue.applied")

    /** Whether we currently have a forced background on this tab's editors. */
    private val TINTED = Key.create<Boolean>("TabCue.tinted")

    /** Whether we currently have a forced foreground on this tab's label. */
    private val TEXT_COLORED = Key.create<Boolean>("TabCue.textColored")

    /** Whether the icon currently on this tab is one we set. */
    private val ICON_OWNED = Key.create<Boolean>("TabCue.iconOwned")

    /** The icon somebody else had set before we replaced it, so clearing can put it back. */
    private val FOREIGN_ICON = Key.create<Icon>("TabCue.foreignIcon")

    /**
     * An editor's background from before we first touched it.
     *
     * Captured per editor rather than re-derived from the global scheme: the terminal may paint a
     * console background that differs from the editor default, and restoring the wrong one would
     * leave a permanently mis-coloured pane after the tint was switched off.
     */
    private val PRE_TINT_BACKGROUND = Key.create<Color>("TabCue.preTintBackground")

    /** Failed tint attempts, so a tab whose editor never appears stops retrying every prompt. */
    private val TINT_ATTEMPTS = Key.create<Int>("TabCue.tintAttempts")

    private const val MAX_TINT_ATTEMPTS = 8

    fun appliedStyle(content: Content): TabStyle? = content.getUserData(APPLIED)?.style

    fun apply(
        content: Content,
        style: TabStyle,
        tintEnabled: Boolean,
        colorAsDot: Boolean,
        tintStrength: Int,
    ) {
        val wantTintNow = tintEnabled && style.tintBackground
        val editors = if (wantTintNow || content.getUserData(TINTED) == true) {
            TerminalTabFacade.outputEditors(content)
        } else {
            emptyList()
        }
        val target = Applied(style, colorAsDot, tintEnabled, editors.size, tintStrength)
        if (content.getUserData(APPLIED) == target) return

        val wantTint = tintEnabled && style.tintBackground

        // Not the accent itself: ColorMath derives a fill that keeps the tab label legible and
        // pre-compensates for the translucent overlay the platform composites over it. Null still
        // means "no colour", which is how a colour is cleared.
        val accent = StylePalette.color(style.colorId)
        runCatching { content.setTabColor(accent?.let { ColorMath.tabFill(it) }) }
            .onFailure { LOG.warn("Could not set tab color", it) }

        runCatching { applyIcon(content, style, colorAsDot) }
            .onFailure { LOG.warn("Could not set tab icon", it) }

        // Deliberately last and deliberately swallowed: this is the one unsupported channel, and a
        // failure here must not cost the user their colour and icon.
        // Entered when a tint is wanted *or* when one is already applied and must come off. The
        // second half matters: skipping this whole block whenever `tintEnabled` was false left the
        // kill switch unable to actually remove an existing tint, so clearing the setting appeared
        // to do nothing until the IDE restarted.
        // The label is a sibling of the tab content and does not exist until the tool window has
        // been shown once, so this can legitimately fail on the first attempt — same shape as the
        // tint, and bounded by the same attempt cap below.
        val textSettled = applyTextColor(content, style)

        val tintSettled = if (wantTint || content.getUserData(TINTED) == true) {
            guarded(LOG, "Background tint unavailable for this tab") {
                applyBackgroundTint(content, wantTint, style, editors, tintStrength)
            } ?: false
        } else {
            true
        }

        // Only memoise a fully applied result. A tab styled the moment it opens may not have its
        // output editor yet, and caching that partial state would make the early-out above swallow
        // every later retry, leaving the tint permanently unapplied.
        if (tintSettled && textSettled) {
            content.putUserData(APPLIED, target)
            content.putUserData(TINT_ATTEMPTS, null)
        } else {
            // Bounded. Not memoising a failed tint is what allows a retry, but with no limit a tab
            // whose editor is never found would walk its whole Swing subtree and write a log line
            // on every shell prompt, forever.
            val attempts = (content.getUserData(TINT_ATTEMPTS) ?: 0) + 1
            content.putUserData(TINT_ATTEMPTS, attempts)
            if (attempts >= MAX_TINT_ATTEMPTS) {
                LOG.info("Giving up on the background tint for a tab after $attempts attempts")
                content.putUserData(APPLIED, target)
            }
        }
    }

    /**
     * Returns false when a label colour was wanted but the label could not be found, so it retries.
     *
     * The `TEXT_COLORED` marker is what makes clearing work: once the theme default has been
     * overwritten there is no way to ask the label what it used to be, so the only way back is to
     * reassign `JBColor.foreground()` — and we must know to do that even though the new style has
     * no colour of its own.
     */
    private fun applyTextColor(content: Content, style: TabStyle): Boolean {
        val wanted = StylePalette.textColor(style.textColorId)
        if (wanted == null && content.getUserData(TEXT_COLORED) != true) return true

        val applied = guarded(LOG, "Could not set the tab label colour") {
            TabLabelFacade.applyTextColor(content, wanted)
        } ?: false

        if (applied) content.putUserData(TEXT_COLORED, if (wanted != null) true else null)
        return applied
    }

    /**
     * Sets the tab icon, without destroying an icon somebody else owns.
     *
     * PhpStorm 2026.2 made this matter. Its "AI Agents" terminal feature sets an agent logo with
     * *exactly* the two calls used here — `putUserData(SHOW_CONTENT_ICON, true)` and
     * `content.icon = agent.icon` — on tabs it launches (Codex and Junie today; Claude Code
     * declares `showIconInTab = false`). Since this runs on every terminal tab, styled or not, an
     * unconditional `content.icon = null` silently wiped that logo the first time we touched the
     * tab. So: only ever clear an icon we set, and put back whatever was there before we did.
     */
    private fun applyIcon(content: Content, style: TabStyle, colorAsDot: Boolean) {
        val icon = StylePalette.iconFor(style, colorAsDot)
        val owned = content.getUserData(ICON_OWNED) == true

        if (icon == null) {
            // Nothing of ours on this tab: leave it exactly as we found it.
            if (!owned) return
            val restored = content.getUserData(FOREIGN_ICON)
            content.putUserData(ToolWindow.SHOW_CONTENT_ICON, if (restored != null) true else null)
            content.putUserData(
                ToolWindowContentUi.NOT_SELECTED_TAB_ICON_TRANSPARENT,
                if (restored != null) false else null,
            )
            content.icon = restored
            content.putUserData(ICON_OWNED, null)
            content.putUserData(FOREIGN_ICON, null)
            return
        }

        // Captured before the first overwrite and only then, so a later restyle cannot mistake one
        // of our own icons for the platform's.
        if (!owned) content.putUserData(FOREIGN_ICON, quietly { content.icon })

        // Order is load-bearing. `BaseLabel.updateTextAndIcon` reads both flags below while it
        // handles the PROP_ICON change, and writing user data fires no event of its own. Setting
        // the icon *last* is therefore what makes it appear immediately; with the icon set first
        // the label would refresh before the flags were true and only pick the icon up on the next
        // unrelated tab update — which is why it previously took a click on the tab to show up.
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
        // Without this, unselected tabs render the icon at 50% alpha as a WatermarkIcon.
        content.putUserData(ToolWindowContentUi.NOT_SELECTED_TAB_ICON_TRANSPARENT, false)
        content.icon = icon
        content.putUserData(ICON_OWNED, true)
    }

    /** Returns false when a tint was wanted but no editor could be found, so it can be retried. */
    private fun applyBackgroundTint(
        content: Content,
        wantTint: Boolean,
        style: TabStyle,
        editors: List<EditorEx>,
        tintStrength: Int,
    ): Boolean {
        if (editors.isEmpty()) {
            // Debug, not info: this is reached on every restyle of an affected tab, and shell
            // titles that could identify the tab must not go to idea.log anyway.
            if (wantTint) LOG.debug("No terminal editor found for a tab; skipping tint")
            return !wantTint
        }

        val swatch = StylePalette.color(style.colorId)
        val tinting = wantTint && swatch != null
        content.putUserData(TINTED, if (tinting) true else null)

        for (editor in editors) {
            quietly {
                // Captured before the first change and kept per editor, so switching the tint off
                // restores what the terminal actually had rather than an approximation.
                val original = editor.getUserData(PRE_TINT_BACKGROUND)
                    ?: editor.backgroundColor.also { editor.putUserData(PRE_TINT_BACKGROUND, it) }
                // Mixed from the *live* scheme, not from `original`, so the blend follows a theme
                // change instead of staying derived from the theme in force when it was captured.
                val base = EditorColorsManager.getInstance().globalScheme.defaultBackground ?: original
                val target = if (tinting) ColorUtil.mix(base, swatch!!, tintStrength / 100.0) else original
                editor.backgroundColor = target
                // The grid does not fill the whole viewport, so the wrapper would keep the theme colour.
                editor.component.background = target
                editor.contentComponent.background = target
            }
        }
        return true
    }

    /**
     * Removes everything this plugin set, leaving no trace on the [Content].
     *
     * Used both for "Clear Style" and on plugin unload, where any remaining user data would keep
     * plugin-loaded classes (our icons) reachable and block the classloader from being collected.
     */
    fun reset(content: Content) {
        apply(
            content = content,
            style = TabStyle.EMPTY,
            tintEnabled = true,
            colorAsDot = false,
            tintStrength = TINT_STRENGTH_DEFAULT,
        )
        content.putUserData(APPLIED, null)
        content.putUserData(TINTED, null)
        content.putUserData(TINT_ATTEMPTS, null)
    }

    /** Forces the next [apply] to do real work, e.g. after a theme change. */
    fun invalidate(content: Content) {
        content.putUserData(APPLIED, null)
    }
}
