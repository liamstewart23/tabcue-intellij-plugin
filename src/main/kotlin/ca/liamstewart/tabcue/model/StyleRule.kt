package ca.liamstewart.tabcue.model

/**
 * What a rule matches against.
 *
 * There is deliberately no "shell command" field. The startup command is only reachable through
 * `TerminalView.startupOptionsDeferred`, a coroutine `Deferred` that has usually not completed when
 * a tab first appears. [TAB_TITLE] covers the same ground more usefully anyway: the terminal keeps
 * the tab title in sync with the shell's OSC 0/2 title, so a title rule matches whatever is
 * *currently running*, not merely what the tab was launched with.
 */
enum class MatchField(val displayName: String, val hint: String) {
    TAB_TITLE(
        "Tab title",
        "Follows the shell's reported title, so this matches the command currently running",
    ),
    WORKING_DIRECTORY(
        "Working directory",
        "The shell's current directory. Use forward slashes; back slashes are matched too",
    ),
}

/** The facts about a tab that rules are evaluated against. */
data class TabFacts(
    val title: String? = null,
    val workingDirectory: String? = null,
) {
    fun valueFor(field: MatchField): String? = when (field) {
        MatchField.TAB_TITLE -> title
        MatchField.WORKING_DIRECTORY -> workingDirectory
    }
}

data class StyleRule(
    val field: MatchField,
    val pattern: String,
    val style: TabStyle,
    val enabled: Boolean = true,
)

/**
 * Glob matching (`*` and `?`), case-insensitive, implicitly substring.
 *
 * Matched with a two-pointer scan rather than a compiled regex. Translating `*` to `.*` produces
 * the classic catastrophic-backtracking shape — `*a*a*a*a*a*a` against a long non-matching string
 * blows up exponentially — and this runs on the EDT against a shell-reported title, which a remote
 * host can influence. The scan below has no such cliff, and it needs no pattern cache.
 */
object RuleMatcher {

    /**
     * A sanity bound, enforced here and not only in the editor dialog: rules also arrive from a
     * hand-edited settings file or a VCS merge.
     */
    const val MAX_PATTERN_LENGTH = 200

    fun firstMatch(rules: List<StyleRule>, facts: TabFacts): StyleRule? =
        rules.firstOrNull { rule ->
            rule.enabled &&
                rule.pattern.isNotBlank() &&
                // A rule with nothing to apply would otherwise match and then silently swallow
                // every rule after it. The editor blocks creating one, but older or hand-edited
                // settings can still contain one.
                !rule.style.isEmpty &&
                matches(rule.pattern, facts.valueFor(rule.field))
        }

    /**
     * Long enough for any real path or title; short enough that a hostile one cannot stall the EDT.
     *
     * Capping the pattern alone was not sufficient: the *value* is a shell-reported title, which a
     * remote host over ssh can make arbitrarily long, and matching runs on the UI thread once per
     * rule per shell prompt.
     */
    const val MAX_VALUE_LENGTH = 4096

    fun matches(pattern: String, value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        if (pattern.length > MAX_PATTERN_LENGTH) return false
        if (value.length > MAX_VALUE_LENGTH) return matches(pattern, value.take(MAX_VALUE_LENGTH))
        // Substring semantics are exactly a pattern wrapped in stars, which keeps the matcher
        // below a plain full-string glob with no special cases.
        return globMatches("*" + normalise(pattern) + "*", normalise(value))
    }

    /**
     * Lower-cased, with separators unified so a wildcard path pattern written with forward slashes
     * also matches a Windows path such as `C:\code\api`.
     */
    private fun normalise(text: String): String = text.replace('\\', '/').lowercase()

    /**
     * The standard glob scan: walk both strings, remembering the most recent `*` as the single
     * backtrack point.
     *
     * Worst case is O(pattern x text) with no exponential path, which is the whole reason for not
     * compiling this to a regex.
     */
    private fun globMatches(pattern: String, text: String): Boolean {
        var p = 0
        var t = 0
        var starPattern = -1
        var starText = 0

        while (t < text.length) {
            if (p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t])) {
                p++
                t++
            } else if (p < pattern.length && pattern[p] == '*') {
                starPattern = p
                starText = t
                p++
            } else if (starPattern >= 0) {
                // Give the last star one more character and resume from just after it.
                p = starPattern + 1
                t = ++starText
            } else {
                return false
            }
        }

        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }
}
