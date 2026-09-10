package ca.liamstewart.tabcue.model

/**
 * The rules a project starts with when it has none of its own.
 *
 * Matching is case-insensitive and implicitly substring, so the bare word is the whole pattern:
 * `claude` covers both "Claude Code" (a session the IDE launched) and "claude" (one you typed).
 * Each agent gets its own colour and its own emoji, since the case these exist for is several
 * agent tabs open at once.
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
