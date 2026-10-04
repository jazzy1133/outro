package com.opus.music

import android.content.Context
import com.opus.music.audiobook.AudiobookManager
import com.opus.music.data.DownloadRepository
import com.opus.music.data.MusicRepository
import com.opus.music.data.ServerConfig
import com.opus.music.data.SettingsRepository
import com.opus.music.data.StatsRepository
import com.opus.music.lyrics.LyricsRepository
import com.opus.music.network.SubsonicClient
import com.opus.music.radio.RadioRepository

/** Active server session (set on login, restored on launch). */
object Session {
    @Volatile var client: SubsonicClient? = null
        private set
    @Volatile var music: MusicRepository? = null
        private set
    @Volatile var config: ServerConfig? = null
        private set

    fun open(config: ServerConfig) {
        this.config = config
        val c = SubsonicClient(config)
        client = c
        music = MusicRepository(c)
    }

    fun close() {
        client = null
        music = null
        config = null
    }
}

/** Manual service locator for app-scoped singletons. */
object Graph {
    lateinit var settings: SettingsRepository
        private set
    lateinit var downloads: DownloadRepository
        private set
    lateinit var stats: StatsRepository
        private set
    lateinit var lyrics: LyricsRepository
        private set
    lateinit var audiobooks: AudiobookManager
        private set
    lateinit var radio: RadioRepository
        private set

    fun init(context: Context) {
        // Idempotent: safe to call multiple times.
        if (::settings.isInitialized) return
        val appContext = context.applicationContext
        settings = SettingsRepository(appContext)
        downloads = DownloadRepository(appContext)
        stats = StatsRepository(appContext)
        lyrics = LyricsRepository(appContext) { Session.client?.api() }
        audiobooks = AudiobookManager(appContext) { Session.client?.api() }
        radio = RadioRepository { Session.client?.api() }
        // Load saved credentials now; otherwise Nav() sees null config
        // and always shows the setup screen.
        settings.ensureInit()
    }
}
