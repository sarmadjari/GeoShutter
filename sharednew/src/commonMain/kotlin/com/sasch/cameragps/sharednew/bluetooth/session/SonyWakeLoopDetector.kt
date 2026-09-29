package com.sasch.cameragps.sharednew.bluetooth.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Notices a Sony camera that GeoShutter keeps waking up. In power save the camera ends the
 * connection a fixed time after the phone set it up (its Power Save Start Time). With
 * "Cnct. while Power OFF" on, the sleeping camera is reachable again seconds later, the
 * phone reconnects, which wakes it, and it all starts again: every 70 s on an α1 II with
 * the default 1 minute. Without that setting the camera stays quiet until someone wakes it.
 *
 * [CYCLES] setups in a row that each ended after about the same time, each followed by a
 * setup again within [MAX_RECONNECT], count as such a loop ([looping]).
 */
class SonyWakeLoopDetector(private val timeSource: TimeSource = TimeSource.Monotonic) {
    private val _looping = MutableStateFlow<Set<String>>(emptySet())

    /** Cameras caught in the loop, by identifier. */
    val looping: StateFlow<Set<String>> = _looping.asStateFlow()

    private class Track {
        var readyAt: TimeMark? = null
        var droppedAt: TimeMark? = null
        val lengths = ArrayDeque<Duration>()
    }

    private val tracks = mutableMapOf<String, Track>()

    /** A Sony camera accepted the setup and takes the location. */
    fun onReady(id: String) {
        val track = tracks.getOrPut(id) { Track() }
        val sinceDrop = track.droppedAt?.elapsedNow()
        if (sinceDrop != null && sinceDrop > MAX_RECONNECT) reset(id, track)
        track.droppedAt = null
        track.readyAt = timeSource.markNow()
    }

    /** The connection ended; only a connection that was set up counts. */
    fun onDisconnected(id: String) {
        val track = tracks[id] ?: return
        val readyAt = track.readyAt ?: return
        val length = readyAt.elapsedNow()
        track.readyAt = null
        track.droppedAt = timeSource.markNow()
        if (length < MIN_LENGTH) {
            reset(id, track)
            return
        }
        track.lengths.addLast(length)
        while (track.lengths.size > CYCLES) track.lengths.removeFirst()
        val loop = track.lengths.size == CYCLES &&
                track.lengths.max() - track.lengths.min() <= LENGTH_TOLERANCE
        _looping.update { if (loop) it + id else it - id }
    }

    /** Called [MAX_RECONNECT] after a drop: without a new setup the camera stays asleep. */
    fun onQuiet(id: String) {
        val track = tracks[id] ?: return
        val droppedAt = track.droppedAt ?: return
        if (droppedAt.elapsedNow() >= MAX_RECONNECT) reset(id, track)
    }

    /** "Keep the camera awake" is on, so it doesn't go into power save while connected. */
    fun forget(id: String) {
        tracks.remove(id)
        _looping.update { it - id }
    }

    private fun reset(id: String, track: Track) {
        track.lengths.clear()
        _looping.update { it - id }
    }

    companion object {
        const val CYCLES = 3

        /** From a drop to the next completed setup: about 12 s in the loop. */
        val MAX_RECONNECT = 45.seconds

        /** Shorter connections ended for another reason (the shortest power save is 10 s). */
        val MIN_LENGTH = 5.seconds

        /** The power save timer ends the connection at the same time after each setup. */
        val LENGTH_TOLERANCE = 15.seconds
    }
}
