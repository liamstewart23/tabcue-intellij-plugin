package ca.liamstewart.tabcue.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule matching is the only substantial logic in the plugin that does not need a running IDE, so it
 * is the part worth unit-testing.
 */
class RuleMatcherTest {

    @Test
    fun `bare pattern matches as a substring`() {
        assertTrue(RuleMatcher.matches("artisan serve", "php artisan serve --port=8000"))
        assertFalse(RuleMatcher.matches("artisan serve", "npm run dev"))
    }

    @Test
    fun `matching ignores case`() {
        assertTrue(RuleMatcher.matches("SSH PROD", "ssh prod-web-01"))
    }

    @Test
    fun `star is a wildcard`() {
        assertTrue(RuleMatcher.matches("ssh *prod", "ssh admin@prod"))
        assertTrue(RuleMatcher.matches("*/api/*", "/Users/liam/code/api/src"))
        assertFalse(RuleMatcher.matches("*/api/*", "/Users/liam/code/web/src"))
    }

    @Test
    fun `question mark matches exactly one character`() {
        assertTrue(RuleMatcher.matches("prod-?", "prod-1"))
        assertFalse(RuleMatcher.matches("prod-?", "prod-"))
    }

    @Test
    fun `regex metacharacters in a pattern are literal`() {
        // A naive glob-to-regex conversion would treat these as regex syntax and either throw or
        // match far too much.
        assertTrue(RuleMatcher.matches("v1.2+build", "release v1.2+build ready"))
        assertFalse(RuleMatcher.matches("v1.2+build", "release v1x2build ready"))
        assertTrue(RuleMatcher.matches("cost(usd)", "total cost(usd) high"))
    }

    @Test
    fun `null and empty values never match`() {
        assertFalse(RuleMatcher.matches("anything", null))
        assertFalse(RuleMatcher.matches("anything", ""))
    }

    @Test
    fun `first enabled matching rule wins`() {
        val rules = listOf(
            rule(MatchField.TAB_TITLE, "npm", "green"),
            rule(MatchField.TAB_TITLE, "*", "grey"),
        )
        val match = RuleMatcher.firstMatch(rules, TabFacts(title = "npm run dev"))
        assertEquals("green", match?.style?.colorId)
    }

    @Test
    fun `disabled rules are skipped in favour of later ones`() {
        val rules = listOf(
            rule(MatchField.TAB_TITLE, "npm", "green", enabled = false),
            rule(MatchField.TAB_TITLE, "npm", "blue"),
        )
        assertEquals("blue", RuleMatcher.firstMatch(rules, TabFacts(title = "npm run dev"))?.style?.colorId)
    }

    @Test
    fun `blank patterns are ignored rather than matching everything`() {
        val rules = listOf(rule(MatchField.TAB_TITLE, "   ", "red"))
        assertNull(RuleMatcher.firstMatch(rules, TabFacts(title = "anything")))
    }

    @Test
    fun `rules only consult their own field`() {
        val rules = listOf(rule(MatchField.WORKING_DIRECTORY, "api", "blue"))
        // "api" appears in the title, but the rule matches on working directory only.
        assertNull(RuleMatcher.firstMatch(rules, TabFacts(title = "api server", workingDirectory = "/code/web")))
        assertEquals(
            "blue",
            RuleMatcher.firstMatch(rules, TabFacts(title = "zsh", workingDirectory = "/code/api"))?.style?.colorId,
        )
    }

    @Test
    fun `absurdly long patterns are rejected rather than run`() {
        val pathological = "*a".repeat(150) + "b"
        assertTrue(pathological.length > RuleMatcher.MAX_PATTERN_LENGTH)
        // Nested wildcards like this are what make regex backtracking blow up; refusing to run
        // them keeps a hand-edited settings file from hanging the EDT on every title change.
        assertFalse(RuleMatcher.matches(pathological, "a".repeat(60)))
    }

    @Test(timeout = 3000)
    fun `a wildcard heavy pattern under the cap still completes instantly`() {
        // 20 nested wildcards, 41 characters, comfortably under MAX_PATTERN_LENGTH, so the length
        // guard does not save us here. Compiled to a regex this is the textbook catastrophic
        // backtracking shape and would hang the EDT; the two-pointer scan is linear.
        val pattern = "*a".repeat(20) + "b"
        assertTrue(pattern.length < RuleMatcher.MAX_PATTERN_LENGTH)
        assertFalse(RuleMatcher.matches(pattern, "a".repeat(200)))
    }

    @Test
    fun `working directory patterns match windows separators too`() {
        // The hint tells people to use forward slashes, so a backslash path must still match.
        assertTrue(RuleMatcher.matches("*/api/*", "C:\\code\\api\\src"))
        assertTrue(RuleMatcher.matches("*/api/*", "/Users/liam/code/api/src"))
    }

    @Test
    fun `a rule with no visual effect is skipped rather than swallowing later rules`() {
        val rules = listOf(
            StyleRule(MatchField.TAB_TITLE, "npm", TabStyle(), enabled = true),
            rule(MatchField.TAB_TITLE, "npm", "green"),
        )
        // The editor dialog blocks creating an empty style, but a hand-edited or older settings
        // file can contain one, and it must not shadow everything after it.
        assertEquals("green", RuleMatcher.firstMatch(rules, TabFacts(title = "npm run dev"))?.style?.colorId)
    }

    @Test
    fun `no rules means no match`() {
        assertNull(RuleMatcher.firstMatch(emptyList(), TabFacts(title = "zsh")))
    }

    private fun rule(
        field: MatchField,
        pattern: String,
        colorId: String,
        enabled: Boolean = true,
    ) = StyleRule(field, pattern, TabStyle(colorId = colorId), enabled)
}
