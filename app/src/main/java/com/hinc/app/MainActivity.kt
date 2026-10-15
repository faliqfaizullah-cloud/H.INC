package com.hinc.app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

val Lav1 = Color(0xFFB7AEDB)
val Lav2 = Color(0xFFD9D3E6)
val Lav3 = Color(0xFFEFEFF2)
val LimeC = Color(0xFFE6F56B)
val GlassB = Brush.linearGradient(listOf(Color.White.copy(.7f), Color.White.copy(.1f)))

data class Theme(val top: Color, val mid: Color, val bot: Color, val ink: Color)
val THEMES = listOf(
    Theme(Color(0xFF3A3A2A), Color(0xFFF6F78A), Color(0xFFEBCBC4), Color(0xFF111111)),
    Theme(Color(0xFF2E5A3A), Color(0xFF9CCB9F), Color(0xFFDDEBDD), Color(0xFF0F1A12)),
    Theme(Color(0xFF1B2A8F), Color(0xFF6C7CF0), Color(0xFFD6D9F6), Color(0xFFFFFFFF)),
    Theme(Color(0xFF3B2A6B), Color(0xFFC9A6F2), Color(0xFFF3D6EE), Color(0xFF1B1030))
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { HincApp(applicationContext) }
    }
}

data class Shown(val title: String, val dist: Float, val secs: Int, val bpm: Int, val pts: List<Pt>)

fun runTitle(t: Long, kind: Int = 0): String {
    val h = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)
    val p = when (h) { in 5..11 -> "Morning"; in 12..16 -> "Afternoon"; in 17..20 -> "Evening"; else -> "Night" }
    return p + if (kind == 1) " Walk" else " Run"
}
fun fmtTime(s: Int): String = String.format(Locale.US, "%d:%02d", s / 60, s % 60)
fun fmtDist(m: Float, unit: Int): String = String.format(Locale.US, "%.2f", toUnit(m, unit))
fun fmtPace(m: Float, secs: Int, unit: Int): String =
    if (m >= 50f && secs > 0) fmtTime((secs / toUnit(m, unit)).toInt()) + " / " + unitLabel(unit).lowercase().replaceFirstChar { it.uppercase() } else "--:--"

// ---------- app state: runs, plan, live tracking, motion ----------
class Ui(val c: Context) {
    val s = Store(c)
    var runs by mutableStateOf(s.runs())
    var goal by mutableStateOf(s.goal)
    var plan by mutableStateOf(s.plan)
    var unit by mutableStateOf(s.unit)
    var theme by mutableStateOf(s.theme)
    var remOn by mutableStateOf(s.remOn)
    var remH by mutableStateOf(s.remH)
    var remM by mutableStateOf(s.remM)
    var selected by mutableStateOf(-1)
    var bgOn by mutableStateOf(s.bgOn)
    var bgAct by mutableStateOf(0)
    var bgTrk by mutableStateOf(false)
    var bgDist by mutableStateOf(0f)
    var bgSecs by mutableStateOf(0)
    var bgKind by mutableStateOf(0)
    private val prefL = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "runs" -> { runs = s.runs() }
            "svcAct", "svcTrk", "svcDist", "svcSecs", "svcKind" -> { syncBg() }
        }
    }
    fun syncBg() {
        bgAct = s.sp.getInt("svcAct", 0); bgTrk = s.sp.getBoolean("svcTrk", false) && bgOn
        bgDist = s.sp.getFloat("svcDist", 0f); bgSecs = s.sp.getInt("svcSecs", 0); bgKind = s.sp.getInt("svcKind", 0)
    }
    fun attach() { s.sp.registerOnSharedPreferenceChangeListener(prefL); runs = s.runs(); syncBg() }
    fun detach() { s.sp.unregisterOnSharedPreferenceChangeListener(prefL) }
    fun setBg(on: Boolean) {
        s.bgOn = on; bgOn = on
        if (on) MotionService.start(c) else { MotionService.stop(c); bgTrk = false; bgAct = 0 }
    }

    var tracking by mutableStateOf(false)
    var secs by mutableStateOf(0)
    var dist by mutableStateOf(0f)
    var pts by mutableStateOf<List<Pt>>(emptyList())
    var speed by mutableStateOf(-1f)
    var bpm by mutableStateOf(-1)
    var level by mutableStateOf(0f)

    private var bpmSum = 0
    private var bpmN = 0
    private var last: Location? = null
    private var dev = 0f
    private val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val hasHr = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null

    private val locL = object : LocationListener {
        override fun onLocationChanged(l: Location) { onLoc(l) }
    }
    private val accL = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            dev = 0.92f * dev + 0.08f * abs(m - 9.81f)
            var lv = ((dev - 0.6f) / 4.5f).coerceIn(0f, 1f)
            if (tracking && speed >= 0f) lv = maxOf(lv, (speed / 3.2f).coerceIn(0f, 1f))
            if (abs(lv - level) > 0.02f) level = lv
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
    private val hrL = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values[0].toInt()
            if (v > 0) { bpm = v; bpmSum += v; bpmN++ }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun startSensors() { sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(accL, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stopSensors() { sm.unregisterListener(accL) }

    private fun onLoc(l: Location) {
        if (!tracking) return
        if (l.hasAccuracy() && l.accuracy > 35f) return
        speed = if (l.hasSpeed()) l.speed else -1f
        val prev = last
        if (prev == null) { last = l; pts = pts + Pt(l.latitude, l.longitude) }
        else {
            val d = prev.distanceTo(l)
            if (d >= 3f) { dist += d; last = l; pts = pts + Pt(l.latitude, l.longitude) }
        }
    }

    fun start() {
        tracking = true; secs = 0; dist = 0f; pts = emptyList(); last = null; speed = -1f
        s.sp.edit().putBoolean("appTracking", true).apply()
        bpmSum = 0; bpmN = 0; bpm = -1
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locL) } catch (e: Exception) {}
        try { sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { sm.registerListener(hrL, it, SensorManager.SENSOR_DELAY_NORMAL) } } catch (e: Exception) {}
    }

    fun stop() {
        tracking = false
        s.sp.edit().putBoolean("appTracking", false).apply()
        try { lm.removeUpdates(locL) } catch (e: Exception) {}
        sm.unregisterListener(hrL)
        speed = -1f
        if (secs >= 10 || dist >= 20f) {
            val r = Run(System.currentTimeMillis(), dist, secs, if (bpmN > 0) bpmSum / bpmN else -1, pts)
            runs = listOf(r) + runs; s.saveRuns(runs); selected = 0
            HincWidget.refresh(c)
        }
    }

    fun deleteRun(i: Int) { runs = runs.filterIndexed { k, _ -> k != i }; selected = -1; s.saveRuns(runs); HincWidget.refresh(c) }
    fun clearRuns() { runs = emptyList(); selected = -1; s.saveRuns(runs); HincWidget.refresh(c) }
    fun savePrefs() {
        s.goal = goal; s.plan = plan; s.unit = unit; s.theme = theme
        s.remOn = remOn; s.remH = remH; s.remM = remM
        HincWidget.refresh(c)
    }
    fun weekRuns(): List<Run> = runs.filter { it.t >= weekStart(System.currentTimeMillis()) }
    fun shown(): Shown? =
        if (tracking) Shown(runTitle(System.currentTimeMillis()), dist, secs, bpm, pts)
        else if (bgTrk) Shown(runTitle(System.currentTimeMillis(), bgKind), bgDist, bgSecs, -1, emptyList())
        else runs.getOrNull(if (selected in runs.indices) selected else 0)?.let {
            Shown(runTitle(it.t, it.kind), it.dist, it.secs, it.bpm, it.pts)
        }
}

// ---------- shared UI ----------
@Composable
fun T(s: String, size: TextUnit = 16.sp, color: Color = Color.White, weight: FontWeight = FontWeight.Light,
      modifier: Modifier = Modifier, align: TextAlign? = null) =
    Text(s, modifier = modifier, color = color, fontSize = size, fontWeight = weight,
        fontFamily = FontFamily.SansSerif, letterSpacing = (-0.3).sp, textAlign = align)

@Composable
fun BigText(s: String, size: TextUnit, color: Color, shadow: Boolean = false) =
    Text(s, color = color, fontSize = size, fontWeight = FontWeight.Thin, fontFamily = FontFamily.SansSerif,
        letterSpacing = (-1).sp,
        style = if (shadow) TextStyle(shadow = Shadow(Color(0x40000000), Offset(0f, 4f), 16f)) else TextStyle.Default)

@Composable
fun GlassCircle(sz: Dp = 46.dp, fill: Color = Color.White.copy(.28f), onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(sz).clip(CircleShape).background(fill).border(1.dp, GlassB, CircleShape).clickable { onClick() },
        Alignment.Center, content = content)
}

@Composable
fun Ico(kind: String, color: Color, sz: Dp = 24.dp) {
    Canvas(Modifier.size(sz)) {
        val w = size.width
        val st = Stroke(w * .075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            "home" -> {
                val p = Path().apply { moveTo(w * .18f, w * .5f); lineTo(w * .5f, w * .2f); lineTo(w * .82f, w * .5f) }
                drawPath(p, color, style = st)
                drawRoundRect(color, Offset(w * .28f, w * .5f), Size(w * .44f, w * .32f), androidx.compose.ui.geometry.CornerRadius(w * .06f), style = st)
            }
            "activity" -> {
                drawRoundRect(color, Offset(w * .12f, w * .16f), Size(w * .76f, w * .68f), androidx.compose.ui.geometry.CornerRadius(w * .14f), style = st)
                val p = Path().apply { moveTo(w * .26f, w * .62f); lineTo(w * .44f, w * .46f); lineTo(w * .56f, w * .56f); lineTo(w * .74f, w * .36f) }
                drawPath(p, color, style = st)
            }
            "calendar" -> {
                drawRoundRect(color, Offset(w * .14f, w * .22f), Size(w * .72f, w * .64f), androidx.compose.ui.geometry.CornerRadius(w * .12f), style = st)
                drawLine(color, Offset(w * .14f, w * .42f), Offset(w * .86f, w * .42f), st.width)
                drawLine(color, Offset(w * .34f, w * .12f), Offset(w * .34f, w * .28f), st.width, StrokeCap.Round)
                drawLine(color, Offset(w * .66f, w * .12f), Offset(w * .66f, w * .28f), st.width, StrokeCap.Round)
            }
            "bars" -> {
                drawLine(color, Offset(w * .26f, w * .8f), Offset(w * .26f, w * .55f), st.width * 1.4f, StrokeCap.Round)
                drawLine(color, Offset(w * .5f, w * .8f), Offset(w * .5f, w * .4f), st.width * 1.4f, StrokeCap.Round)
                drawLine(color, Offset(w * .74f, w * .8f), Offset(w * .74f, w * .22f), st.width * 1.4f, StrokeCap.Round)
            }
            "record" -> drawCircle(color, w * .38f, center, style = Stroke(st.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * .1f, w * .08f))))
            "stop" -> drawRoundRect(color, Offset(w * .28f, w * .28f), Size(w * .44f, w * .44f), androidx.compose.ui.geometry.CornerRadius(w * .08f))
            "bell" -> {
                drawArc(color, 180f, 180f, false, Offset(w * .26f, w * .2f), Size(w * .48f, w * .5f), style = st)
                drawLine(color, Offset(w * .26f, w * .45f), Offset(w * .22f, w * .72f), st.width, StrokeCap.Round)
                drawLine(color, Offset(w * .74f, w * .45f), Offset(w * .78f, w * .72f), st.width, StrokeCap.Round)
                drawLine(color, Offset(w * .22f, w * .72f), Offset(w * .78f, w * .72f), st.width, StrokeCap.Round)
                drawCircle(color, w * .06f, Offset(w * .5f, w * .84f))
            }
            "gear" -> {
                drawCircle(color, w * .17f, center, style = st)
                for (i in 0 until 8) {
                    val a = i * Math.PI.toFloat() / 4f
                    drawLine(color, center + Offset(kotlin.math.cos(a), kotlin.math.sin(a)) * (w * .28f),
                        center + Offset(kotlin.math.cos(a), kotlin.math.sin(a)) * (w * .4f), st.width * 1.3f, StrokeCap.Round)
                }
                drawCircle(color, w * .28f, center, style = st)
            }
            "flower" -> for (i in 0 until 4) {
                val a = i * Math.PI.toFloat() / 2f
                drawCircle(color, w * .2f, center + Offset(kotlin.math.cos(a), kotlin.math.sin(a)) * (w * .2f))
            }
            "face" -> {
                val l = w * .14f; val m = w * .32f
                for (sx in listOf(1f, -1f)) for (sy in listOf(1f, -1f)) {
                    val cx = if (sx > 0) l else w - l; val cy = if (sy > 0) l else w - l
                    val pth = Path().apply { moveTo(cx, cy + sy * m * .6f); lineTo(cx, cy); lineTo(cx + sx * m * .6f, cy) }
                    drawPath(pth, color, style = st)
                }
                drawOval(color, Offset(w * .33f, w * .28f), Size(w * .34f, w * .44f), style = st)
            }
            "check" -> {
                val p = Path().apply { moveTo(w * .22f, w * .52f); lineTo(w * .42f, w * .72f); lineTo(w * .78f, w * .3f) }
                drawPath(p, color, style = Stroke(w * .12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
fun NavBar(m: Modifier, tab: Int, tracking: Boolean, onTab: (Int) -> Unit, onRecord: () -> Unit) {
    val names = listOf("home", "activity", "record", "calendar", "bars")
    Row(m.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(.28f))
        .border(1.dp, GlassB, RoundedCornerShape(50)).padding(horizontal = 8.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically) {
        names.forEachIndexed { i, n ->
            val rec = i == 2
            val sel = i == tab && !rec
            val bg = when { rec && tracking -> Color(0xFFE5484D); sel -> Color(0xFF2B2B2B); else -> Color.White.copy(.35f) }
            Box(Modifier.size(54.dp).clip(CircleShape).background(bg).border(1.dp, GlassB, CircleShape)
                .clickable { if (rec) onRecord() else onTab(i) }, Alignment.Center) {
                Ico(if (rec && tracking) "stop" else n, Color.White, 24.dp)
            }
        }
    }
}

// ---------- root ----------
@Composable
fun HincApp(ctx: Context) {
    val ui = remember { Ui(ctx) }
    val act = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var dlg by remember { mutableStateOf("") }
    var face by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val tick: () -> Unit = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val thud: () -> Unit = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val view = LocalView.current
    SideEffect { view.keepScreenOn = ui.tracking }
    DisposableEffect(Unit) { ui.startSensors(); ui.attach(); onDispose { ui.stopSensors(); ui.detach() } }
    LaunchedEffect(Unit) { if (ui.bgOn) MotionService.start(ctx) }
    LaunchedEffect(ui.tracking) { while (ui.tracking) { delay(1000); ui.secs = ui.secs + 1 } }
    BackHandler(face || tab != 0 || dlg.isNotEmpty()) { if (face) face = false else if (dlg.isNotEmpty()) dlg = "" else tab = 0 }

    val fine = Manifest.permission.ACCESS_FINE_LOCATION
    val perms = if (ui.hasHr) arrayOf(fine, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BODY_SENSORS)
                else arrayOf(fine, Manifest.permission.ACCESS_COARSE_LOCATION)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[fine] == true) { ui.start(); thud(); tab = 0 }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun has(p: String) = ContextCompat.checkSelfPermission(act, p) == PackageManager.PERMISSION_GRANTED
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val locOk = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val arOk = Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACTIVITY_RECOGNITION)
        if (locOk && arOk) ui.setBg(true)
        else android.widget.Toast.makeText(act, "Location and activity permissions are needed", android.widget.Toast.LENGTH_LONG).show()
    }
    val onBg: (Boolean) -> Unit = { on ->
        if (!on) ui.setBg(false)
        else {
            val need = ArrayList<String>()
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) { need.add(Manifest.permission.ACCESS_FINE_LOCATION); need.add(Manifest.permission.ACCESS_COARSE_LOCATION) }
            if (Build.VERSION.SDK_INT >= 29 && !has(Manifest.permission.ACTIVITY_RECOGNITION)) need.add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) need.add(Manifest.permission.POST_NOTIFICATIONS)
            if (need.isEmpty()) ui.setBg(true) else bgLauncher.launch(need.toTypedArray())
        }
    }
    val onBattery: () -> Unit = {
        try { act.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) {}
    }
    val onRecord: () -> Unit = {
        thud()
        if (ui.tracking) ui.stop()
        else if (ui.bgTrk) MotionService.stopRun(ctx)
        else if (ContextCompat.checkSelfPermission(act, fine) == PackageManager.PERMISSION_GRANTED) { ui.start(); tab = 0 }
        else permLauncher.launch(perms)
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Lav1, Lav2, Lav3)))) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Brush.radialGradient(listOf(LimeC, Color.Transparent), Offset(size.width * .12f, size.height), size.width * .45f),
                    size.width * .45f, Offset(size.width * .12f, size.height))
                drawCircle(Brush.radialGradient(listOf(Color(0xFFD88BFF), Color.Transparent), Offset(size.width * .85f, size.height), size.width * .45f),
                    size.width * .45f, Offset(size.width * .85f, size.height))
            }
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                when (tab) {
                    0 -> HomeScreen(ui, tick, onPlan = { dlg = "plan" }, onBell = { dlg = "remind" }, onGear = { dlg = "settings" }, onFace = { face = true })
                    1 -> HistoryScreen(ui, tick) { tab = 0 }
                    3 -> CalendarScreen(ui, tick) { tab = 0 }
                    else -> StatsScreen(ui)
                }
                NavBar(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), tab, ui.tracking,
                    onTab = { tick(); tab = it }, onRecord = onRecord)
            }
            if (face) FaceScreen(ctx, tick, thud) { face = false }
            when (dlg) {
                "plan" -> PlanDialog(ui, thud) { dlg = "" }
                "remind" -> RemindDialog(ui, ctx, thud, onNeedNotif = {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { dlg = "" }
                "settings" -> SettingsDialog(ui, thud, onBg, onBattery) { dlg = "" }
            }
        }
    }
}
