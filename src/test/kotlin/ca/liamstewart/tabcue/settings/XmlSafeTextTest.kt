package ca.liamstewart.tabcue.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings XML mangles two classes of character, and both reach it through user-visible fields.
 *
 * Above-BMP characters are surrogate pairs in a Java `String`, and a per-`char` validity check
 * strips each half as a lone surrogate, so the value returns empty with no error. Control characters
 * are illegal in XML 1.0 outright and a shell can emit one inside an OSC title, which ends up in a
 * style key. This affects emoji, rule patterns and tab-identity keys alike.
 */
class XmlSafeTextTest {

    private fun roundTrip(value: String?) = decodeXmlSafe(encodeXmlSafe(value))

    @Test
    fun `ordinary text is stored verbatim so the file stays readable`() {
        assertEquals("cwd:/Users/liam/code/api", encodeXmlSafe("cwd:/Users/liam/code/api"))
        assertEquals("*/api/*", encodeXmlSafe("*/api/*"))
        assertEquals("cwd:/Users/liam/code/api", roundTrip("cwd:/Users/liam/code/api"))
    }

    @Test
    fun `above BMP characters survive`() {
        assertEquals("🚀", roundTrip("🚀"))
        assertEquals("name:🚀 api", roundTrip("name:🚀 api"))
        // Nothing above the BMP may remain literally, or the XML layer eats it.
        assertTrue(encodeXmlSafe("🚀")!!.all { it.code < 0x80 })
    }

    @Test
    fun `zero width joiner sequences survive`() {
        val technologist = "👩‍💻"
        assertEquals(technologist, roundTrip(technologist))
    }

    @Test
    fun `control characters from a shell title survive`() {
        // A stray control character in an OSC title would otherwise make the whole file unwritable.
        assertEquals("tab:a\u0001b", roundTrip("tab:a\u0001b"))
        assertTrue(encodeXmlSafe("tab:a\u0001b")!!.none { it.code < 0x20 })
    }

    @Test
    fun `a literal backslash is not confused with an escape`() {
        assertEquals("C:\\code\\api", roundTrip("C:\\code\\api"))
        assertEquals("\\u1234", roundTrip("\\u1234"))
        assertEquals("\\\\", roundTrip("\\\\"))
    }

    @Test
    fun `blank and null collapse to null`() {
        assertNull(encodeXmlSafe(null))
        assertNull(encodeXmlSafe(""))
        assertNull(decodeXmlSafe(null))
        assertNull(decodeXmlSafe(""))
    }

    @Test
    fun `a signed hex escape is left as literal text rather than parsed`() {
        // toIntOrNull(16) accepts a sign, so "\\u-001" parsed as -1 and (-1).toChar() produced
        // U+FFFF. Only four hex digits may be consumed as an escape.
        assertEquals("\\u-001", decodeXmlSafe("\\u-001"))
        assertEquals("\\u+001", decodeXmlSafe("\\u+001"))
        assertEquals("\\uZZZZ", decodeXmlSafe("\\uZZZZ"))
    }

    @Test
    fun `hex escapes stay case-insensitive`() {
        assertEquals("\u00e9", decodeXmlSafe("\\u00E9"))
        assertEquals("\u00e9", decodeXmlSafe("\\u00e9"))
    }

    @Test
    fun `xml non-characters are escaped rather than written raw`() {
        // Legal in a Java String, illegal in XML 1.0. A shell can put anything in an OSC title and
        // that title becomes part of a style key, so letting one through risks the settings file
        // failing to write at all.
        listOf(0xFFFE, 0xFFFF, 0xFDD0, 0xFDEF).forEach { code ->
            val raw = "tab${code.toChar()}name"
            val encoded = encodeXmlSafe(raw)!!
            assertTrue(
                "U+%04X must not survive raw".format(code),
                encoded.none { it.code == code },
            )
            assertEquals("must still round-trip", raw, decodeXmlSafe(encoded))
        }
    }

    @Test
    fun `a codepoint just outside the non-character block is untouched`() {
        // Guards the range check itself: 0xFDCF and 0xFDF0 are ordinary characters.
        listOf(0xFDCF, 0xFDF0).forEach { code ->
            assertEquals(code.toChar().toString(), encodeXmlSafe(code.toChar().toString()))
        }
    }
}
