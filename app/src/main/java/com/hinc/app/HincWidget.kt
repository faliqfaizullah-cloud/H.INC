package com.hinc.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.widget.RemoteViews
import kotlin.math.cos
import kotlin.math.min

class HincWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, build(c)) }
    }

    companion object {
        private fun routeBitmap(c: Context, pts: List<Pt>): Bitmap {
            val d = c.resources.displayMetrics.density
            val w = (150 * d).toInt(); val h = (64 * d).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (pts.size < 2) return bmp
            val pad = 12 * d
            val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
            val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
            val kx = cos(Math.toRadians((minLat + maxLat) / 2))
            val spanX = ((maxLon - minLon) * kx).coerceAtLeast(1e-6)
            val spanY = (maxLat - minLat).coerceAtLeast(1e-6)
            val scale = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY)
            val ox = (w - spanX * scale) / 2.0
            val oy = (h - spanY * scale) / 2.0
            fun x(p: Pt) = (ox + (p.lon - minLon) * kx * scale).toFloat()
            fun y(p: Pt) = (oy + (maxLat - p.lat) * scale).toFloat()
            val path = Path()
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p), y(p)) else path.lineTo(x(p), y(p)) }
            val cv = Canvas(bmp)
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 3.5f * d; color = Color.argb(110, 255, 255, 255)
                strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; maskFilter = BlurMaskFilter(4f * d, BlurMaskFilter.Blur.NORMAL)
            }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.4f * d; color = Color.argb(240, 255, 255, 255)
                strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            }
            cv.drawPath(path, glow); cv.drawPath(path, line)
            val e = pts.last(); val ex = x(e); val ey = y(e)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(90, 255, 255, 255) }
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = Color.argb(210, 255, 255, 255) }
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(244, 245, 138) }
            cv.drawCircle(ex, ey, 7f * d, fill); cv.drawCircle(ex, ey, 7f * d, ring); cv.drawCircle(ex, ey, 3f * d, dot)
            return bmp
        }

        fun build(c: Context): RemoteViews {
            val s = Store(c)
            val r = s.runs().firstOrNull()
            val v = RemoteViews(c.packageName, R.layout.widget_hinc)
            v.setTextViewText(R.id.w_title, if (r != null) runTitle(r.t) else "Start a run")
            v.setTextViewText(R.id.w_dist, if (r != null) fmtDist(r.dist, s.unit) else "0.00")
            v.setTextViewText(R.id.w_unit, " " + unitLabel(s.unit))
            v.setTextViewText(R.id.w_time, if (r != null) fmtTime(r.secs) else "0:00")
            v.setTextViewText(R.id.w_bpm, if (r != null && r.bpm > 0) "${r.bpm} bpm" else "-- bpm")
            v.setTextViewText(R.id.w_pace, if (r != null) fmtPace(r.dist, r.secs, s.unit) else "--:--")
            v.setImageViewBitmap(R.id.w_route, routeBitmap(c, r?.pts ?: emptyList()))
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
