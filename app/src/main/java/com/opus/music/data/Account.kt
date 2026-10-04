package com.opus.music.data

/**
 * One saved Navidrome/Subsonic login. Users can keep several accounts
 * (home server, friend's server, demo…) and switch between them.
 */
data class Account(
    val id: String,
    val label: String,
    val baseUrl: String,
    val username: String,
    val password: String
) {
    fun toConfig() = ServerConfig(baseUrl, username, password)
}

/**
 * Default display label for an account: "user @ host".
 * Pure function so it can be unit-tested.
 */
fun accountLabel(username: String, baseUrl: String): String {
    val host = baseUrl
        .substringAfter("://", baseUrl)
        .substringBefore("/")
        .substringBefore(":")
        .trim()
    val u = username.trim()
    return when {
        u.isNotEmpty() && host.isNotEmpty() -> "$u @ $host"
        u.isNotEmpty() -> u
        host.isNotEmpty() -> host
        else -> baseUrl.trim().ifEmpty { "Server" }
    }
}
