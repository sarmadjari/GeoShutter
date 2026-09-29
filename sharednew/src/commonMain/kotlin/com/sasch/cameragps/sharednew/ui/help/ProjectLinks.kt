package com.sasch.cameragps.sharednew.ui.help

/** Where the app sends people for documentation, problem reports and donations. */
object ProjectLinks {

    const val REPOSITORY = "https://github.com/sarmadjari/GeoShutter"
    const val DOCUMENTATION = "$REPOSITORY#readme"
    const val ISSUES = "$REPOSITORY/issues"

    /** Donations go to Saschl, the author of Alpha GPS, which GeoShutter builds on. */
    const val ORIGINAL_AUTHOR_DONATION = "https://buymeacoffee.com/wj8tism4dq"

    /** A link as shown in text: without the scheme. */
    fun display(url: String): String = url.removePrefix("https://")
}
