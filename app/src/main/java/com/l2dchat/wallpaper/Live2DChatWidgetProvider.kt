package com.l2dchat.wallpaper

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.l2dchat.R
import com.l2dchat.logging.L2DLogger
import com.l2dchat.logging.LogModule

class Live2DChatWidgetProvider : AppWidgetProvider() {
    private val logger = L2DLogger.module(LogModule.WIDGET)

    override fun onReceive(context: Context, intent: Intent) {
    logger.debug(
        "onReceive action=${intent.action}",
        throttleMs = 1_000L,
        throttleKey = "widget_receive"
    )
        super.onReceive(context, intent)
    }

    override fun onUpdate(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    logger.debug("onUpdate ids=${appWidgetIds.joinToString()}")
        updateAllWidgets(context)
    }

    companion object {
        private val logger = L2DLogger.module(LogModule.WIDGET)
        fun updateAllWidgets(context: Context) {
        logger.debug(
            "updateAllWidgets invoked",
            throttleMs = 1_000L,
            throttleKey = "widget_update_all"
        )
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, Live2DChatWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) {
        logger.debug(
            "No widget ids registered",
            throttleMs = 2_000L,
            throttleKey = "widget_no_ids"
        )
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
            logger.info("Last preview text='$last', widgetCount=${ids.size}")
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_live2d_chat)
                views.setTextViewText(R.id.widget_hint, last)
                val intent =
                        Intent(context, WidgetInputActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        }
        logger.debug(
            "Preparing PendingIntent for widgetId=$id",
            throttleMs = 1_000L,
            throttleKey = "widget_pending_intent"
        )
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
