package ca.liamstewart.tabcue.model

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.Icon

/**
 * The curated set of colours and icons a tab can use.
 *
 * Each colour is an accent at full strength, used for the dot icon and the background tint. It is
 * not what the tab background is painted with: [ColorMath] derives that, which is what lets a user
 * pick any colour without being able to make a tab unreadable. The accents are therefore stronger
 * than a tab background could safely be. Light and dark values differ only in brightness, so a dot
 * reads against either tab strip.
 */
object StylePalette {

    data class Swatch(val id: String, val displayName: String, val color: JBColor)

    val colors: List<Swatch> = listOf(
        Swatch("red", "Red", JBColor(0xE05252, 0xF07B7B)),
        Swatch("orange", "Orange", JBColor(0xE0842E, 0xF0A356)),
        Swatch("yellow", "Yellow", JBColor(0xC9A227, 0xE0C04A)),
        Swatch("green", "Green", JBColor(0x3E9E4E, 0x5FBF71)),
        Swatch("teal", "Teal", JBColor(0x2A9D9A, 0x4FC0BD)),
        Swatch("blue", "Blue", JBColor(0x3B7DD8, 0x5D9BF0)),
        Swatch("purple", "Purple", JBColor(0x8A5CD1, 0xA87FE8)),
        Swatch("grey", "Gray", JBColor(0x8A8F98, 0xA3A8B0)),
    )

    data class IconChoice(val id: String, val displayName: String, val icon: Icon)

    val icons: List<IconChoice> = listOf(
        IconChoice("run", "Run", AllIcons.Actions.Execute),
        IconChoice("restart", "Restart", AllIcons.Actions.Restart),
        IconChoice("build", "Build", AllIcons.Actions.Compile),
        IconChoice("deploy", "Deploy", AllIcons.Actions.Upload),
        IconChoice("remote", "Remote", AllIcons.General.Web),
        IconChoice("warning", "Warning", AllIcons.General.Warning),
        IconChoice("database", "Database", AllIcons.Nodes.DataTables),
        IconChoice("console", "Console", AllIcons.Nodes.Console),
        IconChoice("folder", "Folder", AllIcons.Nodes.Folder),
        IconChoice("settings", "Settings", AllIcons.General.Settings),
    )

    /**
     * The icon the user actually picked, emoji first. Separate from [colorDot] because the two
     * rank differently against an icon somebody else owns: a chosen icon replaces it, a dot does not.
     */
    fun chosenIcon(style: TabStyle): Icon? {
        style.emoji?.takeIf { it.isNotBlank() }?.let { return EmojiIcon(it) }
        return icon(style.iconId)
    }

    /** A dot in the tab's colour, which is what keeps a colour-only style visible once selected. */
    fun colorDot(style: TabStyle): Icon? = color(style.colorId)?.let { DotIcon(it) }

    /**
     * Emoji offered directly in the tab menu.
     *
     * The point is that the common case needs no dialog at all: one click from the context menu.
     * Anything outside this set goes through "Custom Emoji…".
     */
    data class EmojiChoice(val emoji: String, val displayName: String)

    /**
     * Named because a menu row already shows the glyph as its icon. Using the glyph as the row
     * text too drew it twice side by side, and the name makes the menu searchable by word.
     */
    val emojiChoices: List<EmojiChoice> = listOf(
        EmojiChoice("🚀", "Rocket"),
        EmojiChoice("🔥", "Fire"),
        EmojiChoice("🐛", "Bug"),
        EmojiChoice("🧪", "Tests"),
        EmojiChoice("⚙️", "Build"),
        EmojiChoice("🌐", "Web"),
        EmojiChoice("🗄️", "Database"),
        EmojiChoice("📦", "Package"),
        EmojiChoice("✅", "Passing"),
        EmojiChoice("⚠️", "Warning"),
        EmojiChoice("🔒", "Production"),
        EmojiChoice("🧹", "Cleanup"),
    )

    /** The glyphs alone, for "is this one of the curated set?" checks and the rule editor. */
    val emojis: List<String> = emojiChoices.map { it.emoji }

    data class TextColor(val id: String, val displayName: String, val color: JBColor)

    /**
     * Tab label colours. Two presets only: against a coloured tab the real choice is light text or
     * dark text, and anything else goes through Custom.
     *
     * Light and dark values are identical, because the point of picking one is to override what
     * the theme would have chosen. Black is softened to 0x1A1A1A, since pure black on a mid-tone
     * tab reads as a rendering artefact.
     */
    val textColors: List<TextColor> = listOf(
        TextColor("white", "White", JBColor(0xF2F2F2, 0xF2F2F2)),
        TextColor("black", "Black", JBColor(0x1A1A1A, 0x1A1A1A)),
    )

    /** A preset id, a literal `#RRGGBB`, or null for the theme's own. */
    fun textColor(id: String?): Color? = when {
        id == null -> null
        ColorMath.isCustom(id) -> ColorMath.parseCustom(id)
        else -> textColors.firstOrNull { it.id == id }?.color
    }

    fun textColorName(id: String?): String? = when {
        id == null -> null
        ColorMath.isCustom(id) -> id
        else -> textColors.firstOrNull { it.id == id }?.displayName
    }

    /**
     * The accent for a `colorId`, which is either a palette id or a literal `#RRGGBB` chosen from
     * the colour picker. Unknown ids resolve to null, so a stale or hand-edited value degrades to
     * "no colour" rather than throwing.
     */
    fun color(id: String?): Color? = when {
        id == null -> null
        ColorMath.isCustom(id) -> ColorMath.parseCustom(id)
        else -> colors.firstOrNull { it.id == id }?.color
    }

    fun icon(id: String?): Icon? = id?.let { wanted -> icons.firstOrNull { it.id == wanted }?.icon }

    fun colorName(id: String?): String? = when {
        id == null -> null
        ColorMath.isCustom(id) -> id.uppercase()
        else -> colors.firstOrNull { it.id == id }?.displayName
    }

    /** Used to hand successive auto-assigned tabs visually distinct colours. */
    fun colorIdAt(index: Int): String = colors[index.mod(colors.size)].id
}
