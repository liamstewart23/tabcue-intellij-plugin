package ca.liamstewart.tabcue.model

/**
 * The visual treatment applied to a single terminal tab.
 *
 * Ids rather than live [java.awt.Color] / [javax.swing.Icon] values, so a style is trivially
 * serialisable and survives a theme switch (the palette resolves ids to theme-aware JBColors).
 */
data class TabStyle(
    val colorId: String? = null,
    val iconId: String? = null,
    val tintBackground: Boolean = false,
    /** An emoji (or any short string) drawn as the tab icon; takes precedence over [iconId]. */
    val emoji: String? = null,
) {
    val isEmpty: Boolean
        get() = colorId == null && iconId == null && emoji.isNullOrBlank() && !tintBackground

    companion object {
        val EMPTY = TabStyle()
    }
}
