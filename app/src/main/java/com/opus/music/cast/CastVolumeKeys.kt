package com.opus.music.cast

import android.content.Context
import android.media.AudioAttributes
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import com.opus.music.player.PlayerManager

/**
 * Routes the hardware volume buttons to the casting speaker from
 * ANYWHERE (lock screen, other apps, screen off) while a cast session
 * is active.
 *
 * Android sends volume keys to the active media session whose playback
 * is "remote" ([MediaSession.setPlaybackToRemote] with a
 * [VolumeProvider]). Outro's playback session is Media3 local playback,
 * so while casting we activate this small dedicated session with an
 * absolute VolumeProvider (0..100) whose callbacks feed [CastManager]'s
 * cast volume. Media-button events that land on this session are
 * forwarded to [PlayerManager], which itself routes to CastManager
 * while casting. Inactive whenever nothing is casting, so local
 * playback volume behaves exactly as before.
 */
object CastVolumeKeys {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var session: MediaSession? = null

    @Volatile
    private var provider: VolumeProvider? = null

    fun init(context: Context) {
        if (session != null) return
        val app = context.applicationContext
        main.post {
            if (session != null) return@post
            val vp = object : VolumeProvider(
                VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
                100,
                CastManager.castVolume.value,
            ) {
                override fun onSetVolumeTo(volume: Int) {
                    CastManager.setCastVolume(volume)
                }

                override fun onAdjustVolume(direction: Int) {
                    if (direction != 0) CastManager.adjustCastVolume(direction * 5)
                }
            }
            val s = MediaSession(app, "outro-cast-volume")
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (CastManager.isCasting) CastManager.resume()
                    else PlayerManager.togglePlayPause()
                }

                override fun onPause() {
                    PlayerManager.pause()
                }

                override fun onSkipToNext() {
                    PlayerManager.next()
                }

                override fun onSkipToPrevious() {
                    PlayerManager.previous()
                }

                override fun onStop() {
                    if (CastManager.isCasting) CastManager.stop()
                }
            })
            provider = vp
            session = s
        }
    }

    /** Activate/deactivate remote-volume routing with the cast state. */
    fun setCasting(active: Boolean) {
        main.post {
            val s = session ?: return@post
            val vp = provider ?: return@post
            try {
                if (active) {
                    vp.setCurrentVolume(CastManager.castVolume.value)
                    s.setPlaybackToRemote(vp)
                    s.isActive = true
                } else {
                    s.isActive = false
                    s.setPlaybackToLocal(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Keep the provider's notion of the current volume in sync. */
    fun updateVolume(percent: Int) {
        try {
            provider?.setCurrentVolume(percent.coerceIn(0, 100))
        } catch (_: Exception) {
        }
    }
}
