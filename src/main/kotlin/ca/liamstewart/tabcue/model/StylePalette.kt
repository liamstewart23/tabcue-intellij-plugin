package ca.liamstewart.tabcue.model

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.Icon

/**
 * The curated set of colours and icons a tab can use.
 *
 * Each colour is an *accent*, at full strength: it is what the dot icon and the background tint are
 * drawn in. It is deliberately not what the tab background is painted with — [ColorMath] derives
 * that, mixing the accent part-way into the tab-strip background so the tab label keeps its
 * contrast, then compensating for the translucent overlay the platform's tab painter composites
 * on top. That indirection is what lets a user pick any colour at all without being able to make
 * a tab unreadable.
 *
 * The accents below are far stronger than a tab background could safely be, for exactly that
 * reason. Light and dark values differ only in brightness, so a dot reads against either
 * tab strip.
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
     * The icon a tab should actually show.
     *
     * Precedence is emoji, then a chosen icon, then — when [colorAsDot] is on — a dot in the tab's
     * colour. That last fallback is what keeps a colour-only style visible: the platform's tab
     * painter discards the tab background colour on the selected tab and blends an opaque hover
     * colour over it on hover, whereas the icon is painted in every state.
     */
    fun iconFor(style: TabStyle, colorAsDot: Boolean): Icon? {
        style.emoji?.takeIf { it.isNotBlank() }?.let { return EmojiIcon(it) }
        icon(style.iconId)?.let { return it }
        if (colorAsDot) color(style.colorId)?.let { return DotIcon(it) }
        return null
    }

    /**
     * Emoji offered directly in the tab menu.
     *
     * The point is that the common case needs no dialog at all: one click from the context menu.
     * Anything outside this set goes through "Custom Emoji…".
     */
    data class EmojiChoice(val emoji: String, val displayName: String)

    /**
     * Named, because a menu row shows the glyph as its icon: with the glyph *also* as the row's
     * text every entry rendered the same emoji twice, side by side. The name is what makes the
     * row readable, and it makes the menu searchable by word rather than by pictogram.
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

    /** The glyphs alone, for "is this one of the curated set?" checks and for the rule editor. */
    val emojis: List<String> = emojiChoices.map { it.emoji }

    data class TextColor(val id: String, val displayName: String, val color: JBColor)

    /**
     * Tab *label* colours.
     *
     * Only two presets, deliberately: against a coloured tab the useful choice is essentially
     * "light text" or "dark text", and offering ten tints of each would be choice for its own
     * sake. Anything else goes through Custom, which is the same colour picker the accents use.
     *
     * Both are theme-independent — the point of picking one is to override what the theme would
     * have chosen — so the light and dark values of each JBColor are identical. Black is softened
     * to 0x1A1A1A because pure black on a mid-tone tab reads as a rendering artefact.
     */
    val textColors: List<TextColor> = listOf(
        TextColor("white", "White", JBColor(0xF2F2F2, 0xF2F2F2)),
        TextColor("black", "Black", JBColor(0x1A1A1A, 0x1A1A1A)),
    )

    /** The label colour for a `textColorId`: a preset id, a literal `#RRGGBB`, or null for theme. */
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
