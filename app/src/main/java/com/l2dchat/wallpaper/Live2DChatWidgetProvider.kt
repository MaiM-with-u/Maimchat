package com.l2dchat.wallpaper

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.l2dchat.R

class Live2DChatWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive action=${intent.action}")
        super.onReceive(context, intent)
    }

    override fun onUpdate(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Log.d(TAG, "onUpdate ids=${appWidgetIds.joinToString()}")
        updateAllWidgets(context)
    }

    companion object {
        private const val TAG = "Live2DWidget"

        fun updateAllWidgets(context: Context) {
            Log.d(TAG, "updateAllWidgets invoked")
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, Live2DChatWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) {
                Log.d(TAG, "No widget ids registered")
                return
            }
            val sp =
                    context.getSharedPreferences(
                            WallpaperComm.PREF_WIDGET_INPUT,
                            Context.MODE_PRIVATE
                    )
            val last =
                    sp.getString(WallpaperComm.PREF_WIDGET_LAST_INPUT_KEY, null)?.takeIf {
                        it.isNotBlank()
                    }
                            ?: "点击输入并发送"
            Log.d(TAG, "Last preview text='$last', widgetCount=${ids.size}")
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_live2d_chat)
                views.setTextViewText(R.id.widget_hint, last)
                val intent =
                        Intent(context, WidgetInputActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        }
                Log.d(TAG, "Preparing PendingIntent for widgetId=$id")
                val pi =
                        PendingIntent.getActivity(
                                context,
                                id,
                                intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                views.setOnClickPendingIntent(R.id.widget_send_button, pi)
                views.setOnClickPendingIntent(R.id.widget_hint, pi)
                views.setOnClickPendingIntent(R.id.widget_title, pi)
                views.setOnClickPendingIntent(R.id.widget_input_row, pi)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
