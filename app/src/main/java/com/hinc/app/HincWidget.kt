package com.hinc.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class HincWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, build(c)) }
    }

    companion object {
        fun build(c: Context): RemoteViews {
            val all = Store(c).readings()
            val now = System.currentTimeMillis()
            val today = all.filter { sameDay(it.t, now) }
            val s = stats(if (today.isEmpty()) all.sortedBy { it.t }.takeLast(10) else today)
            val last = s.last
            val title = when {
                last == null -> "No data"
                last < 70 -> "Low"
                last <= 140 -> "In range"
                else -> "High"
            }
            val v = RemoteViews(c.packageName, R.layout.widget_hinc)
            v.setTextViewText(R.id.w_title, title)
            v.setTextViewText(R.id.w_latest, if (last != null) "$last mg/dL" else "--")
            v.setTextViewText(R.id.w_avg, s.avg?.let { "$it mg/dL" } ?: "--")
            v.setTextViewText(R.id.w_tir, s.tir?.let { "$it%" } ?: "--")
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.w_root, open)
            return v
        }

        fun refresh(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c, HincWidget::class.java)).forEach { m.updateAppWidget(it, build(c)) }
        }
    }
}
