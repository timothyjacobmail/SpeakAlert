package com.ghostgramlabs.speakalert.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews

/** Keep one-cell action widgets usable, including when the launcher rotates. */
internal object ActionWidgetLayouts {
    fun create(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        compactLayout: Int,
        expandedLayout: Int,
        bind: RemoteViews.() -> Unit
    ): RemoteViews {
        fun views(layout: Int) = RemoteViews(context.packageName, layout).apply(bind)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return RemoteViews(mapOf(
                SizeF(40f, 40f) to views(compactLayout),
                SizeF(146f, 170f) to views(expandedLayout)
            ))
        }

        val options = manager.getAppWidgetOptions(widgetId)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 146)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 170)
        return views(if (width >= 146 && height >= 170) expandedLayout else compactLayout)
    }
}
