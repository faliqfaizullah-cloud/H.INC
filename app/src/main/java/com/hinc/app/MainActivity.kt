package com.hinc.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.*

val Yellow = Color(0xFFD9F23A)
val Pink = Color(0xFFE0238C)
val Mauve = Color(0xFFC79EC0)
val GlassBorder = Brush.linearGradient(listOf(Color.White.copy(.55f), Color.White.copy(.06f)))

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

enum class Screen { Dashboard, Reminder, Scan }

@Composable
fun HincApp(ctx: Context) {
    val store = remember { Store(ctx) }
    var screen by remember { mutableStateOf(Screen.Dashboard) }
    var readings by remember { mutableStateOf(store.readings()) }
    var vitD by remember { mutableStateOf(store.vitD) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val tick: () -> Unit = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val thud: () -> Unit = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    BackHandler(screen != Screen.Dashboard) { tick(); screen = Screen.Dashboard }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Aurora()
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                when (screen) {
                    Screen.Dashboard -> DashboardScreen(readings, vitD, tick,
                        onSetup = { screen = Screen.Reminder }, onScan = { screen = Screen.Scan },
                        onAdd = { dialog = "glucose" }, onVitD = { dialog = "vitd" })
                    Screen.Reminder -> ReminderScreen(ctx, store, tick, thud,
                        onBack = { screen = Screen.Dashboard }, onStart = { screen = Screen.Scan })
                    Screen.Scan -> ScanScreen(tick, thud, onBack = { screen = Screen.Dashboard }, onLog = { dialog = "glucose" })
                }
            }
            dialog?.let { kind ->
                AddDialog(kind, onDismiss = { dialog = null }, onSave = { v ->
                    if (kind == "glucose") { readings = store.add(v); HincWidget.refresh(ctx) } else { store.vitD = v; vitD = v }
                    thud(); dialog = null; screen = Screen.Dashboard
                })
            }
        }
    }
}

@Composable
fun Aurora() {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset((-90).dp, 30.dp).size(280.dp).blur(90.dp, BlurredEdgeTreatment.Unbounded).background(Pink.copy(.40f), CircleShape))
        Box(Modifier.align(Alignment.CenterEnd).offset(90.dp, 0.dp).size(260.dp).blur(90.dp, BlurredEdgeTreatment.Unbounded).background(Color(0xFF3050E0).copy(.35f), CircleShape))
        Box(Modifier.align(Alignment.BottomStart).offset((-70).dp, 50.dp).size(280.dp).blur(90.dp, BlurredEdgeTreatment.Unbounded).background(Color(0xFF7FA58F).copy(.40f), CircleShape))
    }
}

@Composable
fun T(s: String, size: TextUnit = 16.sp, color: Color = Color.White, weight: FontWeight = FontWeight.Light,
      modifier: Modifier = Modifier, align: TextAlign? = null) =
    Text(s, modifier = modifier, color = color, fontSize = size, fontWeight = weight,
        fontFamily = FontFamily.SansSerif, letterSpacing = (-0.3).sp, textAlign = align)

private val GLYPHS = mapOf(
    '0' to "111101101101111", '1' to "010110010010111", '2' to "111001111100111",
    '3' to "111001111001111", '4' to "101101111001001", '5' to "111100111001111",
    '6' to "111100111101111", '7' to "111001001001001", '8' to "111101111101111",
    '9' to "111101111001111", '-' to "000000111000000"
)

@Composable
fun DotText(text: String, dot: Dp = 6.dp, color: Color = Color.White) {
    val step = dot * 2.4f
    val w = step * (text.length * 4 - 1)
    Canvas(Modifier.size(w, step * 5)) {
        val s = step.toPx(); val r = dot.toPx() / 2
        text.forEachIndexed { i, ch ->
            val g = GLYPHS[ch] ?: return@forEachIndexed
            g.forEachIndexed { k, c ->
                if (c == '1') drawCircle(color, r, Offset((i * 4 + k % 3) * s + r, (k / 3) * s + r))
            }
        }
    }
}

@Composable
fun GCard(m: Modifier, tint: Brush, click: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(34.dp)
    Box(m.clip(shape).background(tint).border(1.dp, GlassBorder, shape)
        .then(if (click != null) Modifier.clickable { click() } else Modifier).padding(20.dp), content = content)
}

@Composable
fun GCircle(label: String, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(.12f)).border(1.dp, GlassBorder, CircleShape)
        .clickable { onClick() }, Alignment.Center) { T(label, 20.sp) }
}

@Composable
fun GPill(label: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(.35f)).border(1.dp, GlassBorder, RoundedCornerShape(50))
        .clickable { onClick() }.padding(start = 24.dp, end = 6.dp).height(50.dp).widthIn(min = 220.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        T(label, 15.sp)
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White), Alignment.Center) { T("+", 22.sp, Color.Black) }
    }
}

@Composable fun MLabel(t: String, m: Modifier = Modifier) = T(t, 14.sp, Color.White.copy(.85f), modifier = m)
@Composable fun Metric(v: String, u: String) = Row(verticalAlignment = Alignment.Bottom) {
    T(v, 34.sp); Spacer(Modifier.width(4.dp)); T(u, 14.sp, modifier = Modifier.padding(bottom = 6.dp))
}

// ---------------- Dashboard ----------------
@Composable
fun DashboardScreen(readings: List<Reading>, vitD: Int, tick: () -> Unit, onSetup: () -> Unit,
                    onScan: () -> Unit, onAdd: () -> Unit, onVitD: () -> Unit) {
    val days = remember { (6 downTo 0).map { d -> Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -d) } } }
    var sel by remember { mutableStateOf(6) }
    val dayReadings = readings.filter { sameDay(it.t, days[sel].timeInMillis) }.sortedBy { it.t }
    val s = stats(dayReadings)
    val letter = SimpleDateFormat("EEEEE", Locale.getDefault())

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GCircle("←", 40.dp) { tick(); onSetup() }
                Spacer(Modifier.weight(1f))
                days.forEachIndexed { i, c ->
                    val on = i == sel
                    Box(Modifier.size(34.dp).clip(CircleShape).background(if (on) Color.White else Color.Transparent)
                        .border(1.dp, Color.White.copy(.6f), CircleShape).clickable { tick(); sel = i }, Alignment.Center) {
                        T(letter.format(c.time), 13.sp, if (on) Color.Black else Color.White)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                T("Sugar", 20.sp)
                Spacer(Modifier.height(12.dp))
                DotText(s.last?.toString() ?: "--", 7.dp)
                Spacer(Modifier.height(8.dp))
                T("mg/dL", 16.sp)
                if (s.last == null) T("No readings — tap + to add yours", 13.sp, Color.White.copy(.6f), modifier = Modifier.padding(top = 6.dp))
            }
            Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                drawLine(Color.White.copy(.85f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
                if (dayReadings.size >= 2) {
                    val vs = dayReadings.map { it.v }
                    val lo = vs.min().toFloat(); val hi = max(vs.max().toFloat(), lo + 10f)
                    fun y(v: Int) = size.height - 10f - (v - lo) / (hi - lo) * (size.height - 20f)
                    val n = vs.size; val w = size.width - 20f
                    val p = Path()
                    vs.forEachIndexed { i, v ->
                        val x = i * w / (n - 1) + 10f
                        if (i == 0) p.moveTo(x, y(v)) else {
                            val px = (i - 1) * w / (n - 1) + 10f
                            p.cubicTo((px + x) / 2, y(vs[i - 1]), (px + x) / 2, y(v), x, y(v))
                        }
                    }
                    drawPath(p, Yellow, style = Stroke(3f, cap = StrokeCap.Round))
                    drawCircle(Yellow, 7f, Offset(w + 10f, y(vs.last())))
                }
            }
            GCard(Modifier.fillMaxWidth().height(130.dp), Brush.linearGradient(listOf(Color(0xCC6A5473), Color(0xCCE08080)))) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                        MLabel("Average Glucose"); Metric(s.avg?.toString() ?: "--", "mg/dl")
                    }
                    Box(Modifier.size(84.dp).border(1.dp, Color.White.copy(.5f), CircleShape), Alignment.Center) {
                        DotText(s.avg?.toString() ?: "--", 3.dp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GCard(Modifier.weight(1f).height(150.dp), Brush.radialGradient(listOf(Color(0xE605060A), Color(0xCC7FA58F)), radius = 380f)) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        MLabel("Time in range")
                        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                            drawLine(Brush.horizontalGradient(listOf(Color.Gray, Color(0xFFE05050))), Offset(0f, 16f), Offset(size.width, 16f), 8f, StrokeCap.Round)
                            s.tir?.let { val x = size.width * it / 100f
                                val tri = Path().apply { moveTo(x, 4f); lineTo(x - 7f, -6f + 0f); lineTo(x + 7f, -6f) }
                                drawCircle(Yellow, 7f, Offset(x, 16f)) }
                        }
                        Metric(s.tir?.toString() ?: "--", "%")
                    }
                }
                GCard(Modifier.weight(1f).height(150.dp), Brush.radialGradient(listOf(Color(0xE68A0A4F), Color(0xCCE88BC4)), radius = 330f)) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        MLabel("Viriability")
                        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                            for (i in 0..8) drawCircle(Color.White, 2f + i * .7f, Offset(i * size.width / 8.5f + 6f, 10f))
                        }
                        Metric(s.cv?.let { String.format(Locale.US, "%.1f", it) } ?: "--", "%")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GCard(Modifier.weight(1f).height(150.dp), Brush.radialGradient(listOf(Color(0xE603050F), Color(0xCC2B4FC8)), radius = 380f), click = { tick(); onVitD() }) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        MLabel("Vitamin D")
                        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                            for (i in 0..20) drawLine(Color.White.copy(.4f), Offset(i * size.width / 20f, 10f), Offset(i * size.width / 20f, 28f), 2f)
                            if (vitD >= 0) { val x = size.width * (vitD.coerceAtMost(100) / 100f)
                                drawLine(Yellow, Offset(x, 0f), Offset(x, 28f), 4f) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            T(if (vitD >= 0) vitD.toString() else "--", 34.sp)
                            T(if (vitD < 0) "Tap to add" else if (vitD >= 30) "Good" else if (vitD >= 20) "Fair" else "Low", 14.sp)
                        }
                    }
                }
                GCard(Modifier.weight(1f).height(150.dp), Brush.linearGradient(listOf(Color(0xCCB5502A), Color(0xCCE0703A))), click = { tick(); onScan() }) {
                    Box(Modifier.align(Alignment.CenterEnd).size(70.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF3050E0), Color.Transparent))))
                    MLabel("Spikes", Modifier.align(Alignment.TopStart))
                    Box(Modifier.align(Alignment.BottomStart)) { Metric(s.spikes?.toString() ?: "--", "%") }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(62.dp).clip(CircleShape)
            .background(Color.White.copy(.16f)).border(1.dp, GlassBorder, CircleShape)
            .clickable { tick(); onAdd() }, Alignment.Center) { T("+", 30.sp) }
    }
}

// ---------------- Reminder / Complete setup ----------------
@Composable
fun Knob(value: Int, max: Int, step: Int, fill: Color, tick: () -> Unit, onChange: (Int) -> Unit) {
    val cur by rememberUpdatedState(value)
    val upd by rememberUpdatedState(onChange)
    var acc by remember { mutableStateOf(0f) }
    Row(Modifier.pointerInput(max) {
        detectVerticalDragGestures { _, d ->
            acc -= d
            if (abs(acc) > 24f) { val dir = if (acc > 0) 1 else -1; acc = 0f; upd(((cur + dir * step) % max + max) % max); tick() }
        }
    }.clickable { upd((cur + step) % max); tick() }, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(30.dp)) {
            drawCircle(fill)
            val a = (value.toFloat() / max) * 2f * PI.toFloat() - PI.toFloat() / 2f
            drawLine(Color.Black.copy(.5f), center, center + Offset(cos(a), sin(a)) * (size.minDimension / 2 - 4f), 3f, StrokeCap.Round)
        }
        Spacer(Modifier.width(10.dp)); T(value.toString().padStart(2, '0'), 16.sp)
    }
}

@Composable
fun ReminderScreen(ctx: Context, store: Store, tick: () -> Unit, thud: () -> Unit, onBack: () -> Unit, onStart: () -> Unit) {
    var on by remember { mutableStateOf(store.remOn) }
    var hours by remember { mutableStateOf(store.remHours.toFloat()) }
    var hh by remember { mutableStateOf(store.remH) }
    var mm by remember { mutableStateOf(store.remM) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val now = remember { Calendar.getInstance() }
    fun commit() {
        store.remOn = on; store.remHours = hours.roundToInt(); store.remH = hh; store.remM = mm
        scheduleReminder(ctx, on, hours.roundToInt(), hh, mm)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GCircle("←", 44.dp) { tick(); commit(); onBack() }; T("Complete setup", 22.sp)
        }
        val shape = RoundedCornerShape(48.dp)
        Box(Modifier.fillMaxSize().clip(shape).background(Mauve.copy(.78f)).border(1.dp, GlassBorder, shape)) {
            Box(Modifier.fillMaxWidth().height(430.dp).background(Brush.radialGradient(
                listOf(Color(0xFF4A0B3A), Color(0xFFD22A86), Color(0xFFE266AE), Color.Transparent), radius = 620f)))
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T("Reminder", 22.sp)
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(.3f)).clickable {
                    on = !on; thud()
                    if (on && Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }.padding(6.dp).width(84.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (on) { T("On", 14.sp, modifier = Modifier.padding(start = 10.dp)); Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White)) }
                    else { Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White)); T("Off", 14.sp, modifier = Modifier.padding(end = 10.dp)) }
                }
            }
            Column(Modifier.align(Alignment.TopCenter).padding(top = 130.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DotText(hours.roundToInt().toString(), 9.dp)
                Spacer(Modifier.height(10.dp)); T("hours", 18.sp)
                Spacer(Modifier.height(20.dp))
                Canvas(Modifier.fillMaxWidth().height(50.dp).pointerInput(Unit) {
                    detectHorizontalDragGestures { _, d ->
                        val old = hours.roundToInt()
                        hours = (hours - d / 40f).coerceIn(1f, 24f)
                        if (hours.roundToInt() != old) tick()
                    }
                }) {
                    val gap = 18f; val c = size.width / 2
                    for (i in -12..12) {
                        val x = c + i * gap - ((hours % 1f) * gap)
                        drawLine(Color.White.copy((.5f - abs(i) * .035f).coerceAtLeast(.05f)), Offset(x, 14f), Offset(x, if (i % 3 == 0) 44f else 32f), 2f)
                    }
                    drawLine(Yellow, Offset(c, 6f), Offset(c, 48f), 5f, StrokeCap.Round)
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(4) { Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(if (it == 0) 1f else .5f))) }
                }
            }
            Row(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 110.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column {
                    T(SimpleDateFormat("EEE", Locale.getDefault()).format(now.time).lowercase(), 16.sp)
                    Spacer(Modifier.height(6.dp))
                    DotText(now.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0'), 3.dp, Color.White.copy(.8f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Knob(hh, 24, 1, Color.White, tick) { hh = it }
                    Knob(mm, 60, 5, Pink, tick) { mm = it }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
                GPill("Start tracking") { thud(); commit(); onStart() }
            }
        }
    }
}

// ---------------- Scan ----------------
@Composable
fun ScanScreen(tick: () -> Unit, thud: () -> Unit, onBack: () -> Unit, onLog: () -> Unit) {
    var tracking by remember { mutableStateOf(false) }
    val t = rememberInfiniteTransition(label = "s")
    val sweep by t.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "a")
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(190.dp)) {
            Box(Modifier.align(Alignment.Center).offset(40.dp, 10.dp).size(200.dp).blur(60.dp, BlurredEdgeTreatment.Unbounded).background(Yellow.copy(.5f), CircleShape))
            Box(Modifier.align(Alignment.Center).offset((-50).dp, 20.dp).size(180.dp).blur(60.dp, BlurredEdgeTreatment.Unbounded).background(Pink.copy(.5f), CircleShape))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { GCircle("←", 44.dp) { tick(); onBack() } }
            T("H.INC", 30.sp, modifier = Modifier.align(Alignment.Center))
        }
        val shape = RoundedCornerShape(48.dp)
        Box(Modifier.fillMaxSize().clip(shape).background(Brush.radialGradient(
            listOf(Color(0xF005061A), Color(0xF005061A), Color(0xCC8DBBA5)), radius = 900f)).border(1.dp, GlassBorder, shape)) {
            Text(if (tracking) "Tracking sensor…" else "Move camera closer\nto a sensor", color = Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.Light, fontFamily = FontFamily.SansSerif, lineHeight = 26.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
            Canvas(Modifier.align(Alignment.Center).size(300.dp)) {
                val c = center; val R = size.minDimension / 2
                for (i in 0 until 120) {
                    val a = i * 3f * PI.toFloat() / 180f
                    val len = if (i % 5 == 0) 12f else 7f
                    drawLine(Color.White.copy(.75f), c + Offset(cos(a), sin(a)) * (R - len), c + Offset(cos(a), sin(a)) * R, 2f)
                }
                val a = (if (tracking) sweep else 135f) * PI.toFloat() / 180f
                for (k in 0..3) {
                    val aa = a + k * .06f
                    drawLine(if (k == 1) Yellow else Color.White, c + Offset(cos(aa), sin(aa)) * (R - 46f),
                        c + Offset(cos(aa), sin(aa)) * (R - 6f), if (k == 1) 5f else 2f)
                }
                drawCircle(Color.White.copy(.35f), R * .62f, c, style = Stroke(2f))
                drawCircle(Color(0xFFF4F4F6), R * .5f, c)
                drawCircle(Color.Gray, 6f, c)
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)) {
                GPill(if (tracking) "Log reading" else "Start tracking") {
                    thud()
                    if (tracking) { tracking = false; onLog() } else tracking = true
                }
            }
        }
    }
}

// ---------------- Add dialog ----------------
@Composable
fun AddDialog(kind: String, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val glucose = kind == "glucose"
    val v = text.toIntOrNull()
    val ok = v != null && (if (glucose) v in 20..600 else v in 1..200)
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    val shape = RoundedCornerShape(36.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(shape).background(Color(0xE61A1A20)).border(1.dp, GlassBorder, shape).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            T(if (glucose) "Add reading" else "Vitamin D", 20.sp)
            Spacer(Modifier.height(16.dp))
            BasicTextField(value = text, onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) text = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Yellow), modifier = Modifier.width(160.dp).focusRequester(fr),
                decorationBox = { inner -> Box(contentAlignment = Alignment.Center) {
                    if (text.isEmpty()) T("0", 56.sp, Color.White.copy(.25f)); inner() } })
            T(if (glucose) "mg/dL" else "ng/mL", 15.sp, Color.White.copy(.7f))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, GlassBorder, RoundedCornerShape(50))
                    .clickable { onDismiss() }.padding(horizontal = 26.dp, vertical = 12.dp)) { T("Cancel", 15.sp) }
                Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(if (ok) 1f else .25f))
                    .clickable(enabled = ok) { onSave(v!!) }.padding(horizontal = 30.dp, vertical = 12.dp)) { T("Save", 15.sp, Color.Black) }
            }
        }
    }
}
