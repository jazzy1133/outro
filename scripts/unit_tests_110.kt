import com.opus.music.data.Account
import com.opus.music.data.MixCandidate
import com.opus.music.data.OfflineMixSelector
import com.opus.music.data.SettingsRepository
import com.opus.music.data.SongMeta
import com.opus.music.data.SwipeActions
import com.opus.music.data.accountLabel
import com.opus.music.player.EqualizerEngine

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("  ok: $name") else { println("  FAIL: $name"); failures++ }
}

fun meta(id: String, title: String = "T$id") =
    SongMeta(id, title, "artist", "album", null, 180, "mp3")

fun main() {
    println("== accountLabel ==")
    check("user@host", accountLabel("alice", "https://music.example.com:4533/rest") == "alice @ music.example.com")
    check("ip host", accountLabel("bob", "http://192.168.1.5") == "bob @ 192.168.1.5")
    check("no scheme", accountLabel("carol", "music.example.com/rest") == "carol @ music.example.com")
    check("blank user -> host only", accountLabel("", "https://x.example.com") == "x.example.com")
    check("blank url -> user only", accountLabel("dave", "") == "dave")
    check("whitespace trimmed", accountLabel("  eve  ", "https://h.example.com/ ") == "eve @ h.example.com")

    println("== Account ==")
    val acc = Account("id1", "L", "https://h.example.com", "u", "p")
    val cfg = acc.toConfig()
    check("toConfig maps fields", cfg.baseUrl == "https://h.example.com" && cfg.username == "u" && cfg.password == "p")

    println("== SwipeActions ==")
    check("default mini up = open player", SwipeActions.defaultFor(SwipeActions.KEY_MINI_UP) == SwipeActions.OPEN_PLAYER)
    check("default mini down = none", SwipeActions.defaultFor(SwipeActions.KEY_MINI_DOWN) == SwipeActions.NONE)
    check("default mini left = next", SwipeActions.defaultFor(SwipeActions.KEY_MINI_LEFT) == SwipeActions.NEXT)
    check("default mini right = previous", SwipeActions.defaultFor(SwipeActions.KEY_MINI_RIGHT) == SwipeActions.PREVIOUS)
    check("default song left = play next", SwipeActions.defaultFor(SwipeActions.KEY_SONG_LEFT) == SwipeActions.PLAY_NEXT)
    check("default song right = add to queue", SwipeActions.defaultFor(SwipeActions.KEY_SONG_RIGHT) == SwipeActions.ADD_TO_QUEUE)
    check("mini actions are safe subset",
        SwipeActions.allowedFor(SwipeActions.KEY_MINI_UP) == SwipeActions.MINI_PLAYER_ACTIONS)
    check("song actions are queue/download subset",
        SwipeActions.allowedFor(SwipeActions.KEY_SONG_LEFT) == SwipeActions.SONG_ROW_ACTIONS)
    check("download not allowed on mini player",
        !SwipeActions.allowedFor(SwipeActions.KEY_MINI_LEFT).contains(SwipeActions.DOWNLOAD))
    check("open-player not allowed on song rows",
        !SwipeActions.allowedFor(SwipeActions.KEY_SONG_LEFT).contains(SwipeActions.OPEN_PLAYER))
    check("label none", SwipeActions.label(SwipeActions.NONE) == "None")
    check("label open player", SwipeActions.label(SwipeActions.OPEN_PLAYER) == "Open player")
    check("label unknown -> None", SwipeActions.label("bogus") == "None")
    check("gesture labels", SwipeActions.gestureLabel(SwipeActions.KEY_MINI_UP) == "Swipe up"
        && SwipeActions.gestureLabel(SwipeActions.KEY_SONG_RIGHT) == "Swipe right on a song")

    println("== EqualizerEngine ==")
    check("5 bands", EqualizerEngine.BAND_COUNT == 5)
    check("max 1500 mB", EqualizerEngine.MAX_DB_MB == 1500)
    check("5 center frequencies", EqualizerEngine.BAND_FREQUENCIES_HZ.size == 5
        && EqualizerEngine.BAND_FREQUENCIES_HZ.first() == 60
        && EqualizerEngine.BAND_FREQUENCIES_HZ.last() == 14000)
    check("presets non-empty", EqualizerEngine.presets.isNotEmpty())
    check("every preset has 5 bands",
        EqualizerEngine.presets.all { it.bands.size == EqualizerEngine.BAND_COUNT })
    check("Flat preset is all zeros",
        EqualizerEngine.presets.first().name == "Flat"
            && EqualizerEngine.presets.first().bands.all { it == 0 })
    check("preset names unique",
        EqualizerEngine.presets.map { it.name }.toSet().size == EqualizerEngine.presets.size)
    val bands = listOf(100, 200, 300, 400, 500)
    check("identity map for 5 device bands",
        EqualizerEngine.mapToDeviceBands(bands, 5) == bands)
    check("resample down to 3 bands",
        EqualizerEngine.mapToDeviceBands(bands, 3) == listOf(100, 200, 400))
    check("resample up to 10 bands",
        EqualizerEngine.mapToDeviceBands(bands, 10) ==
            listOf(100, 100, 200, 200, 300, 300, 400, 400, 500, 500))
    check("clamps to +-1500 mB",
        EqualizerEngine.mapToDeviceBands(listOf(9000, -9000, 0, 0, 0), 5) ==
            listOf(1500, -1500, 0, 0, 0))
    check("zero device bands -> empty", EqualizerEngine.mapToDeviceBands(bands, 0).isEmpty())
    check("short app list pads with 0",
        EqualizerEngine.mapToDeviceBands(listOf(100), 5) == listOf(100, 0, 0, 0, 0))

    println("== OfflineMixSelector unlimited ==")
    val cands = listOf(
        MixCandidate(meta("s1"), playCount = 10, starred = false),
        MixCandidate(meta("s2"), playCount = 5, starred = true),
        MixCandidate(meta("s3"), playCount = 1, starred = false)
    )
    val starred = setOf("s2")
    check("MIX_UNLIMITED sentinel is -1", SettingsRepository.MIX_UNLIMITED == -1)
    val unlim = OfflineMixSelector.selectMix(cands, starred, emptySet(), SettingsRepository.MIX_UNLIMITED)
    check("unlimited takes every candidate", unlim.size == 3)
    check("unlimited keeps starred-first order",
        unlim.map { it.id } == listOf("s2", "s1", "s3"))
    val capped = OfflineMixSelector.selectMix(cands, starred, emptySet(), 2)
    check("cap of 2 still enforced", capped.size == 2 && capped.map { it.id } == listOf("s2", "s1"))
    check("zero -> empty", OfflineMixSelector.selectMix(cands, starred, emptySet(), 0).isEmpty())
    check("downloaded excluded even when unlimited",
        OfflineMixSelector.selectMix(cands, starred, setOf("s1", "s2", "s3"), -1).isEmpty())

    println(if (failures == 0) "ALL UNIT TESTS PASSED" else "$failures UNIT TEST(S) FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
