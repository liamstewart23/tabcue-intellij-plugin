package ca.liamstewart.tabcue.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import ca.liamstewart.tabcue.model.MatchField
import ca.liamstewart.tabcue.model.StarterRules
import ca.liamstewart.tabcue.model.StyleRule
import ca.liamstewart.tabcue.model.TabStyle

/**
 * Serialised form of a rule. The IntelliJ XML serialiser needs a no-arg constructor and mutable
 * properties, which is why the domain types in `model` are not persisted directly.
 */
@Tag("rule")
class RuleState {
    var field: String = MatchField.TAB_TITLE.name
    var pattern: String = ""
    var colorId: String? = null
    var iconId: String? = null
    var emoji: String? = null
    var textColorId: String? = null
    var tintBackground: Boolean = false
    var enabled: Boolean = true

    /**
     * Only meaningful on a rule stashed in `unknownRules`: the index it held in `rules` before this
     * build set it aside, so it can be restored to that position rather than appended. `-1` means
     * "unknown", which restores to the end.
     */
    var order: Int = -1
}

/** A style the user pinned to a specific tab by hand. */
@Tag("override")
class OverrideState {
    var key: String = ""
    var colorId: String? = null
    var iconId: String? = null
    var emoji: String? = null
    var textColorId: String? = null
    var tintBackground: Boolean = false

    /**
     * "Explicitly unstyled", which is not the same as having no entry.
     *
     * Without this there is no way to say "leave this tab alone": clearing a style deletes the
     * override, the next restyle re-matches the rules, and the colour comes straight back.
     */
    var suppressed: Boolean = false
}

/** Shared configuration: safe to commit, because the user wrote all of it deliberately. */
class TabStyleState {
    /** Give every new tab the next palette colour when no rule or override matches. */
    var autoAssignColors: Boolean = false

    /**
     * Kill switch for the unsupported per-session background tint.
     *
     * Defaults to on because the per-tab toggle is already the explicit opt-in; this exists only to
     * disable the feature wholesale if a future IDE build breaks it. Deliberately renamed from
     * `enableBackgroundTint`, so a `false` persisted under the old name (when this doubled as an
     * opt-in and silently suppressed the per-tab toggle) is ignored rather than inherited.
     */
    var allowBackgroundTint: Boolean = true

    /** Draw the tab's colour as a dot icon, so the colour is visible in every tab state. */
    var showColorAsDot: Boolean = true

    /** How far the background tint is blended toward the tab colour, as a percentage. */
    var tintStrength: Int = TINT_STRENGTH_DEFAULT

    @get:XCollection(propertyElementName = "rules")
    var rules: MutableList<RuleState> = mutableListOf()

    /**
     * Whether the AI-agent starter rules have already been offered to this project.
     *
     * Separate from "the rule list is empty" so that deleting them sticks: without the flag, the
     * next project open would helpfully put them all back.
     */
    var seededStarterRules: Boolean = false

    /**
     * Rules whose `field` this build does not recognise, kept verbatim.
     *
     * Without this, opening the settings page on a file written by a newer version would silently
     * delete those rules on the next Apply.
     */
    @get:XCollection(propertyElementName = "unknownRules")
    var unknownRules: MutableList<RuleState> = mutableListOf()

    /**
     * Where per-tab overrides lived before they moved to the workspace file.
     *
     * Retained so styles set by an earlier build are migrated rather than silently dropped. The
     * element name matches what that build wrote. Emptied on first access.
     */
    @get:XCollection(propertyElementName = "overrides")
    var legacyOverrides: MutableList<OverrideState> = mutableListOf()
}

/** Per-tab overrides, kept out of the shared file. See [TabStyleOverrides]. */
class TabStyleOverridesState {
    @get:XCollection(propertyElementName = "overrides")
    var overrides: MutableList<OverrideState> = mutableListOf()
}

/**
 * Auto-generated per-tab styles, stored in the **workspace** file rather than the shared one.
 *
 * The keys are derived from working directories and shell-reported tab titles, so they contain
 * absolute paths and internal hostnames (`cwd:/Users/me/code/client-acme`,
 * `name:ssh prod-db-01.internal`). Nobody writes those deliberately, which makes them exactly the
 * thing you would not want to discover in a committed `.idea/` file. Rules stay in the shared file
 * because those the user authored on purpose and may want to share.
 */
@Service(Service.Level.PROJECT)
@State(name = "TerminalTabStyleOverrides", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TabStyleOverrides : PersistentStateComponent<TabStyleOverridesState> {

    private var state = TabStyleOverridesState()

    override fun getState(): TabStyleOverridesState =
        // A copy: the serialiser walks this on a background save thread while the EDT may be
        // mutating it, which is an unsynchronised concurrent modification.
        TabStyleOverridesState().also { copy ->
            copy.overrides = synchronized(this) { state.overrides.mapTo(mutableListOf()) { it.copy() } }
        }

    override fun loadState(loaded: TabStyleOverridesState) {
        synchronized(this) { state = loaded }
    }

    fun find(key: String): OverrideState? = synchronized(this) {
        state.overrides.firstOrNull { it.key == key }?.copy()
    }

    fun put(key: String, value: OverrideState?) {
        if (key.isBlank()) return
        synchronized(this) {
            state.overrides.removeAll { it.key == key }
            if (value != null) {
                state.overrides += value.also { it.key = key }
            }
        }
    }

    companion object {
        fun getInstance(project: Project): TabStyleOverrides = project.service()
    }
}

private fun RuleState.copy(): RuleState = RuleState().also {
    it.field = field
    it.pattern = pattern
    it.colorId = colorId
    it.iconId = iconId
    it.emoji = emoji
    it.textColorId = textColorId
    it.tintBackground = tintBackground
    it.enabled = enabled
    it.order = order
}

private fun OverrideState.copy(): OverrideState = OverrideState().also {
    it.key = key
    it.colorId = colorId
    it.iconId = iconId
    it.emoji = emoji
    it.textColorId = textColorId
    it.tintBackground = tintBackground
    it.suppressed = suppressed
}

@Service(Service.Level.PROJECT)
@State(name = "TerminalTabStyle", storages = [Storage("terminalTabStyle.xml")])
class TabStyleSettings(private val project: Project) : PersistentStateComponent<TabStyleState> {

    @Volatile
    private var state = TabStyleState()

    /** Rebuilt only when the rules change; [rules] is called on every terminal title change. */
    @Volatile
    private var cachedRules: List<StyleRule>? = null

    private val listeners = mutableListOf<() -> Unit>()

    @Volatile
    private var legacyMigrated = false

    /**
     * The override store, migrating anything left in the old shared-file location first.
     *
     * Done lazily rather than in `loadState`, which must not reach for other services.
     */
    private val overrides: TabStyleOverrides
        get() {
            val store = TabStyleOverrides.getInstance(project)
            if (!legacyMigrated) {
                legacyMigrated = true
                val legacy = state.legacyOverrides
                if (legacy.isNotEmpty()) {
                    state.legacyOverrides = mutableListOf()
                    legacy.filter { it.key.isNotBlank() }.forEach { store.put(it.key, it) }
                }
            }
            return store
        }

    // A copy, for the same reason TabStyleOverrides makes one: the serialiser walks this on a
    // background save thread while the EDT may be replacing the rule list from Apply.
    override fun getState(): TabStyleState = TabStyleState().also { copy ->
        val live = state
        copy.autoAssignColors = live.autoAssignColors
        copy.allowBackgroundTint = live.allowBackgroundTint
        copy.showColorAsDot = live.showColorAsDot
        copy.tintStrength = live.tintStrength
        copy.seededStarterRules = live.seededStarterRules
        copy.rules = live.rules.mapTo(mutableListOf()) { it.copy() }
        copy.unknownRules = live.unknownRules.mapTo(mutableListOf()) { it.copy() }
        copy.legacyOverrides = live.legacyOverrides.mapTo(mutableListOf()) { it.copy() }
    }

    override fun loadState(loaded: TabStyleState) {
        state = loaded
        // Merged back into the live list, otherwise preservation was one-way: they would sit in
        // `unknownRules` forever and a later build that understands their field would still ignore
        // them. `rules()` skips what it cannot parse, and `retainUnknownRules` re-stashes them on
        // the next save.
        if (state.unknownRules.isNotEmpty()) {
            // Re-inserted where they were, not appended. Rules are first-match-wins, so appending
            // silently demotes a rule that the newer build had ranked above the ones this build
            // understands. The style quietly changes rather than the rule being lost, which is
            // harder to notice than an outright deletion.
            state.unknownRules.sortedBy { it.order }.forEach { raw ->
                val at = raw.order.takeIf { it in 0..state.rules.size } ?: state.rules.size
                state.rules.add(at, raw)
            }
            state.unknownRules = mutableListOf()
        }
        seedStarterRulesIfUntouched()
        cachedRules = null
    }

    /**
     * Called instead of [loadState] when the project has no stored settings at all, a genuinely
     * fresh install, which is the main case the starter rules exist for.
     */
    override fun noStateLoaded() {
        seedStarterRulesIfUntouched()
        cachedRules = null
    }

    /**
     * Adds the agent starter rules, once, and only to a project that has no rules of its own.
     *
     * The flag is set even when nothing is added, so a project that already had rules is not
     * re-examined every time it opens. Reaches for no other service, which is what `loadState`
     * requires of anything it calls.
     */
    private fun seedStarterRulesIfUntouched() {
        if (state.seededStarterRules) return
        state.seededStarterRules = true
        if (state.rules.isEmpty()) setRules(StarterRules.agentRules())
    }

    /** The [parent] disposable is required so a dynamic plugin reload cannot double-register. */
    fun addChangeListener(parent: Disposable, listener: () -> Unit) {
        synchronized(listeners) { listeners += listener }
        Disposer.register(parent) { synchronized(listeners) { listeners -= listener } }
    }

    private fun fireChanged() {
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    var autoAssignColors: Boolean
        get() = state.autoAssignColors
        set(value) {
            state.autoAssignColors = value
        }

    var allowBackgroundTint: Boolean
        get() = state.allowBackgroundTint
        set(value) {
            state.allowBackgroundTint = value
        }

    var showColorAsDot: Boolean
        get() = state.showColorAsDot
        set(value) {
            state.showColorAsDot = value
        }

    /** Clamped on read so a hand-edited settings file cannot produce an unreadable terminal. */
    var tintStrength: Int
        get() = state.tintStrength.coerceIn(TINT_STRENGTH_MIN, TINT_STRENGTH_MAX)
        set(value) {
            state.tintStrength = value.coerceIn(TINT_STRENGTH_MIN, TINT_STRENGTH_MAX)
        }

    fun rules(): List<StyleRule> {
        cachedRules?.let { return it }
        val built = state.rules.mapNotNull { raw ->
            val field = runCatching { MatchField.valueOf(raw.field) }.getOrNull() ?: return@mapNotNull null
            StyleRule(
                field = field,
                pattern = decodeXmlSafe(raw.pattern).orEmpty(),
                style = TabStyle(
                    colorId = raw.colorId,
                    iconId = raw.iconId,
                    tintBackground = raw.tintBackground,
                    emoji = decodeXmlSafe(raw.emoji),
                    textColorId = raw.textColorId,
                ),
                enabled = raw.enabled,
            )
        }
        cachedRules = built
        return built
    }

    fun setRules(rules: List<StyleRule>) {
        state.rules = rules.mapTo(mutableListOf()) { rule ->
            RuleState().apply {
                field = rule.field.name
                pattern = encodeXmlSafe(rule.pattern).orEmpty()
                colorId = rule.style.colorId
                iconId = rule.style.iconId
                emoji = encodeXmlSafe(rule.style.emoji)
                textColorId = rule.style.textColorId
                tintBackground = rule.style.tintBackground
                enabled = rule.enabled
            }
        }
        cachedRules = null
    }

    /** Rules this build could not parse, preserved so Apply does not delete them. */
    fun retainUnknownRules() {
        val unknown = state.rules
            .mapIndexed { index, raw -> index to raw }
            .filter { (_, raw) -> runCatching { MatchField.valueOf(raw.field) }.isFailure }
            // The position is recorded here because this is the last point at which it is known:
            // setRules replaces state.rules with the table's contents immediately afterwards.
            .map { (index, raw) -> raw.also { it.order = index } }
        if (unknown.isNotEmpty()) {
            state.unknownRules = unknown.toMutableList()
        }
    }

    fun overrideFor(key: String): TabStyle? {
        val raw = overrides.find(encodeXmlSafe(key) ?: return null) ?: return null
        if (raw.suppressed) return TabStyle.EMPTY
        return TabStyle(
            colorId = raw.colorId,
            iconId = raw.iconId,
            tintBackground = raw.tintBackground,
            emoji = decodeXmlSafe(raw.emoji),
            textColorId = raw.textColorId,
        )
    }

    /** True when the user explicitly cleared this tab, which outranks rules and auto-assignment. */
    fun isSuppressed(key: String): Boolean {
        val encoded = encodeXmlSafe(key) ?: return false
        return overrides.find(encoded)?.suppressed == true
    }

    fun setOverride(key: String, style: TabStyle?) {
        val encoded = encodeXmlSafe(key) ?: return
        if (style == null || style.isEmpty) {
            overrides.put(encoded, null)
            return
        }
        overrides.put(
            encoded,
            OverrideState().apply {
                colorId = style.colorId
                iconId = style.iconId
                emoji = encodeXmlSafe(style.emoji)
                textColorId = style.textColorId
                tintBackground = style.tintBackground
            },
        )
    }

    /** Records "explicitly unstyled" so rules and auto-assignment stop applying to this tab. */
    fun suppress(key: String) {
        val encoded = encodeXmlSafe(key) ?: return
        overrides.put(encoded, OverrideState().apply { suppressed = true })
    }

    fun clearOverride(key: String) {
        overrides.put(encodeXmlSafe(key) ?: return, null)
    }

    /** Called by the Configurable after Apply so open tabs pick up new rules immediately. */
    fun notifyChanged() {
        cachedRules = null
        fireChanged()
    }

    companion object {
        fun getInstance(project: Project): TabStyleSettings = project.service()
    }
}

const val TINT_STRENGTH_MIN = 5
const val TINT_STRENGTH_MAX = 70

/**
 * The default blend, raised from the original 18%.
 *
 * At 18% the tint was technically present but hard to see, which made the feature read as broken.
 * The cap exists because past roughly 70% the terminal's own ANSI colours stop being legible.
 */
const val TINT_STRENGTH_DEFAULT = 40
