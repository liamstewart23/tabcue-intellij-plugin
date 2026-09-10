package ca.liamstewart.tabcue.settings

/**
 * Escapes text so it survives a round trip through the settings XML.
 *
 * Two classes of character do not: characters above the BMP (most emoji) are surrogate pairs in a
 * Java `String`, and a per-`char` XML validity check sees two lone surrogates and strips both —
 * the value comes back as an empty string with no error. Control characters are outright illegal
 * in XML 1.0 and can fail the write for the whole file, and a shell can put one in an OSC title,
 * which ends up in a style key.
 *
 * Anything problematic becomes `\\uXXXX`; a literal backslash is doubled. Ordinary text — which is
 * almost all of it, including rule patterns and directory paths — passes through untouched, so the
 * settings file stays readable.
 */
internal fun encodeXmlSafe(value: String?): String? {
    val text = value ?: return null
    if (text.isEmpty()) return null
    if (text.none { needsEscaping(it) }) return text

    val out = StringBuilder(text.length + 8)
    for (ch in text) {
        when {
            ch == '\\' -> out.append("\\\\")
            needsEscaping(ch) -> out.append("\\u").append("%04X".format(ch.code))
            else -> out.append(ch)
        }
    }
    return out.toString()
}

internal fun decodeXmlSafe(value: String?): String? {
    val text = value ?: return null
    if (text.isEmpty()) return null
    if (!text.contains('\\')) return text

    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch != '\\' || i + 1 >= text.length) {
            out.append(ch)
            i++
            continue
        }
        val next = text[i + 1]
        when {
            next == '\\' -> {
                out.append('\\')
                i += 2
            }
            next == 'u' && i + 6 <= text.length -> {
                val hex = text.substring(i + 2, i + 6)
                // Every one of the four must be a hex *digit*. `toIntOrNull(16)` also accepts a
                // sign, so "\u-001" parsed as -1 and `(-1).toChar()` silently produced U+FFFF
                // instead of leaving the text as the literal it is.
                val code = hex.takeIf { it.all(::isHexDigit) }?.toIntOrNull(16)
                if (code == null) {
                    out.append(ch)
                    i++
                } else {
                    out.append(code.toChar())
                    i += 6
                }
            }
            else -> {
                out.append(ch)
                i++
            }
        }
    }
    return out.toString()
}

/**
 * A backslash is escaped so decoding is unambiguous; surrogates and control characters are escaped
 * because the XML layer mangles or rejects them. Tab, newline and carriage return are legal XML but
 * have no business in a tab title or pattern, so they are escaped too rather than silently kept.
 *
 * XML *non-characters* are escaped for the same reason as surrogates: they are perfectly legal in a
 * Java `String` and illegal in XML 1.0, so letting one through — a shell can put anything in an OSC
 * title, and that title becomes part of a style key — risks the whole settings file failing to
 * write, which is precisely the failure this file exists to prevent.
 */
private fun needsEscaping(ch: Char): Boolean =
    ch == '\\' || ch.isSurrogate() || ch.code < 0x20 || ch.code == 0x7F || isXmlNonCharacter(ch)

private fun isXmlNonCharacter(ch: Char): Boolean =
    ch.code == 0xFFFE || ch.code == 0xFFFF || ch.code in 0xFDD0..0xFDEF

private fun isHexDigit(ch: Char): Boolean =
    ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
