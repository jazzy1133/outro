package com.opus.music.player

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.opus.music.Graph

/**
 * Foreground playback service hosting ExoPlayer + MediaLibrarySession.
 * MediaLibraryService gives Android Auto / Automotive a browse tree
 * (see [AutoLibraryCallback]); local playback behavior is unchanged.
 */
class PlayerService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        try {
            super.onCreate()
        } catch (e: Throwable) {
            android.util.Log.e("PlayerService", "super.onCreate failed", e)
        }
        try {
            val s0 = Graph.settings
            val player = ExoPlayer.Builder(this)
                .setHandleAudioBecomingNoisy(true)
                .setSkipSilenceEnabled(try { s0.isSkipSilence() } catch (_: Exception) { false })
                .build()
            EngineHolder.exoPlayer = player
            session = MediaLibrarySession.Builder(this, player, AutoLibraryCallback()).build()
            // Keep the home-screen widget in sync with playback state.
            try {
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        com.opus.music.widget.WidgetUpdater.refresh(this@PlayerService)
                    }
                    override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                        com.opus.music.widget.WidgetUpdater.refresh(this@PlayerService)
                    }
                })
            } catch (_: Exception) { }
            // Companion-player crossfade engine + smart sleep fade.
            try {
                val engine = CrossfadeEngine(
                    applicationContext,
                    player,
                    songLookup = { id -> PlayerManager.queueSongs.value.firstOrNull { it.id == id } },
                    mediaItemFor = { song -> PlayerManager.mediaItemFor(song) }
                )
                engine.fadeSec = try { Graph.settings.getCrossfadeSec() } catch (_: Exception) { 0 }
                EngineHolder.crossfade = engine
            } catch (e: Throwable) {
                android.util.Log.e("PlayerService", "CrossfadeEngine init failed", e)
            }
            // Built-in 5-band equalizer on the player's audio session.
            // Must never break playback: everything inside fails gracefully.
            try {
                val eq = EqualizerEngine.Controller()
                EngineHolder.eq = eq
                val s = Graph.settings
                eq.bind(player, s.isEqEnabled(), s.getEqBands(), s.getEqPreamp())
            } catch (e: Throwable) {
                android.util.Log.e("PlayerService", "Equalizer init failed", e)
                EngineHolder.eq = null
            }
            // Volume boost (LoudnessEnhancer) and 3-band compressor
            // (DynamicsProcessing) on the same audio session. Best effort.
            try {
                val boost = VolumeBoostController()
                EngineHolder.boost = boost
                boost.bind(player, Graph.settings.getVolumeBoost())
            } catch (e: Throwable) {
                android.util.Log.e("PlayerService", "VolumeBoost init failed", e)
                EngineHolder.boost = null
            }
            try {
                val comp = CompressorController()
                EngineHolder.compressor = comp
                comp.bind(player, Graph.settings.getCompressor())
            } catch (e: Throwable) {
                android.util.Log.e("PlayerService", "Compressor init failed", e)
                EngineHolder.compressor = null
            }
        } catch (e: Throwable) {
            android.util.Log.e("PlayerService", "ExoPlayer init failed", e)
            // Leave session null; controller will get null session gracefully.
            session = null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * Home-screen widget remote control. Operates on [EngineHolder]'s player
     * directly so there is no controller-binding race on a cold start, and
     * routes through [com.opus.music.cast.CastManager] while casting, exactly
     * like [PlayerManager]'s own transport methods.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            com.opus.music.widget.WidgetActions.ACTION_TOGGLE -> {
                try {
                    if (com.opus.music.cast.CastManager.isCasting) {
                        com.opus.music.cast.CastManager.togglePlayPause()
                    } else {
                        EngineHolder.exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
                    }
                } catch (_: Exception) { }
            }
            com.opus.music.widget.WidgetActions.ACTION_NEXT -> {
                try {
                    if (com.opus.music.cast.CastManager.isCasting) {
                        com.opus.music.cast.CastManager.next()
                    } else if (EngineHolder.crossfade?.next() != true) {
                        EngineHolder.exoPlayer?.seekToNextMediaItem()
                    }
                } catch (_: Exception) { }
            }
            com.opus.music.widget.WidgetActions.ACTION_PREV -> {
                try {
                    if (com.opus.music.cast.CastManager.isCasting) {
                        com.opus.music.cast.CastManager.previous()
                    } else if (EngineHolder.crossfade?.previous() != true) {
                        EngineHolder.exoPlayer?.seekToPreviousMediaItem()
                    }
                } catch (_: Exception) { }
            }
        }
        if (intent?.action in setOf(
                com.opus.music.widget.WidgetActions.ACTION_TOGGLE,
                com.opus.music.widget.WidgetActions.ACTION_NEXT,
                com.opus.music.widget.WidgetActions.ACTION_PREV
            )
        ) {
            com.opus.music.widget.WidgetUpdater.refresh(this)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        try { EngineHolder.crossfade?.release() } catch (_: Exception) { }
        EngineHolder.crossfade = null
        try { EngineHolder.eq?.release() } catch (_: Exception) { }
        EngineHolder.eq = null
        try { EngineHolder.boost?.release() } catch (_: Exception) { }
        EngineHolder.boost = null
        try { EngineHolder.compressor?.release() } catch (_: Exception) { }
        EngineHolder.compressor = null
        EngineHolder.exoPlayer = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
