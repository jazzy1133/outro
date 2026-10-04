package com.opus.music.player

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.exoplayer.ExoPlayer

/**
 * Volume boost via [LoudnessEnhancer] on the player's audio session.
 * Gain is in millibels (1 dB = 100 mB), 0 = off. Everything fails
 * gracefully: if the device can't do it, playback continues untouched.
 */
class VolumeBoostController {

    private var enhancer: LoudnessEnhancer? = null
    private var sessionId: Int = 0
    private var player: ExoPlayer? = null
    var gainMb: Int = 0
        private set

    @Synchronized
    fun bind(p: ExoPlayer, gainMb: Int) {
        player = p
        this.gainMb = gainMb.coerceIn(0, MAX_BOOST_MB)
        apply()
    }

    @Synchronized
    fun setGain(mb: Int) {
        gainMb = mb.coerceIn(0, MAX_BOOST_MB)
        apply()
    }

    @Synchronized
    fun apply() {
        val p = player ?: return
        val sid = try {
            p.audioSessionId
        } catch (_: Exception) {
            0
        }
        if (sid == 0 || gainMb <= 0) {
            releaseFx()
            return
        }
        if (enhancer != null && sid == sessionId) return
        releaseFx()
        try {
            val e = LoudnessEnhancer(sid)
            e.setTargetGain(gainMb)
            e.enabled = true
            enhancer = e
            sessionId = sid
        } catch (_: Exception) {
            enhancer = null
            sessionId = 0
        }
    }

    @Synchronized
    fun release() {
        releaseFx()
        player = null
    }

    private fun releaseFx() {
        try {
            enhancer?.release()
        } catch (_: Exception) {
        }
        enhancer = null
        sessionId = 0
    }

    companion object {
        /** +10 dB max boost. */
        const val MAX_BOOST_MB = 1000
    }
}

/**
 * Simple 3-band compressor via [DynamicsProcessing] on the player's audio
 * session. Three presets; everything fails gracefully on devices without
 * support.
 */
class CompressorController {

    private var dp: DynamicsProcessing? = null
    private var sessionId: Int = 0
    private var player: ExoPlayer? = null
    var preset: Int = PRESET_OFF
        private set

    @Synchronized
    fun bind(p: ExoPlayer, preset: Int) {
        player = p
        this.preset = preset.coerceIn(PRESET_OFF, PRESET_FIRM)
        apply()
    }

    @Synchronized
    fun setPreset(preset: Int) {
        this.preset = preset.coerceIn(PRESET_OFF, PRESET_FIRM)
        apply()
    }

    @Synchronized
    fun apply() {
        val p = player ?: return
        val sid = try {
            p.audioSessionId
        } catch (_: Exception) {
            0
        }
        if (sid == 0 || preset == PRESET_OFF) {
            releaseFx()
            return
        }
        if (dp != null && sid == sessionId) return
        releaseFx()
        try {
            val cfg = buildConfig(preset)
            val effect = DynamicsProcessing(0, sid, cfg)
            effect.enabled = true
            dp = effect
            sessionId = sid
        } catch (_: Exception) {
            dp = null
            sessionId = 0
        }
    }

    @Synchronized
    fun release() {
        releaseFx()
        player = null
    }

    private fun releaseFx() {
        try {
            dp?.release()
        } catch (_: Exception) {
        }
        dp = null
        sessionId = 0
    }

    private fun buildConfig(preset: Int): DynamicsProcessing.Config {
        // (attackMs, releaseMs, ratio, thresholdDb, makeupDb)
        val (attack, release, ratio, threshold, makeup) = when (preset) {
            PRESET_FIRM -> floatArrayOf(3f, 80f, 4f, -18f, 4f)
            else -> floatArrayOf(10f, 120f, 2f, -24f, 2f) // PRESET_GENTLE
        }
        val cutoffs = floatArrayOf(250f, 2000f, 20000f)
        val mbc = DynamicsProcessing.Mbc(true, true, 3)
        for (i in 0 until 3) {
            mbc.setBand(
                i,
                DynamicsProcessing.MbcBand(
                    true,
                    attack,
                    release,
                    ratio,
                    threshold,
                    12f,   // knee width dB
                    -90f,  // noise gate threshold dB (effectively off)
                    1f,    // expander ratio (off)
                    0f,    // pre gain dB
                    makeup,
                    cutoffs[i]
                )
            )
        }
        return DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,        // channel count
            false, 0, // pre-EQ
            true, 3,  // multi-band compressor, 3 bands
            false, 0, // post-EQ
            false     // limiter
        ).setMbcAllChannelsTo(mbc).build()
    }

    companion object {
        const val PRESET_OFF = 0
        const val PRESET_GENTLE = 1
        const val PRESET_FIRM = 2
        val PRESET_NAMES = listOf("Off", "Gentle", "Firm")
    }
}
