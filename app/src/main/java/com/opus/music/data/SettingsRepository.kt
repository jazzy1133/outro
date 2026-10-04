package com.opus.music.data

import android.content.Context
import android.content.SharedPreferences
import com.opus.music.player.EqualizerEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Stores server credentials in plain SharedPreferences.
 * (Encrypted prefs via Keystore/Tink caused startup crashes on some devices;
 *  plain prefs are stable. Credentials are only a music-server login.)
 */
class SettingsRepository(private val context: Context) {
    private val _config = MutableStateFlow<ServerConfig?>(null)
    val configFlow: Flow<ServerConfig?> = _config

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accountsFlow: Flow<List<Account>> = _accounts

    private val _activeAccount = MutableStateFlow<Account?>(null)
    val activeAccountFlow: Flow<Account?> = _activeAccount

    private val prefs: SharedPreferences by lazy {
        val p = context.getSharedPreferences("opus_prefs", Context.MODE_PRIVATE)
        _config.value = load(p)
        migrateAccounts(p)
        p
    }

    /** Safe to call from any thread; never throws. */
    fun ensureInit() {
        try {
            prefs // trigger lazy init
        } catch (_: Exception) {}
    }

    private fun load(p: SharedPreferences): ServerConfig? {
        return try {
            val url = p.getString(KEY_URL, "").orEmpty().trim()
            val user = p.getString(KEY_USER, "").orEmpty().trim()
            val pass = p.getString(KEY_PASS, "").orEmpty()
            if (url.isBlank() || user.isBlank() || pass.isEmpty()) null
            else ServerConfig(url, user, pass)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun save(url: String, username: String, password: String) {
        ensureInit()
        val existing = _accounts.value
        if (existing.isEmpty()) {
            // First login ever: create the first account (synchronous commit,
            // so a crash seconds later can't lose the credentials).
            addAccount("", url, username, password)
        } else {
            // Update the active account's credentials in place.
            val active = _activeAccount.value ?: existing.first()
            val updated = active.copy(
                baseUrl = url.trim(),
                username = username.trim(),
                password = password
            )
            persistAccounts(prefs, existing.map { if (it.id == active.id) updated else it }, active.id)
        }
    }

    suspend fun clear() {
        try {
            prefs.edit().clear().commit()
        } catch (_: Exception) {}
        _config.value = null
        _accounts.value = emptyList()
        _activeAccount.value = null
    }

    // --- Multiple accounts ---

    fun getAccounts(): List<Account> {
        ensureInit()
        return _accounts.value
    }

    fun getActiveAccount(): Account? {
        ensureInit()
        return _activeAccount.value
    }

    /**
     * Save a new account and make it active. Returns the created account.
     * Uses commit() so the credentials survive an immediate crash.
     */
    fun addAccount(label: String, url: String, username: String, password: String): Account {
        ensureInit()
        val acc = Account(
            id = java.util.UUID.randomUUID().toString(),
            label = label.ifBlank { accountLabel(username, url) },
            baseUrl = url.trim(),
            username = username.trim(),
            password = password
        )
        persistAccounts(prefs, _accounts.value + acc, acc.id)
        return acc
    }

    fun setActiveAccountId(id: String) {
        ensureInit()
        if (_accounts.value.any { it.id == id }) {
            persistAccounts(prefs, _accounts.value, id)
        }
    }

    /**
     * Remove an account. Returns the account that is now active
     * (null when no accounts remain).
     */
    fun removeAccount(id: String): Account? {
        ensureInit()
        val list = _accounts.value.filterNot { it.id == id }
        val next = if (_activeAccount.value?.id == id) list.firstOrNull() else _activeAccount.value
        persistAccounts(prefs, list, next?.id)
        return next
    }

    /**
     * One-time migration: pre-1.1.0 installs stored a single set of
     * credentials in server_url/username/password. Adopt them as the
     * first account; the legacy keys are left untouched.
     */
    private fun migrateAccounts(p: SharedPreferences) {
        try {
            val raw = p.getString(KEY_ACCOUNTS, null)
            if (raw != null) {
                val list = parseAccounts(raw)
                val activeId = p.getString(KEY_ACTIVE_ACCOUNT, null)
                val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
                _accounts.value = list
                _activeAccount.value = active
                _config.value = active?.toConfig()
                return
            }
            val url = p.getString(KEY_URL, "").orEmpty().trim()
            val user = p.getString(KEY_USER, "").orEmpty().trim()
            val pass = p.getString(KEY_PASS, "").orEmpty()
            if (url.isNotBlank() && user.isNotBlank() && pass.isNotEmpty()) {
                val acc = Account(
                    id = java.util.UUID.randomUUID().toString(),
                    label = accountLabel(user, url),
                    baseUrl = url,
                    username = user,
                    password = pass
                )
                persistAccounts(p, listOf(acc), acc.id)
            }
        } catch (_: Exception) {
        }
    }

    private fun persistAccounts(p: SharedPreferences, list: List<Account>, activeId: String?) {
        try {
            val arr = org.json.JSONArray()
            for (a in list) {
                arr.put(
                    org.json.JSONObject()
                        .put("id", a.id)
                        .put("label", a.label)
                        .put("url", a.baseUrl)
                        .put("user", a.username)
                        .put("pass", a.password)
                )
            }
            p.edit()
                .putString(KEY_ACCOUNTS, arr.toString())
                .putString(KEY_ACTIVE_ACCOUNT, activeId)
                .commit()
        } catch (_: Exception) {
        }
        _accounts.value = list
        val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
        _activeAccount.value = active
        _config.value = active?.toConfig()
    }

    private fun parseAccounts(raw: String): List<Account> {
        val out = ArrayList<Account>()
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                out.add(
                    Account(
                        id = id,
                        label = o.optString("label", "Server").ifEmpty { "Server" },
                        baseUrl = o.optString("url"),
                        username = o.optString("user"),
                        password = o.optString("pass")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    companion object {
        private const val KEY_URL = "server_url"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
        private const val KEY_BITRATE = "stream_bitrate" // 0 = original, else kbps
        private const val KEY_SCROBBLE = "scrobble_enabled"
        private const val KEY_SCREEN_LOCK = "prevent_screen_lock" // never | playing | always
        private const val KEY_GAPLESS = "gapless_enabled"
        private const val KEY_ARTWORK_HIGH = "artwork_high_quality"
        private const val KEY_CROSSFADE = "crossfade_sec" // 0 = off
        private const val KEY_SLEEP_FADE = "sleep_fade_min"
        private const val KEY_SLEEP_EOT = "sleep_end_of_track"
        private const val KEY_MIX_ENABLED = "offline_mix_enabled"
        private const val KEY_MIX_SIZE = "offline_mix_size" // -1 = unlimited
        private const val KEY_MIX_WIFI = "offline_mix_wifi_only"
        private const val KEY_THEME = "app_theme" // dark | light
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_PLAYER_LAYOUT = "player_layout" // classic | immersive
        private const val KEY_ACCOUNTS = "accounts_json"
        private const val KEY_ACTIVE_ACCOUNT = "active_account_id"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_EQ_PRESET = "eq_preset" // -1 = custom bands
        private const val KEY_EQ_BANDS = "eq_bands" // csv millibels, e.g. "0,0,0,0,0"
        private const val KEY_EQ_PREAMP = "eq_preamp" // millibels, -1200..1200
        private const val KEY_VOLUME_BOOST = "volume_boost" // millibels, 0..1000
        private const val KEY_SKIP_SILENCE = "skip_silence"
        private const val KEY_COMPRESSOR = "compressor" // 0 off, 1 gentle, 2 firm
        private const val KEY_HEADPHONE_PROFILE = "headphone_profile" // profile name or ""
        private const val KEY_HEADPHONE_PREV_BANDS = "headphone_prev_bands" // csv millibels

        /** Offline mix size sentinel meaning "no cap": all mix candidates. */
        const val MIX_UNLIMITED = -1
    }

    // --- App preferences (not credentials) ---

    fun getStreamBitrate(): Int = try {
        prefs.getInt(KEY_BITRATE, 0)
    } catch (_: Exception) { 0 }

    fun setStreamBitrate(kbps: Int) {
        try {
            prefs.edit().putInt(KEY_BITRATE, kbps).apply()
        } catch (_: Exception) {}
    }

    fun isScrobbleEnabled(): Boolean = try {
        prefs.getBoolean(KEY_SCROBBLE, true)
    } catch (_: Exception) { true }

    fun setScrobbleEnabled(enabled: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_SCROBBLE, enabled).apply()
        } catch (_: Exception) {}
    }

    // --- Amperfy-style settings ---

    /** never | playing | always */
    fun getPreventScreenLock(): String = try {
        prefs.getString(KEY_SCREEN_LOCK, "never") ?: "never"
    } catch (_: Exception) { "never" }

    fun setPreventScreenLock(value: String) {
        try {
            prefs.edit().putString(KEY_SCREEN_LOCK, value).apply()
        } catch (_: Exception) {}
    }

    fun isGaplessEnabled(): Boolean = try {
        prefs.getBoolean(KEY_GAPLESS, true)
    } catch (_: Exception) { true }

    fun setGaplessEnabled(enabled: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_GAPLESS, enabled).apply()
        } catch (_: Exception) {}
    }

    fun isArtworkHighQuality(): Boolean = try {
        prefs.getBoolean(KEY_ARTWORK_HIGH, true)
    } catch (_: Exception) { true }

    fun setArtworkHighQuality(enabled: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_ARTWORK_HIGH, enabled).apply()
        } catch (_: Exception) {}
    }

    // --- Crossfade ---

    /** Overlap between tracks in seconds; 0 = off. */
    fun getCrossfadeSec(): Int = try {
        prefs.getInt(KEY_CROSSFADE, 0)
    } catch (_: Exception) { 0 }

    fun setCrossfadeSec(sec: Int) {
        try {
            prefs.edit().putInt(KEY_CROSSFADE, sec.coerceIn(0, 12)).apply()
        } catch (_: Exception) {}
    }

    // --- Smart sleep fade ---

    /** Minutes over which the volume fades out before the timer ends. */
    fun getSleepFadeMin(): Int = try {
        prefs.getInt(KEY_SLEEP_FADE, 5)
    } catch (_: Exception) { 5 }

    fun setSleepFadeMin(min: Int) {
        try {
            prefs.edit().putInt(KEY_SLEEP_FADE, min.coerceIn(1, 15)).apply()
        } catch (_: Exception) {}
    }

    fun isSleepEndOfTrack(): Boolean = try {
        prefs.getBoolean(KEY_SLEEP_EOT, false)
    } catch (_: Exception) { false }

    fun setSleepEndOfTrack(enabled: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_SLEEP_EOT, enabled).apply()
        } catch (_: Exception) {}
    }

    // --- Smart Offline Mix ---

    fun isOfflineMixEnabled(): Boolean = try {
        prefs.getBoolean(KEY_MIX_ENABLED, false)
    } catch (_: Exception) { false }

    fun setOfflineMixEnabled(enabled: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_MIX_ENABLED, enabled).apply()
        } catch (_: Exception) {}
    }

    fun getOfflineMixSize(): Int = try {
        val v = prefs.getInt(KEY_MIX_SIZE, 25)
        if (v == MIX_UNLIMITED) MIX_UNLIMITED
        else when (v) { 25, 50, 100 -> v; else -> 25 }
    } catch (_: Exception) { 25 }

    /**
     * Size of the Smart Offline Mix: 25/50/100, or MIX_UNLIMITED (-1).
     * Any legacy/out-of-range value migrates to 25.
     */
    fun setOfflineMixSize(n: Int) {
        try {
            val v = if (n == MIX_UNLIMITED) MIX_UNLIMITED
                else when (n) { 25, 50, 100 -> n; else -> 25 }
            prefs.edit().putInt(KEY_MIX_SIZE, v).apply()
        } catch (_: Exception) {}
    }

    fun isOfflineMixWifiOnly(): Boolean = try {
        prefs.getBoolean(KEY_MIX_WIFI, true)
    } catch (_: Exception) { true }

    fun setOfflineMixWifiOnly(enabled: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_MIX_WIFI, enabled).apply()
        } catch (_: Exception) {}
    }

    // --- Appearance / theme ---

    /** "dark" | "light"; defaults to dark (the original jazzy dark theme). */
    fun getThemeMode(): String = try {
        val v = prefs.getString(KEY_THEME, "dark") ?: "dark"
        if (v == "light") "light" else "dark"
    } catch (_: Exception) { "dark" }

    fun setThemeMode(mode: String) {
        try {
            prefs.edit().putString(KEY_THEME, if (mode == "light") "light" else "dark").apply()
        } catch (_: Exception) {}
    }

    /** Material You dynamic color (Android 12+); defaults to off (brand theme). */
    fun isDynamicColor(): Boolean = try {
        prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
    } catch (_: Exception) { false }

    fun setDynamicColor(v: Boolean) {
        try { prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, v).apply() } catch (_: Exception) {}
    }

    /** Now Playing layout: "classic" (art card + text below) or "immersive"
     * (full-width art with the title overlaid on it). */
    fun getPlayerLayout(): String = try {
        val v = prefs.getString(KEY_PLAYER_LAYOUT, "classic") ?: "classic"
        if (v == "immersive") "immersive" else "classic"
    } catch (_: Exception) { "classic" }

    fun setPlayerLayout(v: String) {
        try {
            prefs.edit().putString(KEY_PLAYER_LAYOUT, if (v == "immersive") "immersive" else "classic").apply()
        } catch (_: Exception) {}
    }

    // --- Equalizer (built-in 5-band) ---

    fun isEqEnabled(): Boolean = try {
        prefs.getBoolean(KEY_EQ_ENABLED, false)
    } catch (_: Exception) { false }

    fun setEqEnabled(enabled: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_EQ_ENABLED, enabled).apply()
        } catch (_: Exception) {}
    }

    /** Preset index into EqualizerEngine.presets, or -1 for a custom curve. */
    fun getEqPreset(): Int = try {
        prefs.getInt(KEY_EQ_PRESET, -1)
    } catch (_: Exception) { -1 }

    /**
     * Band gains in millibels, size = EqualizerEngine.BAND_COUNT.
     * Falls back to the named preset's curve when bands were never customized.
     */
    fun getEqBands(): List<Int> {
        val count = EqualizerEngine.BAND_COUNT
        val max = EqualizerEngine.MAX_DB_MB
        return try {
            val raw = prefs.getString(KEY_EQ_BANDS, null)
            val parsed = raw?.split(",")?.map { it.trim().toIntOrNull() }
            if (parsed != null && parsed.size == count && parsed.all { it != null }) {
                parsed.filterNotNull().map { it.coerceIn(-max, max) }
            } else {
                val preset = getEqPreset()
                EqualizerEngine.presets.getOrNull(preset)?.bands ?: List(count) { 0 }
            }
        } catch (_: Exception) {
            List(count) { 0 }
        }
    }

    /** Save custom bands in millibels; clears the preset selection. */
    fun setEqBands(bands: List<Int>) {
        try {
            val count = EqualizerEngine.BAND_COUNT
            val max = EqualizerEngine.MAX_DB_MB
            val safe = List(count) { i ->
                bands.getOrElse(i) { 0 }.coerceIn(-max, max)
            }
            prefs.edit()
                .putString(KEY_EQ_BANDS, safe.joinToString(","))
                .putInt(KEY_EQ_PRESET, -1)
                .apply()
        } catch (_: Exception) {}
    }

    /** Apply a preset: updates both the preset index and the saved bands. */
    fun setEqPreset(index: Int) {
        try {
            val bands = EqualizerEngine.presets.getOrNull(index)?.bands
                ?: List(EqualizerEngine.BAND_COUNT) { 0 }
            prefs.edit()
                .putString(KEY_EQ_BANDS, bands.joinToString(","))
                .putInt(KEY_EQ_PRESET, index)
                .apply()
        } catch (_: Exception) {}
    }

    /** Preamp gain in millibels, -1200..+1200 (±12 dB). */
    fun getEqPreamp(): Int = try {
        prefs.getInt(KEY_EQ_PREAMP, 0).coerceIn(-1200, 1200)
    } catch (_: Exception) { 0 }

    fun setEqPreamp(mb: Int) {
        try {
            prefs.edit().putInt(KEY_EQ_PREAMP, mb.coerceIn(-1200, 1200)).apply()
        } catch (_: Exception) {}
    }

    /** Volume boost in millibels, 0..1000 (+10 dB max). */
    fun getVolumeBoost(): Int = try {
        prefs.getInt(KEY_VOLUME_BOOST, 0).coerceIn(0, 1000)
    } catch (_: Exception) { 0 }

    fun setVolumeBoost(mb: Int) {
        try {
            prefs.edit().putInt(KEY_VOLUME_BOOST, mb.coerceIn(0, 1000)).apply()
        } catch (_: Exception) {}
    }

    fun isSkipSilence(): Boolean = try {
        prefs.getBoolean(KEY_SKIP_SILENCE, false)
    } catch (_: Exception) { false }

    fun setSkipSilence(on: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_SKIP_SILENCE, on).apply()
        } catch (_: Exception) {}
    }

    /** Compressor preset: 0 off, 1 gentle, 2 firm. */
    fun getCompressor(): Int = try {
        prefs.getInt(KEY_COMPRESSOR, 0).coerceIn(0, 2)
    } catch (_: Exception) { 0 }

    fun setCompressor(preset: Int) {
        try {
            prefs.edit().putInt(KEY_COMPRESSOR, preset.coerceIn(0, 2)).apply()
        } catch (_: Exception) {}
    }

    /** Selected AutoEQ headphone profile name, or "" for none. */
    fun getHeadphoneProfile(): String = try {
        prefs.getString(KEY_HEADPHONE_PROFILE, "") ?: ""
    } catch (_: Exception) { "" }

    fun setHeadphoneProfile(name: String) {
        try {
            prefs.edit().putString(KEY_HEADPHONE_PROFILE, name).apply()
        } catch (_: Exception) {}
    }

    /**
     * The user's own EQ bands stashed when a headphone profile was applied,
     * so picking "None" can restore them. Null when nothing is stashed.
     */
    fun getHeadphonePrevBands(): List<Int>? = try {
        val count = EqualizerEngine.BAND_COUNT
        val raw = prefs.getString(KEY_HEADPHONE_PREV_BANDS, null) ?: return null
        val parsed = raw.split(",").map { it.trim().toIntOrNull() }
        if (parsed.size == count && parsed.all { it != null }) parsed.filterNotNull() else null
    } catch (_: Exception) { null }

    fun setHeadphonePrevBands(bands: List<Int>?) {
        try {
            val e = prefs.edit()
            if (bands == null) e.remove(KEY_HEADPHONE_PREV_BANDS)
            else e.putString(KEY_HEADPHONE_PREV_BANDS, bands.joinToString(","))
            e.apply()
        } catch (_: Exception) {}
    }

    // --- Swipe actions ---

    fun getSwipeAction(key: String): String {
        val def = SwipeActions.defaultFor(key)
        return try {
            val v = prefs.getString(key, def) ?: def
            if (SwipeActions.allowedFor(key).contains(v)) v else def
        } catch (_: Exception) { def }
    }

    fun setSwipeAction(key: String, action: String) {
        if (!SwipeActions.allowedFor(key).contains(action)) return
        try { prefs.edit().putString(key, action).apply() } catch (_: Exception) {}
    }
}
