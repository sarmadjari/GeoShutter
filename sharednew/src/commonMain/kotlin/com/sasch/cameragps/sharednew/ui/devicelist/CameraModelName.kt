package com.sasch.cameragps.sharednew.ui.devicelist

/** "7RM5" → base "7R", mark "5", variant "" (as in ILCE-7RM4A: variant "A"). */
private val SONY_MARK = Regex("^(.+?)M([2-9])([A-Z]?)$")

private val ROMAN = listOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX")

/**
 * Marketing name for the model code a Sony camera reports as its name, for the camera
 * list: "ILCE-1M2" → "α1 II", "ILCE-7RM5" → "α7R V", "ILCE-6700" → "α6700",
 * "ILME-FX3" → "FX3", "ZV-E10M2" → "ZV-E10 II", "DSC-RX100M7" → "RX100 VII".
 * Anything else (Fujifilm models, a name given to the camera) is returned unchanged.
 */
fun cameraModelName(reportedName: String): String {
    val code = reportedName.trim()
    val (prefix, model) = when {
        code.startsWith("ILCE-") -> "α" to code.removePrefix("ILCE-")
        code.startsWith("ILCA-") -> "α" to code.removePrefix("ILCA-")
        code.startsWith("ILME-") -> "" to code.removePrefix("ILME-")
        code.startsWith("DSC-") -> "" to code.removePrefix("DSC-")
        code.startsWith("ZV-") -> "ZV-" to code.removePrefix("ZV-")
        else -> return reportedName
    }
    if (model.isEmpty()) return reportedName
    val mark = SONY_MARK.matchEntire(model)
        ?: return prefix + model
    val (base, generation, variant) = mark.destructured
    return "$prefix$base ${ROMAN[generation.toInt()]}$variant"
}
