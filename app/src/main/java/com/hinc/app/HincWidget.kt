package com.hinc.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale

class HincWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, build(c)) }
    }

    companion object {
        fun build(c: Context): RemoteViews {
            val s = Store(c)
            val week = s.runs().filter { it.t >= weekStart(System.currentTimeMillis()) }
            val dist = week.sumOf { it.dist.toDouble() }.toFloat()
            val v = RemoteViews(c.packageName, R.layout.widget_hinc)
            v.setTextViewText(R.id.w_title, "Run")
            v.setTextViewText(R.id.w_latest, String.format(Locale.US, "%.1f %s", toUnit(dist, s.unit), unitLabel(s.unit).lowercase()))
            v.setTextViewText(R.id.w_avg, week.size.toString())
            v.setTextViewText(R.id.w_tir, if (s.goal > 0) "${s.goal}" + (if (s.unit == 0) "K" else "M") else "--")
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
