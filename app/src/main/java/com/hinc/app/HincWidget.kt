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
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.widget.RemoteViews
import kotlin.math.cos
import kotlin.math.min

class HincWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, build(c)) }
    }

    companion object {
        /** Draws the whole frosted-glass card (route, orb, title, distance, stats) as one bitmap. */
        private fun card(c: Context, unit: Int, r: Run?): Bitmap {
            val d = c.resources.displayMetrics.density
            val s = (190 * d).toInt().coerceAtMost(620)
            val sf = s.toFloat()
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val rad = sf * 0.17f
            val rect = RectF(d, d, sf - d, sf - d)
            val clip = Path().apply { addRoundRect(rect, rad, rad, Path.Direction.CW) }
            cv.save(); cv.clipPath(clip)

            // frosted mint glass: translucent base + soft blurred colour blobs
            val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, sf, intArrayOf(Color.argb(175, 168, 208, 172), Color.argb(190, 140, 196, 148), Color.argb(170, 208, 232, 212)),
                    floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            }
            cv.drawRect(0f, 0f, sf, sf, base)
            fun blob(x: Float, y: Float, rr: Float, col: Int) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = RadialGradient(x * sf, y * sf, rr * sf, col, col and 0x00FFFFFF, Shader.TileMode.CLAMP)
                cv.drawCircle(x * sf, y * sf, rr * sf, p)
            }
            blob(.42f, .80f, .36f, Color.argb(150, 255, 255, 255))
            blob(.58f, .30f, .30f, Color.argb(80, 100, 140, 215))
            blob(.18f, .52f, .28f, Color.argb(80, 60, 140, 80))
            blob(.88f, .88f, .25f, Color.argb(70, 240, 240, 170))
            val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, sf * .3f, Color.argb(60, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            }
            cv.drawRect(0f, 0f, sf, sf * .3f, hi)

            // route trace
            var ox = .54f * sf; var oy = .16f * sf
            val pts = r?.pts ?: emptyList()
            if (pts.size >= 2) {
                val l = .22f * sf; val t = .10f * sf; val w = .56f * sf; val h = .40f * sf
                val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
                val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
                val kx = cos(Math.toRadians((minLat + maxLat) / 2))
                val spanX = ((maxLon - minLon) * kx).coerceAtLeast(1e-6)
                val spanY = (maxLat - minLat).coerceAtLeast(1e-6)
                val scale = min(w / spanX, h / spanY)
                val offX = l + (w - spanX * scale) / 2.0
                val offY = t + (h - spanY * scale) / 2.0
                fun px(p: Pt) = (offX + (p.lon - minLon) * kx * scale).toFloat()
                fun py(p: Pt) = (offY + (maxLat - p.lat) * scale).toFloat()
                val path = Path()
                pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p), py(p)) else path.lineTo(px(p), py(p)) }
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 4f * d; color = Color.argb(110, 255, 255, 255)
                    strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; maskFilter = BlurMaskFilter(4f * d, BlurMaskFilter.Blur.NORMAL)
                }
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(245, 255, 255, 255)
                    strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
                }
                cv.drawPath(path, glow); cv.drawPath(path, line)
                ox = px(pts.last()); oy = py(pts.last())
            }

            // glass orb with petal ring and glowing yellow centre
            val orbR = .055f * sf
            cv.drawCircle(ox, oy, orbR, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(85, 255, 255, 255) })
            cv.drawCircle(ox, oy, orbR, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = Color.argb(215, 255, 255, 255) })
            cv.drawCircle(ox, oy, orbR * .62f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.argb(200, 255, 255, 255)
                pathEffect = DashPathEffect(floatArrayOf(.03f * sf, .018f * sf), 0f)
            })
            cv.drawCircle(ox, oy, orbR * .5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 245, 245, 150); maskFilter = BlurMaskFilter(3f * d, BlurMaskFilter.Blur.NORMAL)
            })
            cv.drawCircle(ox, oy, orbR * .27f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 246, 150) })

            // text
            val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fun tp(sz: Float, tf: Typeface, a: Int, al: Paint.Align) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = sz; typeface = tf; color = Color.argb(a, 255, 255, 255); textAlign = al
                setShadowLayer(3f * d, 0f, d, Color.argb(70, 0, 60, 30))
            }
            val x0 = .097f * sf
            val dist = if (r != null) fmtDist(r.dist, unit) else "0.00"
            cv.drawText(if (r != null) runTitle(r.t, r.kind) else "Start a run", x0, .69f * sf, tp(.045f * sf, light, 235, Paint.Align.LEFT))
            val big = tp(.15f * sf, thin, 255, Paint.Align.LEFT)
            val smudge = Paint(big).apply { clearShadowLayer(); alpha = 120; maskFilter = BlurMaskFilter(.012f * sf, BlurMaskFilter.Blur.NORMAL) }
            cv.drawText(dist, x0, .85f * sf + .014f * sf, smudge)
            cv.drawText(dist, x0, .85f * sf, big)
            cv.drawText(unitLabel(unit), x0 + big.measureText(dist) + .02f * sf, .85f * sf, tp(.032f * sf, light, 190, Paint.Align.LEFT))
            val rt = tp(.045f * sf, light, 240, Paint.Align.RIGHT)
            val rx = .90f * sf
            cv.drawText(if (r != null) fmtTime(r.secs) else "0:00", rx, .69f * sf, rt)
            cv.drawText(if (r != null && r.bpm > 0) "${r.bpm} bpm" else "-- bpm", rx, .765f * sf, rt)
            cv.drawText(if (r != null) fmtPace(r.dist, r.secs, unit) else "--:--", rx, .84f * sf, rt)

            cv.restore()
            // glass rim: bright top-left edge fading to a warm tint at the bottom-right
            cv.drawRoundRect(rect, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.6f * d
                shader = LinearGradient(0f, 0f, sf, sf, intArrayOf(Color.argb(190, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(130, 235, 236, 160)), null, Shader.TileMode.CLAMP)
            })
            return bmp
        }

        fun build(c: Context): RemoteViews {
            val s = Store(c)
            val v = RemoteViews(c.packageName, R.layout.widget_hinc)
            v.setImageViewBitmap(R.id.w_card, card(c, s.unit, s.runs().firstOrNull()))
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
