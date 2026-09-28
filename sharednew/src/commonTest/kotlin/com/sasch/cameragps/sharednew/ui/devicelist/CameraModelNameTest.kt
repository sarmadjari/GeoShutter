package com.sasch.cameragps.sharednew.ui.devicelist

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraModelNameTest {

    @Test
    fun sonyModelCodesBecomeMarketingNames() {
        mapOf(
            "ILCE-1M2" to "α1 II",
            "ILCE-1" to "α1",
            "ILCE-7M4" to "α7 IV",
            "ILCE-7RM5" to "α7R V",
            "ILCE-7RM4A" to "α7R IVA",
            "ILCE-7SM3" to "α7S III",
            "ILCE-7CM2" to "α7C II",
            "ILCE-7CR" to "α7CR",
            "ILCE-9M3" to "α9 III",
            "ILCE-6700" to "α6700",
            "ILCA-99M2" to "α99 II",
            "ILME-FX3" to "FX3",
            "ILME-FX30" to "FX30",
            "ZV-E10M2" to "ZV-E10 II",
            "ZV-E1" to "ZV-E1",
            "ZV-1M2" to "ZV-1 II",
            "DSC-RX100M7" to "RX100 VII",
        ).forEach { (code, name) -> assertEquals(name, cameraModelName(code), code) }
    }

    @Test
    fun otherNamesStayAsTheyAre() {
        listOf("X100VI", "X100VI-1A2B", "My camera", "ILCE-", "N/A").forEach {
            assertEquals(it, cameraModelName(it))
        }
    }

    @Test
    fun theModelLineNamesTheBrandAndTheModel() {
        assertEquals("Sony α1 II", cameraModelLine(CameraBrand.Sony, "ILCE-1M2"))
        assertEquals("Fujifilm X100VI", cameraModelLine(CameraBrand.Fujifilm, "X100VI"))
        // A Sony model code identifies the brand by itself.
        assertEquals("Sony α7 IV", cameraModelLine(null, "ILCE-7M4"))
        // A name chosen on a Sony camera isn't a model.
        assertEquals("Sony", cameraModelLine(CameraBrand.Sony, "Sarmad's A1"))
        assertEquals("Fujifilm", cameraModelLine(CameraBrand.Fujifilm, "N/A"))
        // Unknown brand: the pairing name as before.
        assertEquals("X100VI", cameraModelLine(null, "X100VI"))
        assertEquals(null, cameraModelLine(null, "N/A"))
    }
}
