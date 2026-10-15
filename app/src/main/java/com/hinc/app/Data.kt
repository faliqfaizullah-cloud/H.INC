package com.hinc.app

import android.app.*
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

data class Pt(val lat: Double, val lon: Double)
data class Scan(val t: Long, val hr: Int, val rmssd: Int)
data class Run(val t: Long, val dist: Float, val secs: Int, val bpm: Int, val pts: List<Pt>, val kind: Int = 0)

class Store(ctx: Context) {
    val sp = ctx.getSharedPreferences("hinc_run", Context.MODE_PRIVATE)
    var bgOn: Boolean
        get() = sp.getBoolean("bgOn", false)
        set(x) { sp.edit().putBoolean("bgOn", x).apply() }
    var goal: Int
        get() = sp.getInt("goal", 0)
        set(x) { sp.edit().putInt("goal", x).apply() }
    var plan: Int
        get() = sp.getInt("plan", 0)
        set(x) { sp.edit().putInt("plan", x).apply() }
    var unit: Int
        get() = sp.getInt("unit", 0)
        set(x) { sp.edit().putInt("unit", x).apply() }
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(x) { sp.edit().putInt("theme", x).apply() }
    var remOn: Boolean
        get() = sp.getBoolean("remOn", false)
        set(x) { sp.edit().putBoolean("remOn", x).apply() }
    var remH: Int
        get() = sp.getInt("remH", 7)
        set(x) { sp.edit().putInt("remH", x).apply() }
    var remM: Int
        get() = sp.getInt("remM", 0)
        set(x) { sp.edit().putInt("remM", x).apply() }

    var tempC: Float
        get() = sp.getFloat("tempC", 0f)
        set(x) { sp.edit().putFloat("tempC", x).apply() }
    var scanLen: Int
        get() = sp.getInt("scanLen", 30)
        set(x) { sp.edit().putInt("scanLen", x).apply() }
    fun scans(): List<Scan> = runCatching {
        val a = JSONArray(sp.getString("scans", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Scan(o.getLong("t"), o.getInt("h"), o.getInt("r")) }
    }.getOrDefault(emptyList())
    fun saveScans(l: List<Scan>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.t).put("h", it.hr).put("r", it.rmssd)) }
        sp.edit().putString("scans", a.toString()).apply()
    }

    fun runs(): List<Run> = runCatching {
        val a = JSONArray(sp.getString("runs", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val pts = o.getString("p").split(";").mapNotNull { s ->
                val q = s.split(",")
                if (q.size == 2) Pt(q[0].toDouble(), q[1].toDouble()) else null
            }
            Run(o.getLong("t"), o.getDouble("d").toFloat(), o.getInt("s"), o.getInt("b"), pts, o.optInt("k", 0))
        }
    }.getOrDefault(emptyList())

    fun saveRuns(l: List<Run>) {
        val a = JSONArray()
        l.forEach { r ->
            val step = if (r.pts.size > 400) r.pts.size / 400 + 1 else 1
            val p = r.pts.filterIndexed { i, _ -> i % step == 0 }
                .joinToString(";") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) }
            a.put(JSONObject().put("t", r.t).put("d", r.dist.toDouble()).put("s", r.secs).put("b", r.bpm).put("k", r.kind).put("p", p))
        }
        sp.edit().putString("runs", a.toString()).apply()
    }
}

fun weekStart(now: Long): Long {
    val c = Calendar.getInstance()
    c.firstDayOfWeek = Calendar.MONDAY
    c.timeInMillis = now
    c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}
fun dayIndex(t: Long): Int = (Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_WEEK) + 5) % 7
fun dayKey(t: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    return c.get(Calendar.YEAR) * 10000 + c.get(Calendar.MONTH) * 100 + c.get(Calendar.DAY_OF_MONTH)
}
fun toUnit(m: Float, unit: Int): Float = if (unit == 0) m / 1000f else m / 1609.344f
fun unitLabel(unit: Int) = if (unit == 0) "KM" else "MI"

fun scheduleReminder(c: Context, on: Boolean, h: Int, m: Int) {
    val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pi = PendingIntent.getBroadcast(c, 1, Intent(c, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    am.cancel(pi)
    if (!on) return
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
    }
    am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("hinc", "Run reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1, Notification.Builder(c, "hinc").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("H.INC").setContentText("Time for your run")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
