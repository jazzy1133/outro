package com.opus.music.player

import android.media.audiofx.Equalizer
import androidx.media3.exoplayer.ExoPlayer

/**
 * Built-in 5-band equalizer.
 *
 * The app works with 5 bands in millibels (±1500 mB = ±15 dB). They are mapped
 * onto however many bands the device's [Equalizer] actually exposes. All
 * runtime operations fail gracefully: if the device can't attach an EQ,
 * playback continues untouched.
 */
object EqualizerEngine {

    const val BAND_COUNT = 5
    const val MAX_DB_MB = 1500 // ±15 dB in millibels (1 dB = 100 mB)
    const val MAX_PREAMP_MB = 1200 // ±12 dB preamp

    /** Center frequencies of the 5 app bands. */
    val BAND_FREQUENCIES_HZ = listOf(60, 230, 910, 3600, 14000)

    data class Preset(val name: String, val bands: List<Int>)

    val presets = listOf(
        Preset("Flat", listOf(0, 0, 0, 0, 0)),
        Preset("Bass boost", listOf(900, 700, 200, 0, 0)),
        Preset("Treble boost", listOf(0, 0, 200, 700, 900)),
        Preset("Vocal", listOf(-300, -100, 400, 700, 300)),
        Preset("Rock", listOf(500, 300, -100, 300, 600)),
        Preset("Pop", listOf(-200, 200, 500, 400, -100)),
        Preset("Jazz", listOf(400, 200, -100, 300, 500)),
        Preset("Classical", listOf(300, 200, -100, 200, 400)),
        Preset("Dance", listOf(700, 500, 100, 200, 300)),
        Preset("Acoustic", listOf(300, 200, 200, 400, 300))
    )

    /**
     * Map our 5 app bands (millibels) onto the device's band count.
     * Most devices expose exactly 5; fewer/more are resampled by index.
     * Pure function — unit-testable.
     */
    fun mapToDeviceBands(appBands: List<Int>, deviceBandCount: Int): List<Int> {
        if (deviceBandCount <= 0) return emptyList()
        return List(deviceBandCount) { i ->
            val src = ((i.toLong() * BAND_COUNT) / deviceBandCount).toInt()
                .coerceIn(0, BAND_COUNT - 1)
            appBands.getOrElse(src) { 0 }.coerceIn(-MAX_DB_MB, MAX_DB_MB)
        }
    }

    /**
     * Live controller bound to the service's ExoPlayer. Must be touched from
     * one thread at a time; callers use the main thread.
     */
    class Controller {
        private var eq: Equalizer? = null
        private var sessionId: Int = 0
        private var player: ExoPlayer? = null
        var enabled: Boolean = false
            private set
        var bands: List<Int> = List(BAND_COUNT) { 0 }
            private set
        /**
         * Preamp gain in millibels, added to every band before the device
         * range clamp. -1200..+1200 (±12 dB).
         */
        var preampMb: Int = 0
            private set

        @Synchronized
        fun bind(p: ExoPlayer, enabled: Boolean, bands: List<Int>, preampMb: Int = 0) {
            player = p
            this.enabled = enabled
            this.bands = bands
            this.preampMb = preampMb.coerceIn(-MAX_PREAMP_MB, MAX_PREAMP_MB)
            apply()
        }

        @Synchronized
        fun setEnabled(on: Boolean) {
            enabled = on
            apply()
        }

        @Synchronized
        fun setBands(newBands: List<Int>) {
            bands = newBands
            pushBands()
        }

        @Synchronized
        fun setPreamp(mb: Int) {
            preampMb = mb.coerceIn(-MAX_PREAMP_MB, MAX_PREAMP_MB)
            pushBands()
        }

        /**
         * (Re)attach the effect to the player's current audio session.
         * Safe to call often: no-op when nothing changed; releases the
         * effect when disabled.
         */
        @Synchronized
        fun apply() {
            if (!enabled) {
                releaseEq()
                return
            }
            val p = player ?: return
            val sid = try {
                p.audioSessionId
            } catch (_: Exception) {
                0
            }
            // Session 0 = player hasn't produced audio yet; retry later.
            if (sid == 0) return
            if (eq != null && sid == sessionId) {
                pushBands()
                return
            }
            releaseEq()
            try {
                val e = Equalizer(0, sid)
                val deviceCount = e.numberOfBands.toInt()
                val range = e.bandLevelRange // millibels [min, max]
                val mapped = mapToDeviceBands(bands, deviceCount)
                for (i in mapped.indices) {
                    try {
                        val lvl = (mapped[i] + preampMb).coerceIn(range[0].toInt(), range[1].toInt())
                        e.setBandLevel(i.toShort(), lvl.toShort())
                    } catch (_: Exception) {
                    }
                }
                e.enabled = true
                eq = e
                sessionId = sid
            } catch (_: Exception) {
                // Device can't do EQ (or denied): leave eq null, music plays on.
                eq = null
                sessionId = 0
            }
        }

        @Synchronized
        fun release() {
            releaseEq()
            player = null
        }

        private fun pushBands() {
            val e = eq ?: return
            try {
                val deviceCount = e.numberOfBands.toInt()
                val range = e.bandLevelRange
                val mapped = mapToDeviceBands(bands, deviceCount)
                for (i in mapped.indices) {
                    try {
                        val lvl = (mapped[i] + preampMb).coerceIn(range[0].toInt(), range[1].toInt())
                        e.setBandLevel(i.toShort(), lvl.toShort())
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }

        private fun releaseEq() {
            try {
                eq?.release()
            } catch (_: Exception) {
            }
            eq = null
            sessionId = 0
        }
    }
}

