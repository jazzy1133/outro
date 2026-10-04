package com.opus.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.opus.music.player.EngineHolder
import com.opus.music.player.PlayerService

/**
 * Renders the home-screen widget from the live player state.
 * Reads [EngineHolder.exoPlayer] directly so it works whether or not the
 * app's UI (and its MediaController binding) is alive.
 *
 * Resource IDs are resolved via [Resources.getIdentifier] because this app's
 * own R class is generated after Kotlin compilation, so Kotlin sources cannot
 * reference R directly.
 */
object WidgetUpdater {

    private data class Ids(
        val layout: Int,
        val root: Int,
        val text: Int,
        val title: Int,
        val artist: Int,
        val prev: Int,
        val playPause: Int,
        val next: Int,
        val playIcon: Int,
        val pauseIcon: Int
    )

    @Volatile
    private var cached: Ids? = null

    private fun ids(res: Resources, pkg: String): Ids =
        cached ?: synchronized(this) {
            cached ?: Ids(
                layout = res.getIdentifier("widget_outro", "layout", pkg),
                root = res.getIdentifier("widget_root", "id", pkg),
                text = res.getIdentifier("widget_text", "id", pkg),
                title = res.getIdentifier("widget_title", "id", pkg),
                artist = res.getIdentifier("widget_artist", "id", pkg),
                prev = res.getIdentifier("widget_prev", "id", pkg),
                playPause = res.getIdentifier("widget_play_pause", "id", pkg),
                next = res.getIdentifier("widget_next", "id", pkg),
                playIcon = res.getIdentifier("ic_widget_play", "drawable", pkg),
                pauseIcon = res.getIdentifier("ic_widget_pause", "drawable", pkg)
            ).also { cached = it }
        }

    fun refresh(context: Context) {
        try {
            val appContext = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appContext)
            val ids = mgr.getAppWidgetIds(
                ComponentName(appContext, OutroWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val player = EngineHolder.exoPlayer
            val playing = player?.isPlaying == true
            val meta = player?.currentMediaItem?.mediaMetadata
            val title = meta?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Outro"
            val artist = meta?.artist?.toString().orEmpty()
            val r = ids(appContext.resources, appContext.packageName)
            if (r.layout == 0 || r.title == 0) return
            for (id in ids) {
                val views = RemoteViews(appContext.packageName, r.layout)
                views.setTextViewText(r.title, title)
                views.setTextViewText(r.artist, artist)
                val icon = if (playing) r.pauseIcon else r.playIcon
                if (icon != 0) views.setImageViewResource(r.playPause, icon)
                views.setContentDescription(
                    r.playPause,
                    if (playing) "Pause" else "Play"
                )
                views.setOnClickPendingIntent(
                    r.prev,
                    serviceIntent(appContext, WidgetActions.ACTION_PREV, id, 1)
                )
                views.setOnClickPendingIntent(
                    r.playPause,
                    serviceIntent(appContext, WidgetActions.ACTION_TOGGLE, id, 2)
                )
                views.setOnClickPendingIntent(
                    r.next,
                    serviceIntent(appContext, WidgetActions.ACTION_NEXT, id, 3)
                )
                // Tapping the text opens the app.
                val launch = appContext.packageManager
                    .getLaunchIntentForPackage(appContext.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    views.setOnClickPendingIntent(
                        r.text,
                        PendingIntent.getActivity(
                            appContext, id, launch,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                }
                mgr.updateAppWidget(id, views)
            }
        } catch (_: Exception) {
        }
    }

    private fun serviceIntent(
        context: Context,
        action: String,
        widgetId: Int,
        salt: Int
    ): PendingIntent {
        val intent = Intent(context, PlayerService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            widgetId * 31 + salt,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Entry point used by [OutroWidgetProvider] for transport taps. */
    fun sendAction(context: Context, action: String) {
        try {
            val svc = Intent(context, PlayerService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, svc)
        } catch (_: Exception) {
            // Last resort: open the app so the user can control playback.
            try {
                val launch = context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launch != null) context.startActivity(launch)
            } catch (_: Exception) {
            }
        }
    }
}
