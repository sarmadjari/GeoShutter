package com.sasch.cameragps.sharednew.bluetooth.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class SonyWakeLoopDetectorTest {
    private val time = TestTimeSource()
    private val detector = SonyWakeLoopDetector(time)
    private val id = "SONY"

    private fun looping() = id in detector.looping.value

    /** One setup that the camera ends after [length], then [gap] until the next setup. */
    private fun cycle(length: Duration = 59.seconds, gap: Duration = 11.seconds) {
        detector.onReady(id)
        time += length
        detector.onDisconnected(id)
        time += gap
    }

    /**
     * The α1 II on the iPhone, 2026-09-30, with Cnct. while Power OFF on: it ended the
     * connection 59 to 60 s after each setup and was set up again about 11 s later.
     */
    @Test
    fun threeEqualCyclesWithQuickReconnectsAreALoop() {
        cycle(59.4.seconds)
        cycle(59.6.seconds)
        assertFalse(looping())
        cycle(59.5.seconds)
        assertTrue(looping())
        detector.onReady(id)
        assertTrue(looping(), "the next setup keeps it")
    }

    @Test
    fun aCameraThatStaysAsleepEndsTheLoop() {
        repeat(3) { cycle(gap = 0.seconds) }
        assertTrue(looping())
        time += SonyWakeLoopDetector.MAX_RECONNECT
        detector.onQuiet(id)
        assertFalse(looping())
    }

    @Test
    fun aQuickReconnectIsNotQuiet() {
        repeat(3) { cycle(gap = 0.seconds) }
        time += 11.seconds
        detector.onReady(id)
        time += SonyWakeLoopDetector.MAX_RECONNECT
        detector.onQuiet(id)
        assertTrue(looping())
    }

    /** Without Cnct. while Power OFF the camera comes back only when someone wakes it. */
    @Test
    fun slowReconnectsAreNoLoop() {
        repeat(5) { cycle(gap = 3.minutes) }
        assertFalse(looping())
    }

    /** Someone using the camera keeps it awake longer: the timer no longer explains the drops. */
    @Test
    fun connectionsOfDifferentLengthsAreNoLoop() {
        repeat(3) { cycle() }
        assertTrue(looping())
        cycle(length = 4.minutes)
        assertFalse(looping())
        cycle()
        cycle()
        assertFalse(looping())
        cycle()
        assertTrue(looping(), "three equal cycles again")
    }

    @Test
    fun onlyConnectionsThatWereSetUpCount() {
        repeat(3) {
            detector.onDisconnected(id)
            time += 11.seconds
        }
        assertFalse(looping())
        assertEquals(emptySet(), detector.looping.value)
    }

    @Test
    fun keepingTheCameraAwakeForgetsIt() {
        repeat(3) { cycle() }
        detector.forget(id)
        assertFalse(looping())
        cycle()
        assertFalse(looping(), "it starts counting again")
    }
}
