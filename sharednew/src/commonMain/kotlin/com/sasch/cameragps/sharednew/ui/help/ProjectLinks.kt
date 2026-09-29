package com.sasch.cameragps.sharednew.ui.help

/** Where the app sends people for documentation, problem reports and donations. */
object ProjectLinks {

    const val REPOSITORY = "https://github.com/sarmadjari/GeoShutter"
    const val DOCUMENTATION = "$REPOSITORY#readme"
    const val ISSUES = "$REPOSITORY/issues"
    const val LICENSE = "$REPOSITORY/blob/main/LICENSE"

    /** Alpha GPS by Saschl, which GeoShutter is based on. */
    const val ALPHA_GPS = "https://github.com/Saschl/alpha-gps"

    /** furble by Guo-Rong Koh, the source of the Fujifilm protocol. */
    const val FURBLE = "https://github.com/gkoh/furble"

    /** Donations go to Saschl, the author of Alpha GPS, which GeoShutter builds on. */
    const val ORIGINAL_AUTHOR_DONATION = "https://buymeacoffee.com/wj8tism4dq"

    /** A link as shown in text: without the scheme. */
    fun display(url: String): String = url.removePrefix("https://")
}
