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
            val s = Store(c)
            val title = if (s.name.isBlank() && s.cid.isBlank()) "No claim" else listOf("Initiated", "Survey done", "Docs uploaded", "Settled")[s.stage.coerceIn(0, 3)]
            val v = RemoteViews(c.packageName, R.layout.widget_hinc)
            v.setTextViewText(R.id.w_title, title)
            v.setTextViewText(R.id.w_latest, if (s.plan >= 0) "\$${s.plan}" else "--")
            v.setTextViewText(R.id.w_avg, if (s.pay >= 0) "\$${s.pay}" else "--")
            v.setTextViewText(R.id.w_tir, s.docs().size.toString())
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
