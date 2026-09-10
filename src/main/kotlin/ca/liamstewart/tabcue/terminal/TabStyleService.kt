package ca.liamstewart.tabcue.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.terminal.TerminalTitle
import com.intellij.terminal.TerminalTitleListener
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.Alarm
import ca.liamstewart.tabcue.model.RuleMatcher
import ca.liamstewart.tabcue.model.StylePalette
import ca.liamstewart.tabcue.model.TabFacts
import ca.liamstewart.tabcue.model.TabStyle
import ca.liamstewart.tabcue.settings.TabStyleSettings
import ca.liamstewart.tabcue.util.guarded
import ca.liamstewart.tabcue.util.quietly
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns tab styling for one project: watches for new tabs, decides what style each should get, and
 * keeps that style correct as tabs are renamed or settings change.
 *
 * Precedence: an explicit "cleared" marker, then a manual override, then the first matching rule,
 * then auto-assignment (when enabled), then no styling.
 */
private class PinnedIndex(val base: String, val index: Int)

@Service(Service.Level.PROJECT)
class TabStyleService(val project: Project) : Disposable {

    private val settings get() = TabStyleSettings.getInstance(project)

    /** Per-tab lifetimes for title listeners, so closing a tab unsubscribes it. */
    private val tabDisposables = ConcurrentHashMap<Content, Disposable>()

    /** Every content manager we have hooked; a tool window can be undocked into a new one. */
    private val watchedManagers = ConcurrentHashMap.newKeySet<ContentManager>()

    /**
     * Every tab we have styled.
     *
     * Used instead of the tool window's own content list when comparing a tab against its
     * siblings. The terminal can move a session out to an editor ("Move to Editor"), and such a
     * tab is in no tool window content manager at all — so it would be invisible to the identity
     * discriminator and to auto-colour assignment, and invisible to its siblings, letting keys
     * collide and colours duplicate.
     */
    private val knownContents = ConcurrentHashMap.newKeySet<Content>()

    private val retryAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private var installed = false

    override fun dispose() {
        tabDisposables.keys.toList().forEach { forget(it) }
        knownContents.clear()
        // The plugin is dynamically loadable, so disabling or uninstalling it must not leave
        // coloured tabs behind until the next restart — and while our icons and user data are still
        // attached to live Contents, the plugin classloader cannot be collected either, which the
        // platform reports as "plugin was not unloaded, restart required".
        if (ApplicationManager.getApplication().isDispatchThread) {
            guarded(LOG, "Failed to reset tab styling on unload") {
                allKnownTabs().forEach { content ->
                    TabStyleApplier.reset(content)
                    // The applier clears its own keys; these are ours. A non-null value here keeps
                    // the Key instance — a plugin-loaded class — reachable from the Content.
                    content.putUserData(STYLE_KEY, null)
                    content.putUserData(AUTO_COLOR, null)
                    content.putUserData(KEY_INDEX, null)
                }
            }
        }
    }

    // ---------------------------------------------------------------- installation

    fun install() {
        if (installed) return
        installed = true

        settings.addChangeListener(this) { restyleAllTabs() }

        // A theme switch changes the terminal background the tint is derived from, and nothing else
        // would trigger a restyle until the next title change.
        // Only the editor-colors topic: a theme switch fires both this and LafManagerListener, and
        // subscribing to both ran two full restyle passes — including the Swing walk for tints —
        // for one user action. The tint is derived from the colors scheme, so this is the one that
        // matters.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            EditorColorsManager.TOPIC,
            object : EditorColorsListener {
                override fun globalSchemeChange(scheme: EditorColorsScheme?) = onThemeChanged()
            },
        )

        // Tier 1: the reworked-terminal tab event. Fires with the tab fully constructed, which is
        // what lets us avoid the polling/timer approach other plugins resort to.
        TerminalTabFacade.installTabAddedListener(project, this) { content ->
            onEdt { onTabAppeared(content) }
        }

        // Tier 2: engine-agnostic. Covers the classic engine, tabs restored on project reopen, and
        // the case where the experimental Gen2 listener stops reporting after an API change.
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
        if (toolWindow?.contentManagerIfCreated != null) {
            watch(toolWindow.contentManagerIfCreated!!)
        }
        // Subscribed regardless: the tool window may not exist yet, and undocking it creates a
        // second content manager that the first registration would never see.
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(shown: ToolWindow) {
                    if (shown.id == TERMINAL_TOOL_WINDOW_ID) {
                        shown.contentManagerIfCreated?.let { watch(it) }
                    }
                }
            },
        )
    }

    private fun watch(contentManager: ContentManager) {
        if (!watchedManagers.add(contentManager)) return

        val listener = object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) {
                onEdt { onTabAppeared(event.content) }
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                // Not forgotten outright: this also fires when a session is moved to an editor,
                // and dropping it here would make it invisible to sibling comparison again.
                if (isGone(event.content)) forget(event.content)
            }
        }
        contentManager.addContentManagerListener(listener)
        Disposer.register(this) {
            contentManager.removeContentManagerListener(listener)
            watchedManagers.remove(contentManager)
        }

        // Style anything already open — reopening a project restores tabs before we get here.
        contentManager.contents.forEach { content -> onEdt { onTabAppeared(content) } }
    }

    private fun onThemeChanged() {
        onEdt {
            // allKnownTabs, not terminalContents: a session moved to an editor tab is still
            // styled, and the derived tab fill depends on the theme, so a stale APPLIED record
            // would make the restyle below a no-op for exactly those tabs.
            allKnownTabs().forEach { TabStyleApplier.invalidate(it) }
            restyleAllTabs()
        }
    }

    // ---------------------------------------------------------------- tab lifecycle

    fun onTabAppeared(content: Content) {
        // Always attempted, not just on first sight: a tab can be detached to an editor and
        // re-attached, which drops its title listener. ensureSubscribed is idempotent, so
        // re-entry from the second listener tier is free.
        ensureSubscribed(content, attempt = 0)
        restyle(content)
    }

    /**
     * Attaches the title listener, retrying with a growing delay while the terminal view is not
     * resolvable yet.
     *
     * Gen2 builds `TerminalView` asynchronously. Retrying on immediate `invokeLater` ticks would
     * drain the whole budget inside one event-queue burst — a fraction of a millisecond — so a tab
     * that is a little slow would end up with no listener for the rest of the session, meaning
     * working-directory rules would never apply to it.
     */
    private fun ensureSubscribed(content: Content, attempt: Int) {
        if (tabDisposables.containsKey(content)) return
        // The retry window is a few seconds and holds a strong reference. Without this, closing a
        // tab inside it would leave a live title listener and the whole session graph retained for
        // the project's lifetime, because contentRemoved already ran before we recorded anything.
        if (attempt > 0 && isGone(content)) {
            forget(content)
            return
        }
        if (subscribeToTitleChanges(content)) {
            restyle(content)
            return
        }
        if (attempt >= SUBSCRIBE_ATTEMPTS || retryAlarm.isDisposed) return
        val delay = SUBSCRIBE_BASE_DELAY_MS shl attempt
        retryAlarm.addRequest({ ensureSubscribed(content, attempt + 1) }, delay)
    }

    private fun forget(content: Content) {
        tabDisposables.remove(content)?.let { Disposer.dispose(it) }
        knownContents.remove(content)
    }

    /** A content that has left every manager and is not merely detached to an editor. */
    private fun isGone(content: Content): Boolean =
        quietly { content.manager } == null && content !in terminalContents()

    /**
     * Re-resolve on every title change.
     *
     * This is what makes title rules match the *running* command rather than just the launch
     * command, and it also covers the fact that a tab's working directory is generally not known
     * yet at the moment the tab is created.
     */
    private fun subscribeToTitleChanges(content: Content): Boolean {
        val title: TerminalTitle = TerminalTabFacade.describe(project, content).title ?: return false
        val lifetime = Disposer.newDisposable("TabCue.tab")
        Disposer.register(this, lifetime)

        val listener = object : TerminalTitleListener {
            override fun onTitleChanged(terminalTitle: TerminalTitle) {
                onEdt { restyle(content) }
            }
        }
        val attached = guarded(LOG, "Could not subscribe to terminal title changes") {
            title.addTitleListener(listener, lifetime)
            true
        } ?: false

        // Recorded only on success. Registering the lifetime first would make the containsKey
        // guard above short-circuit every retry, leaving the tab permanently "subscribed" with no
        // listener attached.
        if (attached) {
            tabDisposables[content] = lifetime
        } else {
            Disposer.dispose(lifetime)
        }
        return attached
    }

    // ---------------------------------------------------------------- styling

    fun restyle(content: Content) {
        knownContents += content
        guarded(LOG, "Failed to style a terminal tab") {
            TabStyleApplier.apply(
                content = content,
                style = resolveStyle(content),
                tintEnabled = settings.allowBackgroundTint,
                colorAsDot = settings.showColorAsDot,
                tintStrength = settings.tintStrength,
            )
        }
    }

    fun restyleAllTabs() {
        onEdt { allKnownTabs().forEach { restyle(it) } }
    }

    /**
     * Every tab we should act on: the tool window's own plus anything we have styled.
     *
     * Bulk operations must use this rather than [terminalContents]. A tab in a second (undocked)
     * content manager, or one detached to an editor, is absent from the primary manager — so a
     * theme change would leave it tinted from the old theme, a settings change would not reach it,
     * and disposal would leave it styled after the plugin unloaded.
     */
    private fun allKnownTabs(): List<Content> = (terminalContents() + knownContents).distinct()

    /** Other tabs this style should be distinguished from, including any detached to an editor. */
    private fun siblingsOf(content: Content): List<Content> =
        allKnownTabs().filter { it !== content }

    fun terminalContents(): List<Content> {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?: return emptyList()
        return toolWindow.contentManagerIfCreated?.contents?.toList() ?: emptyList()
    }

    fun factsFor(content: Content): TabFacts =
        factsFor(content, TerminalTabFacade.describe(project, content))

    private fun factsFor(content: Content, info: TerminalTabFacade.TerminalTabInfo): TabFacts {
        val displayed = info.title?.buildTitle()?.takeIf { it.isNotBlank() } ?: tabLabel(content)
        return TabFacts(title = displayed, workingDirectory = info.currentDirectory)
    }

    /** One definition of "the tab's name", so the fallbacks cannot disagree with each other. */
    private fun tabLabel(content: Content): String? =
        content.displayName?.takeIf { it.isNotBlank() } ?: content.tabName?.takeIf { it.isNotBlank() }

    /**
     * The identity used to persist a manual override.
     *
     * **Pure** — it deliberately does not pin anything. It is called from action `update()` while
     * the context menu is being built, and pinning there would freeze the tab's identity at
     * whatever directory the shell happened to be in when the menu was opened. Only
     * [setManualStyle] pins.
     *
     * Preference order: a user-defined title is the most stable identity available, since the user
     * chose it and nothing else rewrites it. Otherwise the working directory, which reads naturally
     * — the terminal you open in a project keeps its colour — but is *not* unique, so it carries a
     * discriminator to stop two terminals in the same directory sharing one override.
     */
    /**
     * The pinned key is checked *before* resolving tab info, not after.
     *
     * The private overload short-circuits on `STYLE_KEY` too, but as an argument
     * `TerminalTabFacade.describe` was evaluated eagerly and then discarded — a reflective tab
     * lookup plus a shell working-directory query, run once per context-menu item, for a tab whose
     * key was already known. Any tab the user has styled by hand has that key pinned, so the common
     * case is now free.
     */
    fun styleKeyFor(content: Content): String =
        content.getUserData(STYLE_KEY)
            ?: styleKeyFor(content, TerminalTabFacade.describe(project, content))

    private fun styleKeyFor(content: Content, info: TerminalTabFacade.TerminalTabInfo): String {
        content.getUserData(STYLE_KEY)?.let { return it }

        val userDefined = info.title?.userDefinedTitle?.takeIf { it.isNotBlank() }
        val cwd = info.currentDirectory?.takeIf { it.isNotBlank() }
        val base = when {
            userDefined != null -> "name:$userDefined"
            cwd != null -> "cwd:$cwd"
            else -> "tab:${tabLabel(content) ?: "terminal"}"
        }

        // Applied to every key shape, not just directories: two tabs can equally share a working
        // directory, a fallback label ("bash"), or even a hand-typed name, and any of those
        // colliding means styling one tab silently styles the other while the menu's checkmarks
        // describe the wrong one. Index 0 keeps the bare key, so styles saved before the
        // discriminator existed still resolve instead of being silently orphaned.
        val index = discriminatorFor(content, base)
        return if (index == 0) base else "$base#$index"
    }

    /**
     * The lowest index not already pinned by another live tab sharing this prefix.
     *
     * Two terminals opened in the project root — the default — would otherwise both resolve to
     * `cwd:/path`, so styling one would silently style the other and the context menu's
     * checkmarks would describe the wrong tab.
     */
    private fun discriminatorFor(content: Content, base: String): Int {
        // Pinned per (tab, base). The index is derived from live siblings, so recomputing it is not
        // stable over a tab's lifetime: with three terminals in one directory, closing the middle
        // one would renumber the third onto the closed tab's key and hand it that tab's style.
        //
        // Pinning *this* is safe in a way that pinning the whole key was not. The index only
        // disambiguates tabs sharing a base; if the shell changes directory the base changes too
        // and a fresh index is computed for it, so nothing is frozen to a transient directory.
        content.getUserData(KEY_INDEX)?.let { pinned ->
            if (pinned.base == base) return pinned.index
        }

        val taken = siblingsOf(content)
            .mapNotNull { it.getUserData(KEY_INDEX) }
            .filter { it.base == base }
            .map { it.index }
            .toSet()
        var index = 0
        while (index in taken) index++
        content.putUserData(KEY_INDEX, PinnedIndex(base, index))
        return index
    }

    private fun resolveStyle(content: Content): TabStyle {
        // Resolved once and threaded through: this runs on every terminal title change.
        val info = TerminalTabFacade.describe(project, content)
        val key = styleKeyFor(content, info)
        // An explicit "cleared" outranks everything, otherwise clearing a rule-styled tab would
        // just delete the override and let the rule paint it again immediately.
        if (settings.isSuppressed(key)) return TabStyle.EMPTY
        settings.overrideFor(key)?.let { return it }
        RuleMatcher.firstMatch(settings.rules(), factsFor(content, info))?.let { return it.style }
        if (settings.autoAssignColors) return TabStyle(colorId = autoColorFor(content, key))
        return TabStyle.EMPTY
    }

    /**
     * Picks a palette colour for a tab that has no style of its own.
     *
     * Derived from the tab's own identity — [key] is its title and working directory — rather than
     * handed out in the order tabs happen to open. That is what makes the colour a property of the
     * *tab* instead of the session: the same tab comes back the same colour after a restart, and
     * on a colleague's machine, because `String.hashCode` is specified by the JDK rather than
     * being an implementation detail. Handing out "first unused" instead meant reopening a project
     * and finding every terminal a different colour than yesterday, which trains you to ignore the
     * colours entirely.
     *
     * Distinctness still wins where the two conflict: the hash only chooses where to *start*, and
     * a colour already on a sibling is skipped. Two tabs therefore never collide just because
     * their names happened to hash together, and with fewer tabs than colours the walk almost
     * never moves.
     *
     * Pinned per tab on first use, because this is re-resolved on every terminal title change and
     * the sibling set changes as tabs come and go.
     */
    private fun autoColorFor(content: Content, key: String): String {
        content.getUserData(AUTO_COLOR)?.let { return it }

        val others = siblingsOf(content)
        // Colours from rules and manual overrides count as taken too, or an auto tab would happily
        // duplicate a rule's red while most of the palette sat unused.
        val inUse = others.flatMap { other ->
            listOfNotNull(other.getUserData(AUTO_COLOR), TabStyleApplier.appliedStyle(other)?.colorId)
        }.toSet()

        val start = key.hashCode().mod(StylePalette.colors.size)
        val chosen = StylePalette.colors.indices.asSequence()
            .map { StylePalette.colorIdAt(start + it) }
            .firstOrNull { it !in inUse }
            // Every colour is taken: more tabs than the palette has entries, so a repeat is
            // unavoidable. Repeat this tab's own colour rather than an arbitrary one.
            ?: StylePalette.colorIdAt(start)

        content.putUserData(AUTO_COLOR, chosen)
        return chosen
    }

    // ---------------------------------------------------------------- manual styling

    fun setManualStyle(content: Content, transform: (TabStyle) -> TabStyle) {
        if (isTornDown(content)) return
        val key = styleKeyFor(content)
        // The user chose this deliberately, so lock the identity here — the one place it is right
        // to do so — even if it is the weak fallback.
        content.putUserData(STYLE_KEY, key)

        // Based on the override alone, never on what a rule happens to be painting: starting from
        // the rule's style would silently copy its colour and tint into a new override and shadow
        // every later edit to that rule.
        val current = settings.overrideFor(key)?.takeUnless { settings.isSuppressed(key) } ?: TabStyle.EMPTY
        val updated = transform(current)
        if (updated.isEmpty) {
            // Just drops the override, so rules apply again. Suppression is reachable only through
            // Clear Style: picking "No Color" asked to remove a color, not to opt the tab out of
            // every rule forever, and conflating the two left no way to do the former.
            settings.clearOverride(key)
        } else {
            settings.setOverride(key, updated)
        }
        restyle(content)
        // The output editor may not exist yet on a freshly opened tab, and the tint needs it.
        ApplicationManager.getApplication().invokeLater({ restyle(content) }, project.disposed)
    }

    fun clearManualStyle(content: Content) {
        if (isTornDown(content)) return
        val key = styleKeyFor(content)
        content.putUserData(STYLE_KEY, key)
        settings.suppress(key)
        // Otherwise the tab would snap straight back to its pinned auto-assigned colour.
        content.putUserData(AUTO_COLOR, null)
        TabStyleApplier.reset(content)
        restyle(content)
    }

    /** Restores rule and auto-assign behaviour for a tab the user had explicitly cleared. */
    fun unsuppress(content: Content) {
        if (isTornDown(content)) return
        settings.clearOverride(styleKeyFor(content))
        // Unpinned deliberately. Clearing pinned the identity so suppression could be stored, and
        // on a tab whose directory had not resolved yet that identity is the weak `tab:<label>`
        // fallback. Leaving it pinned would keep the tab on a label shared by every other
        // un-resolved tab ("Local", "bash") for the rest of the session.
        content.putUserData(STYLE_KEY, null)
        content.putUserData(KEY_INDEX, null)
        restyle(content)
    }

    fun isSuppressed(content: Content): Boolean = settings.isSuppressed(styleKeyFor(content))

    /**
     * Whether the tab has already been torn down.
     *
     * Mutating a disposed [Content] is not merely wasted work: once the session is gone
     * `styleKeyFor` falls back to the weak `tab:<label>` identity, so the write persists an
     * override under a key shared with every other unresolved tab, and a later tab can inherit it.
     * Reachable because the colour picker commits continuously while it is open, and because a
     * context menu outlives the click that opened it.
     *
     * Deliberately *not* [isGone], which asks whether the content has left every content manager —
     * a different question that is also true of a live but unattached tab, and using it here turned
     * every style mutation into a silent no-op.
     *
     * `Disposer.isDisposed` is deprecated in favour of explicit lifecycle tracking, but it is the
     * only accurate answer available for a `Content` we did not create: the disposal tree is where
     * that state actually lives.
     */
    @Suppress("DEPRECATION")
    private fun isTornDown(content: Content): Boolean = Disposer.isDisposed(content)



    /**
     * The user's own override for this tab, ignoring anything a rule contributes.
     *
     * Resolves the key once: this runs for every item in the context menu, and each resolution is
     * a reflective tab lookup plus a content-manager walk.
     */
    fun manualStyleFor(content: Content): TabStyle {
        val key = styleKeyFor(content)
        if (settings.isSuppressed(key)) return TabStyle.EMPTY
        return settings.overrideFor(key) ?: TabStyle.EMPTY
    }

    /** What the tab is actually showing, whatever produced it. Used for menu hints. */
    fun effectiveStyleFor(content: Content): TabStyle =
        TabStyleApplier.appliedStyle(content) ?: TabStyle.EMPTY

    private fun onEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeLater(block, project.disposed)
    }

    companion object {
        const val TERMINAL_TOOL_WINDOW_ID = "Terminal"

        private val LOG = logger<TabStyleService>()

        private const val SUBSCRIBE_ATTEMPTS = 5
        private const val SUBSCRIBE_BASE_DELAY_MS = 120

        private val STYLE_KEY = Key.create<String>("TabCue.key")

        /** A tab's pinned auto-assigned colour, so it stays put for the tab's lifetime. */
        private val AUTO_COLOR = Key.create<String>("TabCue.autoColor")

        /** A tab's pinned identity discriminator, tied to the base it was computed for. */
        private val KEY_INDEX = Key.create<PinnedIndex>("TabCue.keyIndex")

        fun getInstance(project: Project): TabStyleService = project.service()
    }
}
