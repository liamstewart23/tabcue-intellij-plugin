package ca.liamstewart.tabcue.settings

import com.intellij.util.xmlb.XmlSerializer
import ca.liamstewart.tabcue.model.MatchField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trips the persisted state through the IDE's own serialiser.
 *
 * The state classes are hand-written for the XML serialiser (no-arg constructors, mutable
 * properties), and a mistake there fails silently: the user's rules simply vanish on restart. This
 * is cheap insurance against exactly that.
 */
class TabStyleStateSerializationTest {

    private fun roundTrip(state: TabStyleState): TabStyleState {
        val element = XmlSerializer.serialize(state)
        return XmlSerializer.deserialize(element, TabStyleState::class.java)
    }

    private fun roundTrip(state: TabStyleOverridesState): TabStyleOverridesState {
        val element = XmlSerializer.serialize(state)
        return XmlSerializer.deserialize(element, TabStyleOverridesState::class.java)
    }

    @Test
    fun `rules survive a round trip including the emoji field`() {
        val state = TabStyleState().apply {
            rules.add(
                RuleState().apply {
                    field = MatchField.WORKING_DIRECTORY.name
                    pattern = "*/api/*"
                    colorId = "blue"
                    iconId = "run"
                    emoji = encodeXmlSafe("🚀")
                    tintBackground = true
                    enabled = false
                }
            )
        }

        val restored = roundTrip(state)

        assertEquals(1, restored.rules.size)
        val rule = restored.rules.single()
        assertEquals(MatchField.WORKING_DIRECTORY.name, rule.field)
        assertEquals("*/api/*", rule.pattern)
        assertEquals("blue", rule.colorId)
        assertEquals("run", rule.iconId)
        assertEquals("🚀", decodeXmlSafe(rule.emoji))
        assertTrue(rule.tintBackground)
        // A false on a Boolean that defaults to true is the classic thing a serialiser drops.
        assertEquals(false, rule.enabled)
    }

    @Test
    fun `manual overrides survive a round trip`() {
        val state = TabStyleOverridesState().apply {
            overrides.add(
                OverrideState().apply {
                    key = "cwd:/Users/someone/code/api"
                    colorId = "red"
                    emoji = encodeXmlSafe("🔥")
                    tintBackground = true
                }
            )
        }

        val restored = roundTrip(state)

        val override = restored.overrides.single()
        assertEquals("cwd:/Users/someone/code/api", override.key)
        assertEquals("red", override.colorId)
        assertEquals("🔥", decodeXmlSafe(override.emoji))
        assertTrue(override.tintBackground)
        assertNull(override.iconId)
    }

    @Test
    fun `top level flags survive a round trip`() {
        val state = TabStyleState().apply {
            autoAssignColors = true
            // Both default to true, so a persisted false is the case worth pinning.
            allowBackgroundTint = false
            showColorAsDot = false
        }

        val restored = roundTrip(state)

        assertTrue(restored.autoAssignColors)
        assertEquals(false, restored.allowBackgroundTint)
        assertEquals(false, restored.showColorAsDot)
    }

    @Test
    fun `non-BMP emoji survive XML, which raw surrogate pairs do not`() {
        // 🚀 is U+1F680: a surrogate pair in a Java String. Stored raw, the XML layer strips both
        // halves and hands back an empty string, silently losing the user's emoji on restart.
        val raw = "🚀"
        val state = TabStyleOverridesState().apply {
            overrides.add(OverrideState().apply { key = "k"; emoji = encodeXmlSafe(raw) })
        }

        val restored = roundTrip(state)

        assertEquals(raw, decodeXmlSafe(restored.overrides.single().emoji))
    }

    @Test
    fun `an empty state round trips to defaults`() {
        val restored = roundTrip(TabStyleState())

        assertTrue(restored.rules.isEmpty())
        assertEquals(false, restored.autoAssignColors)
        assertTrue(restored.allowBackgroundTint)
        assertTrue(restored.showColorAsDot)
    }

    @Test
    fun `the explicitly unstyled marker survives a round trip`() {
        val state = TabStyleOverridesState().apply {
            overrides.add(OverrideState().apply { key = "cwd:/x"; suppressed = true })
        }

        // If this were lost, a tab the user had cleared would be repainted by the rules on the
        // next restart.
        assertTrue(roundTrip(state).overrides.single().suppressed)
    }

    @Test
    fun `overrides written by the previous build are still readable for migration`() {
        // 0.1.0 wrote per-tab overrides into the shared file under an <overrides> element. They
        // must still deserialise, or upgrading would silently discard every style already set.
        val state = TabStyleState().apply {
            legacyOverrides.add(OverrideState().apply { key = "cwd:/x"; colorId = "red" })
        }

        val restored = roundTrip(state)

        assertEquals(1, restored.legacyOverrides.size)
        assertEquals("red", restored.legacyOverrides.single().colorId)
    }

    @Test
    fun `the order recorded for a stashed unknown rule survives a round trip`() {
        // The position is what makes a preserved rule come back at the right priority. If it does
        // not serialise, the rule is preserved but silently demoted to last.
        val state = TabStyleState().apply {
            unknownRules.add(
                RuleState().apply {
                    field = "SOME_FIELD_A_LATER_VERSION_ADDED"
                    pattern = "ssh prod*"
                    colorId = "red"
                    order = 0
                }
            )
        }

        val restored = roundTrip(state)

        assertEquals(1, restored.unknownRules.size)
        assertEquals(0, restored.unknownRules.single().order)
    }

    @Test
    fun `a rule this build cannot parse keeps its priority across a save and reload`() {
        // The scenario: a newer build wrote a rule using a MatchField this build has never heard
        // of, and ranked it first. Opening the settings page and pressing Apply must not quietly
        // move it behind the rules this build does understand -- rules are first-match-wins, so
        // that changes which style a tab gets without deleting anything.
        val fromNewerBuild = TabStyleState().apply {
            rules.add(RuleState().apply { field = "SOME_FIELD_A_LATER_VERSION_ADDED"; pattern = "prod" })
            rules.add(RuleState().apply { field = MatchField.TAB_TITLE.name; pattern = "*" })
        }

        // What this build does on Apply: stash what it cannot parse, then replace `rules` with the
        // table's contents, which hold only the rules it could show.
        val unknown = fromNewerBuild.rules
            .mapIndexed { index, raw -> index to raw }
            .filter { (_, raw) -> runCatching { MatchField.valueOf(raw.field) }.isFailure }
            .map { (index, raw) -> raw.also { it.order = index } }
        val saved = TabStyleState().apply {
            rules.add(RuleState().apply { field = MatchField.TAB_TITLE.name; pattern = "*" })
            unknownRules.addAll(unknown)
        }

        val reloaded = roundTrip(saved)
        // The merge loadState performs.
        reloaded.unknownRules.sortedBy { it.order }.forEach { raw ->
            val at = raw.order.takeIf { it in 0..reloaded.rules.size } ?: reloaded.rules.size
            reloaded.rules.add(at, raw)
        }

        assertEquals(2, reloaded.rules.size)
        assertEquals(
            "the unparseable rule must return to the front, not the back",
            "SOME_FIELD_A_LATER_VERSION_ADDED",
            reloaded.rules.first().field,
        )
    }

    @Test
    fun `the tab label colour survives a round trip on both rules and overrides`() {
        // Added after the field itself: a new property on a hand-written state class is exactly
        // the kind of thing that compiles, runs, and silently drops the value on restart.
        val rules = roundTrip(
            TabStyleState().apply {
                rules.add(
                    RuleState().apply {
                        field = MatchField.TAB_TITLE.name
                        pattern = "prod"
                        textColorId = "white"
                    },
                )
            },
        )
        assertEquals("white", rules.rules.single().textColorId)

        val overrides = roundTrip(
            TabStyleOverridesState().apply {
                overrides.add(
                    OverrideState().apply {
                        key = "tab"
                        // A custom colour, since that is the form the picker produces.
                        textColorId = "#FF8800"
                    },
                )
            },
        )
        assertEquals("#FF8800", overrides.overrides.single().textColorId)
    }

    @Test
    fun `the starter-rules flag survives a round trip so they are not re-added`() {
        // Without this flag persisting, deleting the shipped agent rules would be undone the next
        // time the project opened, which is the most annoying possible behaviour.
        val state = TabStyleState().apply { seededStarterRules = true }
        assertTrue(roundTrip(state).seededStarterRules)
    }
}
