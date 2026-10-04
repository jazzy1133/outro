package com.opus.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/** Home-screen widget: current track + prev/play/next. */
class OutroWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetUpdater.refresh(context)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdater.refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetActions.ACTION_TOGGLE,
            WidgetActions.ACTION_NEXT,
            WidgetActions.ACTION_PREV -> {
                WidgetUpdater.sendAction(context, intent.action!!)
                // Optimistic refresh; the service refreshes again after acting.
                WidgetUpdater.refresh(context)
                return
            }
        }
        super.onReceive(context, intent)
    }
}
