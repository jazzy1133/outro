package com.opus.music.cast

/** What to do when the speaker finishes the current cast track. */
enum class CastEndAction { REPLAY, NEXT, WRAP, FINISH }

/**
 * Pure track-end decision for the cast queue (unit-tested on the JVM).
 * [repeatMode] mirrors androidx.media3.common.Player: 0 = off,
 * 1 = repeat all, 2 = repeat one.
 */
fun castEndAction(index: Int, queueSize: Int, repeatMode: Int): CastEndAction = when {
    queueSize <= 0 -> CastEndAction.FINISH
    repeatMode == 2 -> CastEndAction.REPLAY
    index < queueSize - 1 -> CastEndAction.NEXT
    repeatMode == 1 -> CastEndAction.WRAP
    else -> CastEndAction.FINISH
}
