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
     * The tab *label* colour: a [StylePalette.textColors] id or a literal `#RRGGBB`.
     *
     * Null means the theme decides, which is the right default — the tab fill is already derived
     * to keep the label legible. This exists for the case that derivation cannot help with: the
     * platform discards the tab colour entirely on the selected tab, so a user who wants a tab to
     * stand out *while they are looking at it* has only the label left to colour.
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
