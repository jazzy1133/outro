package com.opus.music.data

/**
 * Action ids for configurable swipe gestures, plus the preference keys
 * that store each gesture's chosen action.
 */
object SwipeActions {
    // Actions
    const val NONE = "none"
    const val OPEN_PLAYER = "open_player"
    const val PLAY_PAUSE = "play_pause"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val PLAY_NEXT = "play_next"
    const val ADD_TO_QUEUE = "add_queue"
    const val DOWNLOAD = "download"

    // Preference keys (also used as dialog identifiers in settings UI)
    const val KEY_MINI_UP = "swipe_mini_up"
    const val KEY_MINI_DOWN = "swipe_mini_down"
    const val KEY_MINI_LEFT = "swipe_mini_left"
    const val KEY_MINI_RIGHT = "swipe_mini_right"
    const val KEY_SONG_LEFT = "swipe_song_left"
    const val KEY_SONG_RIGHT = "swipe_song_right"

    fun label(action: String): String = when (action) {
        OPEN_PLAYER -> "Open player"
        PLAY_PAUSE -> "Play / pause"
        NEXT -> "Next track"
        PREVIOUS -> "Previous track"
        PLAY_NEXT -> "Play next"
        ADD_TO_QUEUE -> "Add to queue"
        DOWNLOAD -> "Download"
        else -> "None"
    }

    /** Actions that make sense on the mini player. */
    val MINI_PLAYER_ACTIONS = listOf(NONE, OPEN_PLAYER, PLAY_PAUSE, NEXT, PREVIOUS)

    /** Actions that make sense swiping a song row. */
    val SONG_ROW_ACTIONS = listOf(NONE, PLAY_NEXT, ADD_TO_QUEUE, DOWNLOAD)

    fun allowedFor(key: String): List<String> =
        if (key.startsWith("swipe_mini")) MINI_PLAYER_ACTIONS else SONG_ROW_ACTIONS

    fun defaultFor(key: String): String = when (key) {
        KEY_MINI_UP -> OPEN_PLAYER
        KEY_MINI_DOWN -> NONE
        KEY_MINI_LEFT -> NEXT
        KEY_MINI_RIGHT -> PREVIOUS
        KEY_SONG_LEFT -> PLAY_NEXT
        KEY_SONG_RIGHT -> ADD_TO_QUEUE
        else -> NONE
    }

    fun gestureLabel(key: String): String = when (key) {
        KEY_MINI_UP -> "Swipe up"
        KEY_MINI_DOWN -> "Swipe down"
        KEY_MINI_LEFT -> "Swipe left"
        KEY_MINI_RIGHT -> "Swipe right"
        KEY_SONG_LEFT -> "Swipe left on a song"
        KEY_SONG_RIGHT -> "Swipe right on a song"
        else -> key
    }
}
