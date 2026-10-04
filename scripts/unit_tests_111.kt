import com.opus.music.audiobook.AudiobookManager
import com.opus.music.data.MixCandidate
import com.opus.music.data.SongMeta
import com.opus.music.lyrics.LrcParser
import com.opus.music.mixes.SmartMixes
import com.opus.music.network.Song

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("  ok: $name") else { println("  FAIL: $name"); failures++ }
}

fun meta(id: String, title: String = "T$id") =
    SongMeta(id, title, "artist", "album", null, 180, "mp3")

fun cand(id: String, plays: Int, title: String = "T$id") =
    MixCandidate(meta(id, title), plays, starred = false)

fun song(id: String, genre: String? = null, duration: Int = 180) =
    Song(id = id, title = "T$id", genre = genre, duration = duration)

fun main() {
    println("== LrcParser.parse ==")
    val lrc = """
        [ti:Title]
        [00:10.00]Hello
        [00:20.50][00:30.00]Chorus line
        [01:00.00]End
    """.trimIndent()
    val lines = LrcParser.parse(lrc)
    check("parses 4 lines (multi-timestamp expands)", lines.size == 4)
    check("first line time 10000", lines[0].timeMs == 10_000L && lines[0].text == "Hello")
    check("multi timestamps expand", lines[1].timeMs == 20_500L && lines[2].timeMs == 30_000L
        && lines[1].text == "Chorus line" && lines[2].text == "Chorus line")
    check("sorted by time", lines.map { it.timeMs } == lines.map { it.timeMs }.sorted())
    check("ignores metadata tags", lines.none { it.text.startsWith("[ti:") })
    check("blank input -> empty", LrcParser.parse("").isEmpty())
    check("plain text -> empty", LrcParser.parse("just some words\nno timestamps").isEmpty())

    println("== LrcParser.lineAt ==")
    check("before first -> -1", LrcParser.lineAt(lines, 5_000) == -1)
    check("exact first", LrcParser.lineAt(lines, 10_000) == 0)
    check("between -> earlier", LrcParser.lineAt(lines, 15_000) == 0)
    check("second line", LrcParser.lineAt(lines, 21_000) == 1)
    check("last line", LrcParser.lineAt(lines, 120_000) == 3)
    check("empty list -> -1", LrcParser.lineAt(emptyList(), 999) == -1)

    println("== SmartMixes.heavyRotation ==")
    val cands = listOf(
        cand("a", 50), cand("b", 30), cand("c", 10), cand("d", 5), cand("e", 1)
    )
    val heavy = SmartMixes.heavyRotation(cands, 3)
    check("top N by plays", heavy.map { it.id } == listOf("a", "b", "c"))
    check("empty -> empty", SmartMixes.heavyRotation(emptyList(), 5).isEmpty())
    check("limit 0 -> empty", SmartMixes.heavyRotation(cands, 0).isEmpty())

    println("== SmartMixes.forgottenFavorites ==")
    val starred = setOf("a", "c", "e")
    val forgotten = SmartMixes.forgottenFavorites(cands, starred, 10)
    check("only starred", forgotten.all { it.id in starred })
    check("least played first", forgotten.map { it.id } == listOf("e", "c", "a"))
    check("no starred -> empty", SmartMixes.forgottenFavorites(cands, emptySet(), 5).isEmpty())

    println("== SmartMixes.dailyMix ==")
    val daily1 = SmartMixes.dailyMix(cands, starred, 10, seed = 42L)
    val daily2 = SmartMixes.dailyMix(cands, starred, 10, seed = 42L)
    check("deterministic for same seed", daily1.map { it.id } == daily2.map { it.id })
    check("non-empty within limit", daily1.isNotEmpty() && daily1.size <= 10)
    check("no dupes", daily1.map { it.id }.toSet().size == daily1.size)
    check("empty candidates -> empty", SmartMixes.dailyMix(emptyList(), emptySet(), 5).isEmpty())
    val big = (1..200).map { cand("s$it", it % 20) }
    check("large pool capped at limit",
        SmartMixes.dailyMix(big, (1..200).map { "s$it" }.toSet(), 50, seed = 7L).size == 50)

    println("== AudiobookManager.isAudiobook ==")
    check("audiobook genre", AudiobookManager.isAudiobook(song("1", genre = "Audiobook")))
    check("podcast genre", AudiobookManager.isAudiobook(song("2", genre = "Podcasts")))
    check("spoken word", AudiobookManager.isAudiobook(song("3", genre = "Spoken Word")))
    check("music genre is not", !AudiobookManager.isAudiobook(song("4", genre = "Rock")))
    check("short track is not",
        !AudiobookManager.isAudiobook(song("5", genre = null, duration = 180)))
    check("long track counts",
        AudiobookManager.isAudiobook(song("6", genre = null, duration = 3600)))

    println("== AudiobookManager speeds ==")
    check("speed steps", AudiobookManager.SPEEDS.toList() ==
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f))
    check("next speed after 1x", AudiobookManager.nextSpeed(1f) == 1.25f)
    check("next speed wraps at max", AudiobookManager.nextSpeed(3f) == 0.5f)
    check("next speed snaps unknown", AudiobookManager.nextSpeed(1.1f) == 1.25f)

    if (failures > 0) {
        println("FAILURES: $failures")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}
