package com.hinc.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

val Yellow = Color(0xFFD9F23A)
val Pink = Color(0xFFE0238C)
val Mauve = Color(0xFFC79EC0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HincApp() }
    }
}

enum class Screen { Dashboard, Reminder, Scan }

@Composable
fun HincApp() {
    var screen by remember { mutableStateOf(Screen.Dashboard) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(16.dp)) {
            when (screen) {
                Screen.Dashboard -> DashboardScreen({ screen = Screen.Reminder }, { screen = Screen.Scan })
                Screen.Reminder -> ReminderScreen({ screen = Screen.Dashboard }, { screen = Screen.Scan })
                Screen.Scan -> ScanScreen { screen = Screen.Dashboard }
            }
        }
    }
}

// ---------- dot-matrix digits ----------
private val GLYPHS = mapOf(
    '0' to "111101101101111", '1' to "010110010010111", '2' to "111001111100111",
    '3' to "111001111001111", '4' to "101101111001001", '5' to "111100111001111",
    '6' to "111100111101111", '7' to "111001001001001", '8' to "111101111101111",
    '9' to "111101111001111"
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
fun BackBtn(onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF1C1C1E)).clickable { onClick() }, Alignment.Center) {
        Text("←", color = Color.White, fontSize = 18.sp)
    }
}

@Composable
fun Pill(label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000)).clickable { onClick() }
            .padding(start = 24.dp, end = 6.dp).height(48.dp).widthIn(min = 200.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White, fontSize = 15.sp)
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White), Alignment.Center) { Text("+", color = Color.Black, fontSize = 20.sp) }
    }
}

// ---------- Dashboard ----------
@Composable
fun DashboardScreen(onReminder: () -> Unit, onScan: () -> Unit) {
    var day by remember { mutableStateOf(1) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackBtn(onReminder)
            Spacer(Modifier.weight(1f))
            listOf("M", "T", "W", "T", "F").forEachIndexed { i, d ->
                val sel = i == day
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (sel) Color.White else Color.Black)
                    .border(1.dp, Color.White.copy(.6f), CircleShape).clickable { day = i }, Alignment.Center) {
                    Text(d, color = if (sel) Color.Black else Color.White, fontSize = 14.sp)
                }
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sugar", color = Color.White, fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            DotText(listOf("88", "90", "86", "92", "89")[day], 7.dp)
            Spacer(Modifier.height(8.dp))
            Text("mg/dL", color = Color.White, fontSize = 16.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val mid = size.height / 2
            drawLine(Color.White.copy(.8f), Offset(0f, mid + 20), Offset(size.width, mid - 20), 2f)
            val p = Path()
            for (x in 0..size.width.toInt() step 4) {
                val xf = x.toFloat(); val y = mid + sin(xf / 40f) * 22f * (1f - xf / size.width * .6f)
                if (x == 0) p.moveTo(xf, y) else p.lineTo(xf, y)
            }
            drawPath(p, Yellow, style = Stroke(3f))
        }
        Card(Modifier.fillMaxWidth().height(130.dp),
            Brush.linearGradient(listOf(Color(0xFF6A5473), Color(0xFFE08080)))) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Label("Average Glucose")
                    Value("84", "mg/dl")
                }
                Box(Modifier.size(84.dp).border(1.dp, Color.White.copy(.5f), CircleShape), Alignment.Center) { DotText("84", 3.dp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f).height(150.dp), Brush.radialGradient(listOf(Color(0xFF05060A), Color(0xFF7FA58F)), radius = 380f)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Label("Time in range")
                    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                        drawLine(Brush.horizontalGradient(listOf(Color.Gray, Color(0xFFE05050))), Offset(0f, 12f), Offset(size.width, 12f), 8f, StrokeCap.Round)
                    }
                    Value("100", "%")
                }
            }
            Card(Modifier.weight(1f).height(150.dp), Brush.radialGradient(listOf(Color(0xFF8A0A4F), Color(0xFFE88BC4)), radius = 330f)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Label("Viriability")
                    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                        for (i in 0..8) drawCircle(Color.White, 2f + i * .7f, Offset(i * size.width / 8.5f + 6f, 10f))
                    }
                    Value("6.8", "%")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f).height(150.dp), Brush.radialGradient(listOf(Color(0xFF03050F), Color(0xFF2B4FC8)), radius = 380f)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Label("Vitamin D")
                    Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                        for (i in 0..20) drawLine(Color.White.copy(.4f), Offset(i * size.width / 20f, 10f), Offset(i * size.width / 20f, 28f), 2f)
                        drawLine(Yellow, Offset(size.width * .85f, 0f), Offset(size.width * .85f, 28f), 4f)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Text("83", color = Color.White, fontSize = 34.sp); Text("Good", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
            Card(Modifier.weight(1f).height(150.dp), Brush.linearGradient(listOf(Color(0xFFB5502A), Color(0xFFE0703A)))) {
                Box(Modifier.fillMaxSize().clickable { onScan() }) {
                    Box(Modifier.align(Alignment.CenterEnd).size(70.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF3050E0), Color.Transparent))))
                    Label("Spikes", Modifier.align(Alignment.TopStart))
                    Box(Modifier.align(Alignment.BottomStart)) { Value("0", "%") }
                }
            }
        }
    }
}

@Composable fun Label(t: String, m: Modifier = Modifier) = Text(t, color = Color.White.copy(.85f), fontSize = 14.sp, modifier = m)
@Composable fun Value(v: String, u: String) = Row(verticalAlignment = Alignment.Bottom) {
    Text(v, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light)
    Spacer(Modifier.width(4.dp)); Text(u, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
}
@Composable fun Card(m: Modifier, brush: Brush, content: @Composable BoxScope.() -> Unit) =
    Box(m.clip(RoundedCornerShape(36.dp)).background(brush).padding(20.dp), content = content)

// ---------- Reminder / Complete setup ----------
@Composable
fun ReminderScreen(onBack: () -> Unit, onStart: () -> Unit) {
    var on by remember { mutableStateOf(true) }
    var hours by remember { mutableStateOf(8f) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BackBtn(onBack); Text("Complete setup", color = Color.White, fontSize = 22.sp)
        }
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(48.dp)).background(Mauve)) {
            Box(Modifier.fillMaxWidth().height(430.dp).background(Brush.radialGradient(
                listOf(Color(0xFF4A0B3A), Color(0xFFD22A86), Color(0xFFE266AE), Color.Transparent), radius = 620f)))
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Reminder", color = Color.White, fontSize = 22.sp)
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x66604060)).clickable { on = !on }.padding(6.dp).width(84.dp),
                    horizontalArrangement = if (on) Arrangement.SpaceBetween else Arrangement.SpaceBetween) {
                    if (on) { Text("On", color = Color.White, modifier = Modifier.padding(start = 10.dp, top = 4.dp)); Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White)) }
                    else { Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White)); Text("Off", color = Color.White, modifier = Modifier.padding(end = 10.dp, top = 4.dp)) }
                }
            }
            Column(Modifier.align(Alignment.TopCenter).padding(top = 130.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DotText(hours.roundToInt().toString(), 9.dp)
                Spacer(Modifier.height(10.dp)); Text("hours", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.height(20.dp))
                Canvas(Modifier.fillMaxWidth().height(50.dp).pointerInput(Unit) {
                    detectHorizontalDragGestures { _, d -> hours = (hours - d / 40f).coerceIn(1f, 24f) }
                }) {
                    val gap = 18f; val c = size.width / 2
                    for (i in -12..12) {
                        val x = c + i * gap - ((hours % 1f) * gap)
                        drawLine(Color.White.copy(.5f - abs(i) * .035f), Offset(x, 14f), Offset(x, if (i % 3 == 0) 44f else 32f), 2f)
                    }
                    drawLine(Yellow, Offset(c, 6f), Offset(c, 48f), 5f, StrokeCap.Round)
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(4) { Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(if (it == 0) 1f else .5f))) }
                }
            }
            Row(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 110.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column { Text("tue", color = Color.White, fontSize = 16.sp); DotText("25", 3.dp, Color.White.copy(.7f)) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White)); Spacer(Modifier.width(10.dp)); Text("10", color = Color.White) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(28.dp).clip(CircleShape).background(Pink)); Spacer(Modifier.width(10.dp)); Text("00", color = Color.White) }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) { Pill("Start tracking", onStart) }
        }
    }
}

// ---------- veri scan ----------
@Composable
fun ScanScreen(onBack: () -> Unit) {
    var tracking by remember { mutableStateOf(false) }
    val sweep by rememberInfiniteAngle()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BackBtn(onBack); Text("veri", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
        }
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(48.dp)).background(Brush.radialGradient(
            listOf(Color(0xFF05061A), Color(0xFF05061A), Color(0xFF8DBBA5)), radius = 900f))) {
            Text(if (tracking) "Tracking sensor…" else "Move camera closer\nto a sensor", color = Color.White, fontSize = 20.sp,
                lineHeight = 26.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 36.dp))
            Canvas(Modifier.align(Alignment.Center).size(320.dp)) {
                val c = center; val R = size.minDimension / 2
                for (i in 0 until 120) {
                    val a = i * 3f * PI.toFloat() / 180f
                    val long = i % 5 == 0
                    drawLine(Color.White.copy(.75f), c + Offset(cos(a), sin(a)) * (R - if (long) 12f else 7f), c + Offset(cos(a), sin(a)) * R, 2f)
                }
                val a = (if (tracking) sweep else 135f) * PI.toFloat() / 180f
                for (k in 0..3) { val aa = a + k * .06f
                    drawLine(if (k == 1) Yellow else Color.White, c + Offset(cos(aa), sin(aa)) * (R - 46f), c + Offset(cos(aa), sin(aa)) * (R - 6f), if (k == 1) 5f else 2f) }
                drawCircle(Color.White.copy(.35f), R * .62f, c, style = Stroke(2f))
                drawCircle(Color(0xFFF4F4F6), R * .5f, c)
                drawCircle(Color.Gray, 6f, c)
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)) { Pill(if (tracking) "Stop tracking" else "Start tracking") { tracking = !tracking } }
        }
    }
}

@Composable
fun rememberInfiniteAngle(): State<Float> {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "sweep")
    return t.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(4000, easing = androidx.compose.animation.core.LinearEasing)), label = "a")
}
