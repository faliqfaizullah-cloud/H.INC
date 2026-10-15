package com.hinc.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sqrt

/** Foreground service: watches for walking / running in the background and records the route automatically. */
class MotionService : Service(), SensorEventListener, LocationListener {
    companion object {
        const val STOP_RUN = "com.hinc.app.STOP_RUN"
        private const val CH = "hinc_motion"
        fun start(c: Context) { ContextCompat.startForegroundService(c, Intent(c, MotionService::class.java)) }
        fun stop(c: Context) { c.stopService(Intent(c, MotionService::class.java)) }
        fun stopRun(c: Context) { c.startService(Intent(c, MotionService::class.java).setAction(STOP_RUN)) }
    }

    private lateinit var store: Store
    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private lateinit var nm: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private val stepTimes = ArrayDeque<Long>()
    private var hasStepSensor = false
    private var dev = 0f
    private var act = 0
    private var runTicks = 0
    private var walkTicks = 0
    private var idleTicks = 0
    private var nonRunTicks = 0
    private var tickN = 0
    private var tracking = false
    private var kind = 0
    private var secs = 0
    private var idleSecs = 0
    private var dist = 0f
    private var last: Location? = null
    private val pts = ArrayList<Pt>()

    private val ticker = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, 2000) }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Background motion", NotificationManager.IMPORTANCE_LOW))
        val sd = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        hasStepSensor = sd != null
        try {
            if (sd != null) sm.registerListener(this, sd, SensorManager.SENSOR_DELAY_NORMAL)
            else sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        } catch (e: Exception) {}
        handler.postDelayed(ticker, 2000)
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == STOP_RUN) { if (tracking) finishSession(true); return START_STICKY }
        val n = notif("Watching for movement")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var t = 0
                if (has(Manifest.permission.ACCESS_FINE_LOCATION)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.ACTIVITY_RECOGNITION)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                if (t == 0) { stopSelf(); return START_NOT_STICKY }
                startForeground(1, n, t)
            } else startForeground(1, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        sm.unregisterListener(this)
        if (tracking) finishSession(true)
        store.sp.edit().putInt("svcAct", 0).putBoolean("svcTrk", false).apply()
        super.onDestroy()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> stepTimes.addLast(System.currentTimeMillis())
            Sensor.TYPE_ACCELEROMETER -> {
                val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                dev = 0.97f * dev + 0.03f * abs(m - 9.81f)
            }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun tick() {
        val now = System.currentTimeMillis()
        while (stepTimes.isNotEmpty() && now - stepTimes.first() > 10_000) stepTimes.removeFirst()
        act = if (hasStepSensor) {
            val spm = stepTimes.size * 6
            if (spm >= 140) 2 else if (spm >= 40) 1 else 0
        } else if (dev > 3.2f) 2 else if (dev > 0.9f) 1 else 0
        if (act == 2) { runTicks++; nonRunTicks = 0 } else { runTicks = 0; nonRunTicks++ }
        walkTicks = if (act == 1) walkTicks + 1 else 0
        idleTicks = if (act == 0) idleTicks + 1 else 0
        if (tracking) {
            secs += 2
            idleSecs = if (act == 0) idleSecs + 2 else 0
            if (kind == 1 && act == 2 && runTicks >= 5) kind = 0
            if (idleTicks >= 30 || (kind == 0 && nonRunTicks >= 90)) finishSession(false)
        } else if (!store.sp.getBoolean("appTracking", false) && has(Manifest.permission.ACCESS_FINE_LOCATION) &&
            (runTicks >= 8 || walkTicks >= 25)) {
            startSession(if (act == 2) 0 else 1)
        }
        publish()
        tickN++
        if (tickN % 3 == 0) nm.notify(1, notif(null))
    }

    private fun startSession(k: Int) {
        tracking = true; kind = k; secs = 0; idleSecs = 0; dist = 0f; last = null; pts.clear()
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 3f, this, Looper.getMainLooper()) } catch (e: Exception) {}
    }

    private fun finishSession(manual: Boolean) {
        try { lm.removeUpdates(this) } catch (e: Exception) {}
        val s = if (manual) secs else (secs - idleSecs).coerceAtLeast(0)
        val ok = if (manual) (dist >= 20f || s >= 10) else dist >= 150f
        if (ok) {
            val r = Run(System.currentTimeMillis(), dist, s, -1, pts.toList(), kind)
            store.saveRuns(listOf(r) + store.runs())
            HincWidget.refresh(this)
        }
        tracking = false
        publish()
    }

    override fun onLocationChanged(l: Location) {
        if (!tracking) return
        if (l.hasAccuracy() && l.accuracy > 35f) return
        val prev = last
        if (prev == null) { last = l; pts.add(Pt(l.latitude, l.longitude)) }
        else {
            val d = prev.distanceTo(l)
            if (d >= 3f) { dist += d; last = l; pts.add(Pt(l.latitude, l.longitude)) }
        }
    }

    private fun publish() {
        store.sp.edit().putInt("svcAct", act).putBoolean("svcTrk", tracking).putFloat("svcDist", dist)
            .putInt("svcSecs", secs).putInt("svcKind", kind).apply()
    }

    private fun notif(text: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val label = listOf("Idle", "Walking", "Running")[act.coerceIn(0, 2)]
        val body = text ?: if (tracking)
            (if (kind == 1) "Walk" else "Run") + " · " + fmtDist(dist, store.unit) + " " + unitLabel(store.unit) + " · " + fmtTime(secs)
        else "Watching for movement · $label"
        val b = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("H.INC").setContentText(body).setContentIntent(open).setOngoing(true)
        if (tracking) {
            val stop = PendingIntent.getService(this, 2, Intent(this, MotionService::class.java).setAction(STOP_RUN), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(android.R.drawable.ic_media_pause, "Stop & save", stop)
        }
        return b.build()
    }
}
