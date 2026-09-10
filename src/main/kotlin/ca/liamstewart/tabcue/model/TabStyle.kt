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
    /**
     * The tab label colour: a [StylePalette.textColors] id or a literal `#RRGGBB`, null for the
     * theme's own. The one cue that survives selection, since the platform discards a tab's
     * colour while that tab is selected.
     */
    val textColorId: String? = null,
) {
    val isEmpty: Boolean
        get() = colorId == null &&
            iconId == null &&
            emoji.isNullOrBlank() &&
            !tintBackground &&
            textColorId == null

    companion object {
        val EMPTY = TabStyle()
    }
}
