package com.hinc.app

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

val InkL = Color(0xFF2A2540)

// ---------- glowing human figure that reacts to real motion ----------
fun DrawScope.drawRunner(p: Float, lv: Float) {
    val sc = size.height / 330f
    val pi = PI.toFloat()
    val bob = (abs(sin(p)) * 7f * lv + sin(p * 0.5f) * 2f * (1f - lv)) * sc
    val hip = Offset(size.width * 0.42f, size.height * 0.56f - bob)
    val lean = 0.06f + 0.22f * lv
    fun pt(o: Offset, ang: Float, len: Float) = Offset(o.x + sin(ang) * len * sc, o.y + cos(ang) * len * sc)
    fun seg(a: Offset, b: Offset, w: Float, al: Float) {
        drawLine(Color.White.copy(al * 0.10f), a, b, (w + 34f) * sc, StrokeCap.Round)
        drawLine(Color.White.copy(al * 0.22f), a, b, (w + 16f) * sc, StrokeCap.Round)
        drawLine(Color.White.copy(al * 0.85f), a, b, w * sc, StrokeCap.Round)
    }
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(.5f), Color.Transparent), hip, size.height * .5f), size.height * .5f, hip)
    val neck = pt(hip, pi - lean, 96f)
    val head = pt(neck, pi - lean * 0.7f, 34f)
    val shoulder = pt(neck, pi - lean, -12f)
    fun arm(side: Int, al: Float) {
        val ph = p + side * pi
        val up = (0.25f + 0.85f * lv) * sin(ph)
        val el = pt(shoulder, up, 52f)
        val hand = pt(el, up + 0.35f + 1.0f * lv, 48f)
        seg(shoulder, el, 18f, al); seg(el, hand, 15f, al)
    }
    fun leg(side: Int, al: Float) {
        val ph = p + side * pi
        val th = (0.12f + 0.75f * lv) * sin(ph)
        val knee = pt(hip, th, 68f)
        val sh = th - (0.15f + 1.15f * lv) * (0.5f + 0.5f * sin(ph + 1.2f))
        val foot = pt(knee, sh, 66f)
        seg(hip, knee, 28f, al); seg(knee, foot, 21f, al)
        seg(foot, Offset(foot.x + 20f * sc, foot.y), 12f, al)
    }
    arm(1, .55f); leg(1, .55f)
    seg(hip, neck, 50f, 1f)
    val hr = 21f * sc
    drawCircle(Color.White.copy(.10f), hr + 17f * sc, head)
    drawCircle(Color.White.copy(.22f), hr + 8f * sc, head)
    drawCircle(Color.White.copy(.9f), hr, head)
    drawArc(Color.White, 180f, 180f, true, Offset(head.x - 24f * sc, head.y - 25f * sc), Size(48f * sc, 40f * sc))
    seg(Offset(head.x + 8f * sc, head.y - 6f * sc), Offset(head.x + 34f * sc, head.y), 7f, 1f)
    leg(0, 1f); arm(0, 1f)
}

@Composable
fun Runner(level: Float, modifier: Modifier) {
    val lv by animateFloatAsState(level, tween(700), label = "lv")
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                if (last != 0L) {
                    val dt = (n - last) / 1_000_000_000f
                    phase = (phase + dt * 2f * PI.toFloat() * (0.35f + 1.7f * lv)) % (2f * PI.toFloat())
                }
                last = n
            }
        }
    }
    Canvas(modifier) { drawRunner(phase, lv) }
}

// ---------- home ----------
@Composable
fun GlassPill(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(.3f)).border(1.dp, GlassB, RoundedCornerShape(50))
        .clickable { onClick() }.padding(horizontal = 20.dp, vertical = 12.dp)) { T(label, 14.sp) }
}

@Composable
fun DayPill(l: String, done: Boolean, today: Boolean, h: Dp, onClick: () -> Unit) {
    Row(Modifier.width(96.dp).height(h).clip(RoundedCornerShape(50)).background(Color.White.copy(if (today) .42f else .26f))
        .border(1.dp, GlassB, RoundedCornerShape(50)).clickable { onClick() }.padding(horizontal = 14.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically) {
        T(l, 14.sp)
        if (done) Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF2B2B2B)), Alignment.Center) { Ico("check", Color.White, 14.dp) }
        else Canvas(Modifier.size(22.dp)) {
            drawCircle(Color.White.copy(.8f), size.width * .42f, center,
                style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))))
        }
    }
}

@Composable
fun RunCard(th: Theme, sh: Shown?, ui: Ui, m: Modifier) {
    val shape = RoundedCornerShape(56.dp)
    val ink = th.ink
    val pts = sh?.pts ?: emptyList()
    Box(m.clip(shape).background(Brush.verticalGradient(0f to th.top, 0.3f to th.mid, 0.72f to th.mid, 1f to th.bot))
        .border(1.dp, GlassB, shape)) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight(0.56f).padding(28.dp)) {
            if (pts.size >= 2) {
                val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
                val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
                val kx = cos(Math.toRadians((minLat + maxLat) / 2))
                val spanX = ((maxLon - minLon) * kx).coerceAtLeast(1e-6)
                val spanY = (maxLat - minLat).coerceAtLeast(1e-6)
                val scale = minOf(size.width.toDouble() / spanX, size.height.toDouble() / spanY)
                val ox = (size.width - spanX * scale) / 2.0
                val oy = (size.height - spanY * scale) / 2.0
                fun mp(q: Pt) = Offset((ox + (q.lon - minLon) * kx * scale).toFloat(), (oy + (maxLat - q.lat) * scale).toFloat())
                val path = Path()
                pts.forEachIndexed { i, q -> val o = mp(q); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                drawPath(path, ink.copy(.85f), style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val e = mp(pts.last())
                drawCircle(Color.White.copy(.35f), 24f, e)
                drawCircle(ink.copy(.5f), 24f, e, style = Stroke(2f))
                drawCircle(Color(0xFFF4F58A), 9f, e)
            }
        }
        val hint = when {
            ui.bgTrk && !ui.tracking -> "Tracking in the background…"
            ui.tracking && pts.size < 2 -> "Searching for GPS…"
            sh == null -> "Tap the dashed circle to start your first run"
            else -> ""
        }
        if (hint.isNotEmpty()) T(hint, 14.sp, ink.copy(.75f), modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp, start = 24.dp, end = 24.dp), align = TextAlign.Center)
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(26.dp), Arrangement.SpaceBetween, Alignment.Bottom) {
            Column {
                T(sh?.title ?: runTitle(System.currentTimeMillis()), 14.sp, ink)
                Row(verticalAlignment = Alignment.Bottom) {
                    BigText(fmtDist(sh?.dist ?: 0f, ui.unit), 62.sp, ink)
                    T(unitLabel(ui.unit), 11.sp, ink.copy(.6f), modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(sh?.let { fmtTime(it.secs) } ?: "0:00", 15.sp, ink)
                T(if ((sh?.bpm ?: -1) > 0) "${sh?.bpm} bpm" else "-- bpm", 15.sp, ink)
                T(if (sh != null) fmtPace(sh.dist, sh.secs, ui.unit) else "--:--", 15.sp, ink)
            }
        }
    }
}

@Composable
fun HomeScreen(ui: Ui, tick: () -> Unit, onPlan: () -> Unit, onBell: () -> Unit, onGear: () -> Unit, onFace: () -> Unit) {
    val th = THEMES[ui.theme % THEMES.size]
    val hDp = LocalConfiguration.current.screenHeightDp
    val sh = ui.shown()
    val wk = ui.weekRuns()
    val own = when { ui.level < 0.1f -> "Idle"; ui.level < 0.55f -> "Walking"; else -> "Running" }
    val label = if (ui.bgTrk && !ui.tracking) "Auto · Tracking"
        else if (ui.bgOn && !ui.tracking) "Auto · " + listOf("Idle", "Walking", "Running")[ui.bgAct.coerceIn(0, 2)] else own
    val planned = (0..6).filter { (ui.plan and (1 shl it)) != 0 }
    val letters = listOf("M", "T", "W", "T", "F", "S", "S")
    val todayIdx = dayIndex(System.currentTimeMillis())
    val doneDays = wk.map { dayIndex(it.t) }.toSet()
    val goalText = if (ui.goal > 0) "${ui.goal}" + (if (ui.unit == 0) "K" else "M") else "Set plan"
    Box(Modifier.fillMaxSize()) {
        Runner(ui.level, Modifier.align(Alignment.TopCenter).padding(top = 36.dp).fillMaxWidth().height((hDp * 0.40f).dp))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFF2F57C))
                .clickable { tick(); ui.theme = (ui.theme + 1) % THEMES.size; ui.savePrefs() }, Alignment.Center) { Ico("flower", Color.Black, 22.dp) }
            Spacer(Modifier.weight(1f))
            T(label, 12.sp, Color.White.copy(.9f))
            Spacer(Modifier.weight(1f))
            GlassCircle(46.dp, onClick = { tick(); onFace() }) { Ico("face", Color.White, 22.dp) }
            Spacer(Modifier.width(10.dp))
            GlassCircle(46.dp, onClick = { tick(); onBell() }) { Ico("bell", Color.White, 22.dp) }
            Spacer(Modifier.width(10.dp))
            GlassCircle(46.dp, onClick = { tick(); onGear() }) { Ico("gear", Color.White, 22.dp) }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(top = (hDp * 0.17f).dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            if (planned.isEmpty()) GlassPill("+ Plan") { tick(); onPlan() }
            else planned.forEach { d -> DayPill(letters[d], d in doneDays, d == todayIdx, if (planned.size > 4) 36.dp else 46.dp) { tick(); onPlan() } }
        }
        Column(Modifier.align(Alignment.TopStart).padding(top = (hDp * 0.36f).dp).clickable { tick(); onPlan() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Plan", 14.sp, Color.White.copy(.85f))
                Box(Modifier.padding(horizontal = 10.dp).width(80.dp).height(1.dp).background(Color.White.copy(.6f)))
                T(String.format(Locale.US, "%02d", wk.size), 14.sp, Color.White.copy(.75f))
            }
            BigText(goalText, 72.sp, Color.White, shadow = true)
        }
        RunCard(th, sh, ui, Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp).fillMaxWidth().height((hDp * 0.37f).dp))
    }
}

// ---------- activity / calendar / stats ----------
@Composable
fun Header(title: String) = Text(title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Thin,
    fontFamily = FontFamily.SansSerif, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
    style = TextStyle(shadow = Shadow(Color(0x40000000), Offset(0f, 4f), 16f)))

val ROWFMT = SimpleDateFormat("EEE, d MMM  h:mm a", Locale.getDefault())

@Composable
fun RunRow(ui: Ui, r: Run, onOpen: () -> Unit, onDelete: () -> Unit) {
    val sh = RoundedCornerShape(28.dp)
    Row(Modifier.fillMaxWidth().clip(sh).background(Color.White.copy(.32f)).border(1.dp, GlassB, sh).clickable { onOpen() }.padding(16.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(runTitle(r.t, r.kind), 16.sp, InkL, FontWeight.Normal)
            T(ROWFMT.format(Date(r.t)), 12.sp, InkL.copy(.7f))
        }
        Column(horizontalAlignment = Alignment.End) {
            T(fmtDist(r.dist, ui.unit) + " " + unitLabel(ui.unit), 18.sp, InkL, FontWeight.Normal)
            T(fmtTime(r.secs) + "  ·  " + fmtPace(r.dist, r.secs, ui.unit), 12.sp, InkL.copy(.7f))
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(.5f)).clickable { onDelete() }, Alignment.Center) { T("×", 18.sp, InkL) }
    }
}

@Composable
fun HistoryScreen(ui: Ui, tick: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header("Activity")
        if (ui.runs.isEmpty()) T("No runs yet. Tap the dashed circle below to record one.", 15.sp, InkL)
        ui.runs.forEachIndexed { i, r -> RunRow(ui, r, onOpen = { tick(); ui.selected = i; onOpen() }, onDelete = { tick(); ui.deleteRun(i) }) }
    }
}

@Composable
fun CalendarScreen(ui: Ui, tick: () -> Unit, onOpen: () -> Unit) {
    val now = Calendar.getInstance()
    var ym by remember { mutableStateOf(now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)) }
    var selKey by remember { mutableStateOf(dayKey(System.currentTimeMillis())) }
    val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.YEAR, ym / 12); set(Calendar.MONTH, ym % 12) }
    val first = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val byDay = ui.runs.groupBy { dayKey(it.t) }
    val rows = (first + days + 6) / 7
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header("Calendar")
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            GlassCircle(40.dp, onClick = { tick(); ym -= 1 }) { T("‹", 18.sp) }
            T(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time), 18.sp, InkL, FontWeight.Normal)
            GlassCircle(40.dp, onClick = { tick(); ym += 1 }) { T("›", 18.sp) }
        }
        Row(Modifier.fillMaxWidth()) { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Box(Modifier.weight(1f), Alignment.Center) { T(it, 12.sp, InkL.copy(.7f)) } } }
        for (r in 0 until rows) Row(Modifier.fillMaxWidth()) {
            for (c in 0..6) {
                val d = r * 7 + c - first + 1
                Box(Modifier.weight(1f).height(46.dp), Alignment.Center) {
                    if (d in 1..days) {
                        val key = cal.get(Calendar.YEAR) * 10000 + cal.get(Calendar.MONTH) * 100 + d
                        val has = byDay.containsKey(key)
                        Box(Modifier.size(40.dp).clip(CircleShape)
                            .background(if (has) LimeC else Color.Transparent)
                            .border(if (key == selKey) 2.dp else 0.dp, if (key == selKey) Color(0xFF2B2B2B) else Color.Transparent, CircleShape)
                            .clickable { tick(); selKey = key }, Alignment.Center) { T("$d", 14.sp, InkL, FontWeight.Normal) }
                    }
                }
            }
        }
        val list = ui.runs.mapIndexedNotNull { i, r -> if (dayKey(r.t) == selKey) i to r else null }
        if (list.isEmpty()) T("No runs on this day.", 14.sp, InkL)
        list.forEach { (i, r) -> RunRow(ui, r, onOpen = { tick(); ui.selected = i; onOpen() }, onDelete = { tick(); ui.deleteRun(i) }) }
    }
}

@Composable
fun StatTile(m: Modifier, label: String, value: String) {
    val sh = RoundedCornerShape(26.dp)
    Column(m.clip(sh).background(Color.White.copy(.32f)).border(1.dp, GlassB, sh).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T(label, 12.sp, InkL.copy(.7f)); BigText(value, 30.sp, InkL)
    }
}

@Composable
fun StatsScreen(ui: Ui) {
    val ws = weekStart(System.currentTimeMillis())
    val per = FloatArray(7)
    ui.runs.filter { it.t >= ws }.forEach { per[dayIndex(it.t)] += it.dist }
    val weekDist = toUnit(per.sum(), ui.unit)
    val totalM = ui.runs.sumOf { it.dist.toDouble() }.toFloat()
    val totalS = ui.runs.sumOf { it.secs }
    val longest = ui.runs.maxOfOrNull { it.dist } ?: 0f
    val u = unitLabel(ui.unit)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("Stats")
        val sh = RoundedCornerShape(32.dp)
        Column(Modifier.fillMaxWidth().clip(sh).background(Color.White.copy(.32f)).border(1.dp, GlassB, sh).padding(18.dp)) {
            T("This week", 13.sp, InkL.copy(.7f))
            Row(verticalAlignment = Alignment.Bottom) {
                BigText(String.format(Locale.US, "%.1f", weekDist), 44.sp, InkL)
                T(" $u" + if (ui.goal > 0) "  /  ${ui.goal}" else "", 13.sp, InkL.copy(.7f), modifier = Modifier.padding(bottom = 8.dp))
            }
            val mx = maxOf(per.max(), 1f)
            Canvas(Modifier.fillMaxWidth().height(120.dp).padding(top = 10.dp)) {
                val bw = size.width / 7f
                for (i in 0..6) {
                    val h = size.height * per[i] / mx
                    drawRoundRect(Color(0xFF2B2B2B).copy(if (per[i] > 0f) .85f else .12f), Offset(i * bw + bw * .25f, size.height - maxOf(h, 6f)),
                        Size(bw * .5f, maxOf(h, 6f)), androidx.compose.ui.geometry.CornerRadius(bw * .25f))
                }
            }
            Row(Modifier.fillMaxWidth()) { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Box(Modifier.weight(1f), Alignment.Center) { T(it, 12.sp, InkL.copy(.7f)) } } }
            if (ui.goal > 0) {
                val frac = (weekDist / ui.goal).coerceIn(0f, 1f)
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(.5f))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color(0xFF2B2B2B)))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Modifier.weight(1f), "Total distance", String.format(Locale.US, "%.1f", toUnit(totalM, ui.unit)))
            StatTile(Modifier.weight(1f), "Runs", ui.runs.size.toString())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Modifier.weight(1f), "Avg pace / $u", if (totalM >= 50f) fmtTime((totalS / toUnit(totalM, ui.unit)).toInt()) else "--:--")
            StatTile(Modifier.weight(1f), "Longest", String.format(Locale.US, "%.1f", toUnit(longest, ui.unit)))
        }
    }
}

// ---------- dialogs ----------
@Composable
fun DialogCard(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.clip(RoundedCornerShape(32.dp)).background(Color.White).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(title, 22.sp, Color.Black, FontWeight.Normal)
            content()
        }
    }
}

@Composable
fun Btn(label: String, filled: Boolean, m: Modifier = Modifier, onClick: () -> Unit) {
    Box(m.clip(RoundedCornerShape(50)).background(if (filled) Color(0xFF2B2B2B) else Color(0xFFEDEDED)).clickable { onClick() }.padding(vertical = 14.dp, horizontal = 18.dp), Alignment.Center) {
        T(label, 15.sp, if (filled) Color.White else Color.Black, FontWeight.Normal)
    }
}

@Composable
fun Chip(label: String, on: Boolean, m: Modifier = Modifier, onClick: () -> Unit) {
    Box(m.clip(RoundedCornerShape(50)).background(if (on) Color(0xFF2B2B2B) else Color(0xFFEDEDED)).clickable { onClick() }.padding(vertical = 12.dp, horizontal = 16.dp), Alignment.Center) {
        T(label, 14.sp, if (on) Color.White else Color.Black, FontWeight.Normal)
    }
}

@Composable
fun PlanDialog(ui: Ui, thud: () -> Unit, onClose: () -> Unit) {
    var goal by remember { mutableStateOf(if (ui.goal > 0) ui.goal.toString() else "") }
    var mask by remember { mutableStateOf(ui.plan) }
    DialogCard("Weekly plan", onClose) {
        T("Weekly goal (" + unitLabel(ui.unit).lowercase() + ")", 12.sp, Color(0xFF777777))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFEDEDED)).padding(14.dp)) {
            BasicTextField(goal, { if (it.length <= 3 && it.all { c -> c.isDigit() }) goal = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(color = Color.Black, fontSize = 18.sp, fontFamily = FontFamily.SansSerif),
                cursorBrush = SolidColor(Color.Black), modifier = Modifier.fillMaxWidth())
        }
        T("Run days", 12.sp, Color(0xFF777777))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l ->
                Chip(l, (mask and (1 shl i)) != 0, Modifier.weight(1f)) { mask = mask xor (1 shl i) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Cancel", false, Modifier.weight(1f)) { onClose() }
            Btn("Save", true, Modifier.weight(1f)) { ui.goal = goal.toIntOrNull() ?: 0; ui.plan = mask; ui.savePrefs(); thud(); onClose() }
        }
    }
}

@Composable
fun RemindDialog(ui: Ui, ctx: Context, thud: () -> Unit, onNeedNotif: () -> Unit, onClose: () -> Unit) {
    val act = LocalContext.current
    var on by remember { mutableStateOf(ui.remOn) }
    var h by remember { mutableStateOf(ui.remH) }
    var m by remember { mutableStateOf(ui.remM) }
    DialogCard("Daily reminder", onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("On", on, Modifier.weight(1f)) { on = true }
            Chip("Off", !on, Modifier.weight(1f)) { on = false }
        }
        Btn(String.format(Locale.US, "Time  %02d:%02d", h, m), false, Modifier.fillMaxWidth()) {
            TimePickerDialog(act, { _, hh, mm -> h = hh; m = mm }, h, m, true).show()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Cancel", false, Modifier.weight(1f)) { onClose() }
            Btn("Save", true, Modifier.weight(1f)) {
                ui.remOn = on; ui.remH = h; ui.remM = m; ui.savePrefs()
                scheduleReminder(ctx, on, h, m)
                if (on) onNeedNotif()
                thud(); onClose()
            }
        }
    }
}

@Composable
fun SettingsDialog(ui: Ui, thud: () -> Unit, onBg: (Boolean) -> Unit, onBattery: () -> Unit, onClose: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    DialogCard("Settings", onClose) {
        T("Units", 12.sp, Color(0xFF777777))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Kilometres", ui.unit == 0, Modifier.weight(1f)) { ui.unit = 0; ui.savePrefs() }
            Chip("Miles", ui.unit == 1, Modifier.weight(1f)) { ui.unit = 1; ui.savePrefs() }
        }
        T("Background detection", 12.sp, Color(0xFF777777))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("On", ui.bgOn, Modifier.weight(1f)) { onBg(true) }
            Chip("Off", !ui.bgOn, Modifier.weight(1f)) { onBg(false) }
        }
        T("Keeps running in the background, notices walking or running and records the route automatically. A notification stays visible while it runs.", 12.sp, Color(0xFF777777))
        Btn("Battery settings", false, Modifier.fillMaxWidth()) { onBattery() }
        T("Colour: tap the yellow button on the home screen to switch card colours.", 12.sp, Color(0xFF777777))
        Btn(if (confirm) "Tap again to delete all runs" else "Clear history", false, Modifier.fillMaxWidth()) {
            if (confirm) { ui.clearRuns(); thud(); confirm = false; onClose() } else confirm = true
        }
        Btn("Close", true, Modifier.fillMaxWidth()) { onClose() }
    }
}
