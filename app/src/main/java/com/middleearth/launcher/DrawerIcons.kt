package com.middleearth.launcher

/** How the Red Book draws its icons (a setting). */
object DrawerIcons {
    const val PREF = "drawer_icons"
    const val TONAL = "tonal"     // a filled squircle in a soft tint of the look
    const val DISC = "disc"       // a filled circle with two-tone glyphs
    const val BARE = "bare"       // just the glyph, larger
    const val CLASSIC = "classic" // the original outlined frames
    const val DEFAULT = TONAL

    val styles = listOf(TONAL to "Tonal squircle", DISC to "Soft disc, two-tone", BARE to "No frame", CLASSIC to "Classic outlines")
    fun title(style: String) = styles.firstOrNull { it.first == style }?.second ?: styles[0].second
}
