package com.opus.music.lyrics

/** One synced lyric line. */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Parses LRC (synced lyrics) format, e.g. "[00:12.34]Hello".
 * Pure logic — unit-testable on the JVM.
 */
object LrcParser {
    // [mm:ss.xx] where xx is centiseconds (2 digits) or milliseconds (3 digits).
    private val TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{2,3}))?]""")
    private val META = Regex("""\[(ar|ti|al|au|by|offset|length):""")

    fun parse(lrc: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in lrc.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (META.containsMatchIn(line) && !TAG.containsMatchIn(line)) continue
            val tags = TAG.findAll(line).toList()
            if (tags.isEmpty()) continue
            val text = TAG.replace(line, "").trim()
            if (text.isEmpty()) continue
            for (t in tags) {
                val min = t.groupValues[1].toLongOrNull() ?: continue
                val sec = t.groupValues[2].toLongOrNull() ?: continue
                val frac = t.groupValues[3]
                val ms = when (frac.length) {
                    2 -> frac.toLongOrNull()?.times(10) ?: 0L   // centiseconds
                    3 -> frac.toLongOrNull() ?: 0L               // milliseconds
                    else -> 0L
                }
                out.add(LyricLine(min * 60_000 + sec * 1000 + ms, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Index of the line that should be highlighted at [positionMs] (-1 if none yet). */
    fun lineAt(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var res = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                res = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return res
    }

    /** Quick check: does this text look like synced (LRC) lyrics? */
    fun looksSynced(text: String): Boolean = TAG.containsMatchIn(text)
}
