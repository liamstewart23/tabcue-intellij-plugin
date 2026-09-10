package ca.liamstewart.tabcue.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the rules a fresh install ships with.
 *
 * These are the only rules most users will ever see, they are written by hand, and every way of
 * getting them wrong fails silently rather than loudly: an unresolvable colour id degrades to "no
 * colour", and a rule whose style is empty is skipped by [RuleMatcher] — which also swallows every
 * rule after it, since matching is first-match-wins.
 */
class StarterRulesTest {

    private val rules = StarterRules.agentRules()

    @Test
    fun `there are rules and every one of them can actually apply something`() {
        assertTrue("expected starter rules", rules.isNotEmpty())
        rules.forEach { rule ->
            assertFalse(
                "an empty style is skipped by the matcher, and hides every rule after it: $rule",
                rule.style.isEmpty,
            )
            assertTrue("pattern should not be blank", rule.pattern.isNotBlank())
            assertTrue(
                "pattern must be within the matcher's cap",
                rule.pattern.length <= RuleMatcher.MAX_PATTERN_LENGTH,
            )
            assertTrue("pattern should be lower case so it reads as case-insensitive", rule.pattern == rule.pattern.lowercase())
            assertTrue("starter rules must ship enabled or they do nothing", rule.enabled)
        }
    }

    @Test
    fun `every colour resolves against the palette`() {
        rules.forEach { rule ->
            assertNotNull(
                "unknown colour ids silently become no colour: ${rule.style.colorId}",
                StylePalette.color(rule.style.colorId),
            )
        }
    }

    @Test
    fun `each agent is visually distinct from the others`() {
        // The entire point of shipping these is three agent tabs open at once, so a duplicated
        // colour or emoji defeats the feature rather than merely looking untidy.
        fun <T> distinct(what: String, values: List<T>) =
            assertEquals("starter rules should not share a $what: $values", values.size, values.toSet().size)

        distinct("pattern", rules.map { it.pattern })
        distinct("colour", rules.mapNotNull { it.style.colorId })
        distinct("emoji", rules.mapNotNull { it.style.emoji })
    }

    @Test
    fun `emoji are stored exactly as they will be drawn`() {
        // Emoji are normalised to a single grapheme cluster on the way in from the UI. A starter
        // rule bypasses that path, so a glyph that normalises to something else would be stored
        // one way and painted another.
        rules.forEach { rule ->
            val emoji = rule.style.emoji
            assertNotNull("each agent rule should carry an emoji", emoji)
            assertEquals("emoji should already be normalised", emoji, normaliseEmoji(emoji))
        }
    }

    @Test
    fun `the patterns match what the agents actually put in a tab title`() {
        // Matching is case-insensitive and implicitly substring, which is what lets the bare word
        // cover both an IDE-launched session (tab named "Claude Code") and a hand-typed one.
        assertTrue(RuleMatcher.matches("claude", "Claude Code"))
        assertTrue(RuleMatcher.matches("claude", "claude"))
        assertTrue(RuleMatcher.matches("codex", "codex --model gpt"))
        assertFalse("an unrelated shell must not match", RuleMatcher.matches("claude", "zsh"))
    }
}
