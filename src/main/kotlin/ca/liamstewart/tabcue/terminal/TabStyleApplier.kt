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
 * Everything except the background tint is supported platform API. `setTabColor` fires
 * `PROP_TAB_COLOR`, which the platform turns into a relayout, so nothing here repaints by hand.
 */
object TabStyleApplier {

    private val LOG = logger<TabStyleApplier>()

    /**
     * Everything that affects the rendered result, compared before doing any work.
     *
     * [TabStyleService] re-resolves on every terminal title change, which for a shell reporting
     * its title per prompt is many times a minute. Each one would otherwise walk the tab's Swing
     * tree for the output editor and re-fire property changes that cause a relayout.
     */
    private data class Applied(
        val style: TabStyle,
        val colorAsDot: Boolean,
        val tintEnabled: Boolean,
        /**
         * How many output editors the tab had. Splitting a tinted tab adds a pane without
         * changing anything above, and the early-out would leave the new pane untinted.
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

    /** The icon we last set, so ownership is checked against the tab. Our icons implement `equals`. */
    private val OUR_ICON = Key.create<Icon>("TabCue.ourIcon")

    /** The icon somebody else had set before we replaced it, so clearing can put it back. */
    private val FOREIGN_ICON = Key.create<Icon>("TabCue.foreignIcon")

    /**
     * An editor's background from before we first touched it. Captured per editor rather than
     * re-derived from the scheme, since the terminal may paint a console background of its own and
     * restoring the wrong one leaves a permanently mis-coloured pane.
     */
    private val PRE_TINT_BACKGROUND = Key.create<Color>("TabCue.preTintBackground")

    /** Failed attempts, so a tab whose editor or label never appears stops retrying every prompt. */
    private val SETTLE_ATTEMPTS = Key.create<Int>("TabCue.settleAttempts")

    private const val MAX_SETTLE_ATTEMPTS = 8

    fun appliedStyle(content: Content): TabStyle? = content.getUserData(APPLIED)?.style

    fun apply(
        content: Content,
        style: TabStyle,
        tintEnabled: Boolean,
        colorAsDot: Boolean,
        tintStrength: Int,
    ) {
        // Ahead of the memo, because the platform resets the label colour behind our back and
        // neither a LaF change nor a rebuilt tool window invalidates the memo. Cheap to repeat:
        // the label is cached and nothing is written unless the colour differs.
        val textSettled = applyTextColor(content, style)

        val wantTint = tintEnabled && style.tintBackground
        val editors = if (wantTint || content.getUserData(TINTED) == true) {
            TerminalTabFacade.outputEditors(content)
        } else {
            emptyList()
        }
        val target = Applied(style, colorAsDot, tintEnabled, editors.size, tintStrength)
        // The icon is checked too: the memo cannot see somebody else having replaced it.
        val iconSettled = content.getUserData(OUR_ICON).let { it == null || it == quietly { content.icon } }
        if (content.getUserData(APPLIED) == target && textSettled && iconSettled) return

        // Not the accent itself: ColorMath derives a fill that keeps the tab label legible and
        // pre-compensates for the translucent overlay the platform composites over it. Null still
        // means "no colour", which is how a colour is cleared.
        val accent = StylePalette.color(style.colorId)
        runCatching { content.setTabColor(accent?.let { ColorMath.tabFill(it) }) }
            .onFailure { LOG.warn("Could not set tab color", it) }

        runCatching { applyIcon(content, style, colorAsDot) }
            .onFailure { LOG.warn("Could not set tab icon", it) }

        // Last and swallowed: the tint is the one unsupported channel, and failing here must not
        // cost the user their colour and icon. Also entered when a tint is already applied and has
        // to come off, so switching the setting off can actually remove it.
        val tintSettled = if (wantTint || content.getUserData(TINTED) == true) {
            guarded(LOG, "Background tint unavailable for this tab") {
                applyBackgroundTint(content, wantTint, style, editors, tintStrength)
            } ?: false
        } else {
            true
        }

        // Only memoise a fully applied result: a tab styled the moment it opens may not have its
        // output editor yet, and caching that would make the early-out swallow every later retry.
        if (tintSettled && textSettled) {
            content.putUserData(APPLIED, target)
            content.putUserData(SETTLE_ATTEMPTS, null)
        } else {
            // Bounded, or a tab whose editor never appears retries on every shell prompt forever.
            val attempts = (content.getUserData(SETTLE_ATTEMPTS) ?: 0) + 1
            content.putUserData(SETTLE_ATTEMPTS, attempts)
            if (attempts >= MAX_SETTLE_ATTEMPTS) {
                LOG.info("Gave up applying the tint or label colour after $attempts attempts")
                content.putUserData(APPLIED, target)
            }
        }
    }

    /**
     * Returns false when a label colour was wanted but the label could not be found, so it retries.
     *
     * The marker is what makes clearing work: the label cannot say what colour it used to have, so
     * the only way back is to reassign the theme's, and we have to know to do that for a style that
     * has no colour of its own.
     */
    private fun applyTextColor(content: Content, style: TabStyle): Boolean {
        val wanted = StylePalette.textColor(style.textColorId)
        if (wanted == null && content.getUserData(TEXT_COLORED) != true) return true

        val applied = guarded(LOG, "Could not set the tab label colour") {
            TabLabelFacade.applyTextColor(content, wanted)
        } ?: false

        // Recorded on request rather than on success. The facade remembers the wanted colour and
        // reapplies it whenever the label is painted, so a later clear has to reach it even if this
        // attempt could not find the label yet.
        content.putUserData(TEXT_COLORED, if (wanted != null) true else null)
        return applied
    }

    /**
     * Sets the tab icon without destroying an icon somebody else owns.
     *
     * PhpStorm 2026.2 brands AI agent tabs with a vendor logo using the same two calls used here,
     * and every terminal tab is restyled whether or not it has a style. So an icon we did not set
     * is put back when our style no longer needs one, and the dot, being only our stand-in for the
     * tab colour, yields to it entirely. An emoji or a chosen icon still wins.
     */
    private fun applyIcon(content: Content, style: TabStyle, colorAsDot: Boolean) {
        val present = quietly { content.icon }
        val ours = content.getUserData(OUR_ICON)
        // Anything else on the tab means ownership moved, and what is there now is what to protect.
        val owned = ours != null && present == ours
        val foreign = if (owned) content.getUserData(FOREIGN_ICON) else present

        val icon = StylePalette.chosenIcon(style)
            ?: StylePalette.colorDot(style).takeIf { colorAsDot && foreign == null }

        if (icon == null) {
            // Never touched this tab, so there is nothing of ours to undo.
            if (ours == null) return
            // The icon flag has to stay on for a foreign icon, which needs it to render at all.
            // The transparency flag is cleared either way: left at false it would keep somebody
            // else's icon fully opaque on unselected tabs, where the platform draws it at 50%.
            content.putUserData(ToolWindow.SHOW_CONTENT_ICON, if (foreign != null) true else null)
            content.putUserData(ToolWindowContentUi.NOT_SELECTED_TAB_ICON_TRANSPARENT, null)
            // Only while it is still ours: otherwise this would undo whatever replaced it.
            if (owned) content.icon = foreign
            content.putUserData(OUR_ICON, null)
            content.putUserData(FOREIGN_ICON, null)
            return
        }

        // Recorded as ownership is taken, so a later restyle cannot mistake our icon for theirs.
        if (!owned) content.putUserData(FOREIGN_ICON, foreign)

        // Order is load-bearing. The label reads both flags below while handling the icon change,
        // and writing user data fires no event, so setting the icon last is what makes it appear
        // immediately. The other way round it took a click on the tab to show up.
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
        // Without this, unselected tabs render the icon at 50% alpha as a WatermarkIcon.
        content.putUserData(ToolWindowContentUi.NOT_SELECTED_TAB_ICON_TRANSPARENT, false)
        content.icon = icon
        content.putUserData(OUR_ICON, icon)
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
     * Removes everything this plugin set, leaving no trace on the [Content]. Used for "Clear
     * Style" and on unload, where leftover user data keeps our icon classes reachable and stops
     * the plugin classloader being collected.
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
        content.putUserData(SETTLE_ATTEMPTS, null)
        content.putUserData(TEXT_COLORED, null)
        content.putUserData(OUR_ICON, null)
        content.putUserData(FOREIGN_ICON, null)
        TabLabelFacade.forget(content)
    }

    /** Forces the next [apply] to do real work, e.g. after a theme change. */
    fun invalidate(content: Content) {
        content.putUserData(APPLIED, null)
        // A theme change also recreates label colours, so the cached label is worth re-finding.
        TabLabelFacade.forget(content)
    }
}
