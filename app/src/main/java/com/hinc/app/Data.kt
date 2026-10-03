package com.hinc.app

import android.app.*
import android.content.*
import java.util.Calendar
import kotlin.math.*

data class Reading(val t: Long, val v: Int)
data class Stats(val last: Int?, val avg: Int?, val tir: Int?, val cv: Double?, val spikes: Int?)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("hinc", Context.MODE_PRIVATE)
    fun readings(): List<Reading> = (sp.getString("r", "") ?: "").split(";").mapNotNull {
        val p = it.split(":")
        val t = p.getOrNull(0)?.toLongOrNull()
        val v = p.getOrNull(1)?.toIntOrNull()
        if (t != null && v != null) Reading(t, v) else null
    }
    fun add(v: Int): List<Reading> {
        sp.edit().putString("r", (sp.getString("r", "") ?: "") + "${System.currentTimeMillis()}:$v;").apply()
        return readings()
    }
    var vitD: Int
        get() = sp.getInt("d", -1)
        set(x) { sp.edit().putInt("d", x).apply() }
    var remOn: Boolean
        get() = sp.getBoolean("on", false)
        set(x) { sp.edit().putBoolean("on", x).apply() }
    var remHours: Int
        get() = sp.getInt("rh", 4)
        set(x) { sp.edit().putInt("rh", x).apply() }
    var remH: Int
        get() = sp.getInt("hh", 8)
        set(x) { sp.edit().putInt("hh", x).apply() }
    var remM: Int
        get() = sp.getInt("mm", 0)
        set(x) { sp.edit().putInt("mm", x).apply() }
}

fun stats(list: List<Reading>): Stats {
    if (list.isEmpty()) return Stats(null, null, null, null, null)
    val v = list.sortedBy { it.t }.map { it.v }
    val mean = v.average()
    val sd = sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
    val tir = v.count { it in 70..140 } * 100 / v.size
    val sp = if (v.size < 2) 0 else (1 until v.size).count { v[it] - v[it - 1] >= 30 } * 100 / (v.size - 1)
    return Stats(v.last(), mean.roundToInt(), tir, sd / mean * 100, sp)
}

fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

fun scheduleReminder(c: Context, on: Boolean, hours: Int, h: Int, m: Int) {
    val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pi = PendingIntent.getBroadcast(c, 1, Intent(c, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    am.cancel(pi)
    if (!on) return
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
        while (timeInMillis <= System.currentTimeMillis()) add(Calendar.HOUR_OF_DAY, hours)
    }
    am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, hours * 3600000L, pi)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("hinc", "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1, Notification.Builder(c, "hinc").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("H.INC").setContentText("Time to log your glucose reading")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
