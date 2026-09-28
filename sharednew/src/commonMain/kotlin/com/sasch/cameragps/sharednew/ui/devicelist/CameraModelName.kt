package com.sasch.cameragps.sharednew.ui.devicelist

/** "7RM5" → base "7R", mark "5", variant "" (as in ILCE-7RM4A: variant "A"). */
private val SONY_MARK = Regex("^(.+?)M([2-9])([A-Z]?)$")

private val ROMAN = listOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX")

/** Camera makers GeoShutter knows, as shown in the camera list. */
enum class CameraBrand(val displayName: String) {
    Sony("Sony"),
    Fujifilm("Fujifilm"),
}

/**
 * Marketing name for a Sony model code as Sony cameras report it, or null when [code]
 * isn't one: "ILCE-1M2" → "α1 II", "ILCE-7RM5" → "α7R V", "ILCE-6700" → "α6700",
 * "ILME-FX3" → "FX3", "ZV-E10M2" → "ZV-E10 II", "DSC-RX100M7" → "RX100 VII".
 */
fun sonyModelName(code: String): String? {
    val trimmed = code.trim()
    val (prefix, model) = when {
        trimmed.startsWith("ILCE-") -> "α" to trimmed.removePrefix("ILCE-")
        trimmed.startsWith("ILCA-") -> "α" to trimmed.removePrefix("ILCA-")
        trimmed.startsWith("ILME-") -> "" to trimmed.removePrefix("ILME-")
        trimmed.startsWith("DSC-") -> "" to trimmed.removePrefix("DSC-")
        trimmed.startsWith("ZV-") -> "ZV-" to trimmed.removePrefix("ZV-")
        else -> return null
    }
    if (model.isEmpty()) return null
    val mark = SONY_MARK.matchEntire(model) ?: return prefix + model
    val (base, generation, variant) = mark.destructured
    return "$prefix$base ${ROMAN[generation.toInt()]}$variant"
}

/** [sonyModelName] for Sony model codes; anything else is returned unchanged. */
fun cameraModelName(reportedName: String): String = sonyModelName(reportedName) ?: reportedName

/**
 * The line under a camera's name: brand and model, e.g. "Sony α1 II" or
 * "Fujifilm X100VI". [pairingName] is the name the camera had when it was added: a Sony
 * model code, or the model for Fujifilm cameras in pairing mode. A Sony model code
 * also identifies the brand when [brand] is unknown. Returns null when there is
 * nothing to show.
 */
fun cameraModelLine(brand: CameraBrand?, pairingName: String): String? {
    val name = pairingName.trim().takeUnless { it.isEmpty() || it == "N/A" }
    val sonyModel = name?.let(::sonyModelName)
    val resolvedBrand = brand ?: CameraBrand.Sony.takeIf { sonyModel != null }
    val model = when (resolvedBrand) {
        // A Sony name that isn't a model code was chosen on the camera, not a model.
        CameraBrand.Sony -> sonyModel
        CameraBrand.Fujifilm, null -> name
    }
    return listOfNotNull(resolvedBrand?.displayName, model)
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }
}
