package ca.liamstewart.tabcue.model

/**
 * The rules a project starts with when it has none of its own.
 *
 * Telling AI agent sessions apart is the plugin's stated purpose, and a feature that only works
 * after you have found the settings page and written four rules by hand mostly does not work. So
 * these ship enabled, and the first terminal you start an agent in is already distinct.
 *
 * Matching is on the tab title, which the terminal keeps in sync with the shell's OSC title — so
 * these fire both for a session the IDE launched (the tab is named after the agent) and for one
 * where you typed the command yourself. [RuleMatcher] is case-insensitive and implicitly
 * substring, so the bare word is the whole pattern: `claude` matches "Claude Code" and "claude"
 * alike, and needs no wildcards.
 *
 * Each agent gets a different colour *and* a different emoji rather than sharing a house style,
 * because the situation these exist for is three agent tabs open at once.
 *
 * Seeded only into a project with an empty rule list, and only once — see
 * `TabStyleState.seededStarterRules`. Deleting them is meant to stick.
 */
object StarterRules {

    fun agentRules(): List<StyleRule> = listOf(
        rule("claude", colorId = "purple", emoji = "🤖"),
        rule("codex", colorId = "teal", emoji = "🧠"),
        rule("junie", colorId = "orange", emoji = "🦉"),
        rule("aider", colorId = "blue", emoji = "🛠️"),
    )

    private fun rule(pattern: String, colorId: String, emoji: String): StyleRule =
        StyleRule(
            field = MatchField.TAB_TITLE,
            pattern = pattern,
            style = TabStyle(colorId = colorId, emoji = emoji),
        )
}
