package com.opus.music.mixes

import com.opus.music.data.MixCandidate
import com.opus.music.data.SongMeta
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure smart-mix builders over local listening stats.
 * Unit-testable on the JVM; UI-agnostic.
 */
object SmartMixes {

    /** "Heavy Rotation": most-played songs, most-played first. */
    fun heavyRotation(candidates: List<MixCandidate>, limit: Int): List<SongMeta> =
        candidates.sortedWith(
            compareByDescending<MixCandidate> { it.playCount }
                .thenBy { it.meta.title.lowercase() }
        ).take(limit.coerceAtLeast(0)).map { it.meta }

    /**
     * "Forgotten Favorites": starred songs the user barely plays anymore.
     * Starred songs with the LOWEST play counts come first.
     */
    fun forgottenFavorites(
        candidates: List<MixCandidate>,
        starredIds: Set<String>,
        limit: Int
    ): List<SongMeta> =
        candidates
            .filter { it.meta.id in starredIds }
            .sortedWith(
                compareBy<MixCandidate> { it.playCount }
                    .thenBy { it.meta.title.lowercase() }
            )
            .take(limit.coerceAtLeast(0))
            .map { it.meta }

    /**
     * "Daily Mix": a deterministic-per-day shuffle weighted toward favorites.
     * Starred songs are 3x more likely to be picked. Same seed (day) -> same mix.
     */
    fun dailyMix(
        candidates: List<MixCandidate>,
        starredIds: Set<String>,
        limit: Int,
        seed: Long = System.currentTimeMillis() / 86_400_000L
    ): List<SongMeta> {
        if (candidates.isEmpty() || limit <= 0) return emptyList()
        val rng = Random(seed)
        // Weighted pool: starred entries appear 3x.
        val pool = ArrayList<MixCandidate>(candidates.size * 2)
        for (c in candidates) {
            pool.add(c)
            if (c.meta.id in starredIds) { pool.add(c); pool.add(c) }
            repeat(min(c.playCount, 5)) { pool.add(c) }
        }
        val picked = LinkedHashSet<String>()
        val out = ArrayList<SongMeta>(limit)
        var guard = 0
        while (out.size < limit && guard++ < pool.size * 4) {
            val c = pool[rng.nextInt(pool.size)]
            if (picked.add(c.meta.id)) out.add(c.meta)
        }
        return out
    }
}
