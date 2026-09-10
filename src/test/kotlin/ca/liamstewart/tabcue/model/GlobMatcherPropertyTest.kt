package ca.liamstewart.tabcue.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Cross-checks the hand-written two-pointer glob scan against a regex reference.
 *
 * The production matcher deliberately avoids a regex (catastrophic backtracking on the EDT), but a
 * regex is a perfectly good *oracle* for short inputs. Hand-written glob matchers classically go
 * wrong on trailing `?`, runs of `*`, and patterns longer than the text, so this brute-forces a
 * small alphabet rather than trusting a handful of chosen examples.
 */
class GlobMatcherPropertyTest {

    /** Mirrors `RuleMatcher.matches` semantics: substring, case-insensitive, `/`-normalised. */
    private fun reference(pattern: String, value: String): Boolean {
        val normalisedPattern = pattern.replace('\\', '/').lowercase()
        val normalisedValue = value.replace('\\', '/').lowercase()
        val regex = buildString {
            for (ch in normalisedPattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
        }
        return Regex(regex, RegexOption.DOT_MATCHES_ALL).containsMatchIn(normalisedValue)
    }

    @Test
    fun `matches agrees with a regex oracle across an exhaustive small space`() {
        val alphabet = listOf("", "a", "b", "*", "?")
        val patterns = mutableListOf<String>()
        // Every pattern up to length 4 over {a, b, *, ?}.
        for (a in alphabet) for (b in alphabet) for (c in alphabet) for (d in alphabet) {
            patterns += a + b + c + d
        }
        val texts = listOf("", "a", "b", "ab", "ba", "aa", "bb", "aab", "aba", "abab", "bbaa")

        var compared = 0
        for (pattern in patterns.distinct()) {
            if (pattern.isEmpty()) continue
            for (text in texts) {
                if (text.isEmpty()) continue
                assertEquals(
                    "pattern='$pattern' text='$text'",
                    reference(pattern, text),
                    RuleMatcher.matches(pattern, text),
                )
                compared++
            }
        }
        assertTrue("the sweep must actually compare something", compared > 2000)
    }

    @Test
    fun `matches agrees with the oracle on random longer inputs`() {
        val random = Random(20260908)
        repeat(4000) {
            val pattern = (1..random.nextInt(1, 9))
                .map { "ab*?/".random(random) }
                .joinToString("")
            val text = (1..random.nextInt(1, 13))
                .map { "ab/".random(random) }
                .joinToString("")
            assertEquals(
                "pattern='$pattern' text='$text'",
                reference(pattern, text),
                RuleMatcher.matches(pattern, text),
            )
        }
    }

    @Test
    fun `edge shapes behave`() {
        assertTrue(RuleMatcher.matches("*", "anything"))
        assertTrue(RuleMatcher.matches("***", "anything"))
        assertTrue(RuleMatcher.matches("a", "banana"))
        // A trailing ? must consume exactly one character, which is the classic off-by-one.
        assertTrue(RuleMatcher.matches("a?", "ab"))
        assertTrue(RuleMatcher.matches("ba?", "bab"))
        assertFalse(RuleMatcher.matches("longerthanthetext", "short"))
        // Consecutive stars must not multiply backtracking or change the result.
        assertTrue(RuleMatcher.matches("a**b", "axxxb"))
        assertEquals(RuleMatcher.matches("a*b", "axxxb"), RuleMatcher.matches("a**b", "axxxb"))
        // An empty value never matches, even against a lone star.
        assertFalse(RuleMatcher.matches("*", ""))
        assertFalse(RuleMatcher.matches("*", null))
    }
}
