package com.hinc.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

val FBg = Color(0xFF070F0D)
val FTeal = Color(0xFF29D6A8)

data class Metrics(val s: Scan, val rec: Int?, val gap: Int?, val tension: Int?)

// ---------- signal processing: camera-based pulse (rPPG) ----------
fun movAvg(a: DoubleArray, w: Int): DoubleArray {
    val n = a.size
    val cs = DoubleArray(n + 1)
    for (i in 0 until n) cs[i + 1] = cs[i] + a[i]
    val half = w / 2
    return DoubleArray(n) { i ->
        val lo = max(0, i - half); val hi = min(n - 1, i + half)
        (cs[hi + 1] - cs[lo]) / (hi - lo + 1)
    }
}

/** Returns (heart rate bpm, RMSSD ms) or null if the signal is too weak. */
fun ppg(ts: List<Double>, gs: List<Double>): Pair<Int, Int>? {
    val n = gs.size
    if (n < 200) return null
    val g = gs.toDoubleArray()
    val fast = movAvg(g, 3); val slow = movAvg(g, 25)
    val s = DoubleArray(n) { fast[it] - slow[it] }
    val sd = sqrt(s.sumOf { it * it } / n)
    if (sd < 1e-6) return null
    val idx = ArrayList<Int>()
    for (i in 1 until n - 1) {
        if (s[i] > s[i - 1] && s[i] >= s[i + 1] && s[i] > 0.35 * sd) {
            if (idx.isEmpty() || ts[i] - ts[idx.last()] > 0.33) idx.add(i)
            else if (s[i] > s[idx.last()]) idx[idx.size - 1] = i
        }
    }
    val ibi = ArrayList<Double>()
    for (k in 1 until idx.size) { val d = ts[idx[k]] - ts[idx[k - 1]]; if (d in 0.33..1.5) ibi.add(d) }
    if (ibi.size < 15) return null
    val med = ibi.sorted()[ibi.size / 2]
    val ok = ibi.filter { it in 0.75 * med..1.25 * med }
    if (ok.size < 12) return null
    val hr = 60.0 / ok.average()
    var acc = 0.0
    for (k in 1 until ok.size) { val d = ok[k] - ok[k - 1]; acc += d * d }
    val rmssd = min(sqrt(acc / (ok.size - 1)) * 1000.0, 250.0)
    return Pair(hr.roundToInt(), rmssd.roundToInt())
}

// ---------- camera + face + scan state ----------
class FaceCam(val c: Context) {
    private val s = Store(c)
    var frame by mutableStateOf<ImageBitmap?>(null)
    var box by mutableStateOf<FloatArray?>(null)
    var scanning by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var msg by mutableStateOf("")
    var scans by mutableStateOf(s.scans())
    var temp by mutableStateOf(s.tempC)
    var scanLen by mutableStateOf(s.scanLen)

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
    private var detecting = false
    private var n = 0
    private val ts = ArrayList<Double>()
    private val gs = ArrayList<Double>()
    private var validSec = 0.0
    private var lastT = -1.0
    private var startT = -1.0

    private val lut = IntArray(256) { i ->
        val v = ((i - 25) * 1.45f).coerceIn(0f, 255f) / 255f
        val stops = listOf(0f to 0xFF06130F.toInt(), 0.35f to 0xFF14574A.toInt(), 0.7f to 0xFF6FCFAA.toInt(), 1f to 0xFFF2F9CC.toInt())
        var res = stops.last().second
        for (k in 0 until stops.size - 1) {
            val a = stops[k].first; val b = stops[k + 1].first
            if (v <= b) { res = ColorUtils.blendARGB(stops[k].second, stops[k + 1].second, (v - a) / (b - a)); break }
        }
        res
    }

    fun startScan() {
        ts.clear(); gs.clear(); validSec = 0.0; lastT = -1.0; startT = -1.0
        progress = 0f; msg = ""; scanning = true
    }
    fun cancel() { scanning = false }

    private fun finish() {
        scanning = false
        val r = ppg(ts, gs)
        if (r == null) { msg = "Signal too weak. Use even light and stay still."; progress = 0f; return }
        val sc = Scan(System.currentTimeMillis(), r.first, r.second)
        scans = listOf(sc) + scans
        s.saveScans(scans)
    }

    fun setTemp(v: Float) { temp = v; s.tempC = v }
    fun setLen(v: Int) { scanLen = v; s.scanLen = v }
    fun clearScans() { scans = emptyList(); s.saveScans(scans); progress = 0f }

    fun metrics(): Metrics? {
        val l = scans.firstOrNull() ?: return null
        val prev = scans.drop(1).take(7)
        if (prev.isEmpty()) return Metrics(l, null, null, null)
        val bR = prev.map { it.rmssd }.average(); val bH = prev.map { it.hr }.average()
        return Metrics(l, (l.rmssd / bR * 100).coerceAtMost(100.0).roundToInt(),
            (l.rmssd - bR).roundToInt(), ((l.hr - bH) / bH * 100).coerceAtLeast(0.0).roundToInt())
    }

    fun analyze(img: ImageProxy) {
        val plane = img.planes[0]
        val buf = plane.buffer; val rs = plane.rowStride; val ps = plane.pixelStride
        val step = 3
        val sw = img.width / step; val sh = img.height / step
        val rot = img.imageInfo.rotationDegrees
        val vert = rot == 90 || rot == 270
        val ow = if (vert) sh else sw; val oh = if (vert) sw else sh
        val disp = IntArray(ow * oh); val rgb = IntArray(ow * oh)
        for (y in 0 until sh) {
            val row = y * step * rs
            for (x in 0 until sw) {
                val i = row + x * step * ps
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                val lum = (r * 77 + g * 150 + b * 29) shr 8
                var ox: Int; val oy: Int
                when (rot) {
                    90 -> { ox = sh - 1 - y; oy = x }
                    180 -> { ox = sw - 1 - x; oy = sh - 1 - y }
                    270 -> { ox = y; oy = sw - 1 - x }
                    else -> { ox = x; oy = y }
                }
                ox = ow - 1 - ox
                val k = oy * ow + ox
                disp[k] = lut[lum]; rgb[k] = (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(disp, ow, oh, Bitmap.Config.ARGB_8888)
        n++
        if (n % 2 == 0) frame = bmp.asImageBitmap()
        if (n % 6 == 0 && !detecting) {
            detecting = true
            detector.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { faces ->
                val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                box = f?.boundingBox?.let {
                    floatArrayOf((it.left / ow.toFloat()).coerceIn(0f, 1f), (it.top / oh.toFloat()).coerceIn(0f, 1f),
                        (it.right / ow.toFloat()).coerceIn(0f, 1f), (it.bottom / oh.toFloat()).coerceIn(0f, 1f))
                }
                detecting = false
            }.addOnFailureListener { detecting = false }
        }
        if (!scanning) return
        val t = img.imageInfo.timestamp / 1e9
        if (startT < 0) startT = t
        val b = box
        if (b == null) { lastT = -1.0 }
        else {
            val fw = b[2] - b[0]; val fh = b[3] - b[1]
            val x0 = ((b[0] + fw * 0.28f) * ow).toInt().coerceIn(0, ow - 1)
            val x1 = ((b[2] - fw * 0.28f) * ow).toInt().coerceIn(x0 + 1, ow)
            val y0 = ((b[1] + fh * 0.08f) * oh).toInt().coerceIn(0, oh - 1)
            val y1 = ((b[1] + fh * 0.28f) * oh).toInt().coerceIn(y0 + 1, oh)
            var sum = 0L; var cnt = 0
            for (yy in y0 until y1) for (xx in x0 until x1) { sum += (rgb[yy * ow + xx] shr 8) and 0xFF; cnt++ }
            if (cnt > 0) {
                ts.add(t); gs.add(sum.toDouble() / cnt)
                if (lastT > 0 && t - lastT < 0.2) validSec += t - lastT
                lastT = t
                progress = (validSec / scanLen).toFloat().coerceAtMost(1f)
                if (validSec >= scanLen) { progress = 1f; finish(); return }
            }
        }
        if (t - startT > scanLen * 3) { scanning = false; progress = 0f; msg = "Keep your face in view during the scan." }
    }
}

// ---------- screen ----------
@Composable
fun DDialog(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.clip(RoundedCornerShape(32.dp)).background(Color(0xFF10201C)).border(1.dp, FTeal.copy(.25f), RoundedCornerShape(32.dp))
            .verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(title, 22.sp, Color.White, FontWeight.Normal)
            content()
        }
    }
}

@Composable
fun DChip(label: String, on: Boolean, m: Modifier = Modifier, onClick: () -> Unit) {
    Box(m.clip(RoundedCornerShape(50)).background(if (on) FTeal else Color.White.copy(.1f)).clickable { onClick() }.padding(vertical = 12.dp, horizontal = 16.dp), Alignment.Center) {
        T(label, 14.sp, if (on) Color.Black else Color.White, FontWeight.Normal)
    }
}

@Composable
fun FaceScreen(ctx: Context, tick: () -> Unit, thud: () -> Unit, onBack: () -> Unit) {
    val act = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val cam = remember { FaceCam(ctx) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(act, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var dlg by remember { mutableStateOf("") }
    var hud by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose {}
        val exec = Executors.newSingleThreadExecutor()
        val fut = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        fut.addListener({
            try {
                val p = fut.get(); provider = p
                val analysis = ImageAnalysis.Builder().setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                analysis.setAnalyzer(exec) { img -> try { cam.analyze(img) } finally { img.close() } }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) { cam.msg = "Camera unavailable." }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { try { provider?.unbindAll() } catch (e: Exception) {}; exec.shutdown(); cam.cancel() }
    }

    val anim = rememberInfiniteTransition(label = "f")
    val sweep by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "sw")
    val flick by anim.animateFloat(0f, 12f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "fl")
    val fr = cam.frame
    val bx = cam.box
    val m = cam.metrics()
    val tensionTxt = m?.tension?.let { "$it% " + (if (it <= 15) "low" else if (it <= 30) "mid" else "high") } ?: "--"
    val recTxt = m?.rec?.let { "$it% " + (if (it >= 90) "high" else if (it >= 70) "good" else "low") } ?: "--"
    val gapTxt = m?.gap?.let { (if (it >= 0) "+" else "") + "$it ms" } ?: "--"
    val status = when {
        m == null -> "Ready"; m.rec == null -> "Baseline"
        (m.tension ?: 0) > 15 -> "Elevated"; m.rec >= 80 -> "Optimal"; else -> "Recovering"
    }
    val pill = when {
        cam.scanning && bx == null -> "Face not found"
        cam.scanning || cam.progress > 0f -> "Scanned: ${(cam.progress * 100).toInt()}%"
        bx == null -> "Align your face"
        else -> "Tap to scan"
    }
    val stamp = SimpleDateFormat("d MMM yyyy · hh:mm a", Locale.getDefault()).format(Date(m?.s?.t ?: System.currentTimeMillis())).lowercase()

    BoxWithConstraints(Modifier.fillMaxSize().background(FBg)) {
        val wDp = maxWidth; val hDp = maxHeight
        Canvas(Modifier.fillMaxSize()) {
            val W = size.width; val H = size.height; val d = density
            val fw = fr?.width ?: 3; val fh = fr?.height ?: 4
            val scale = max(W / fw, H / fh)
            val dw = fw * scale; val dh = fh * scale
            val offX = (W - dw) / 2f; val offY = (H - dh) / 2f
            if (fr != null) drawImage(fr, dstOffset = IntOffset(offX.roundToInt(), offY.roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()), filterQuality = FilterQuality.Low)
            drawRect(Brush.verticalGradient(listOf(Color(0xDD070F0D), Color.Transparent, Color.Transparent, Color(0xEE070F0D)), 0f, H))
            if (hud) {
                // measurement patch = forehead region that is really being sampled
                var l: Float; var t: Float; var r: Float; var b: Float
                if (bx != null) {
                    val fwn = bx[2] - bx[0]; val fhn = bx[3] - bx[1]
                    l = offX + (bx[0] + fwn * 0.28f) * dw; r = offX + (bx[2] - fwn * 0.28f) * dw
                    t = offY + (bx[1] + fhn * 0.08f) * dh; b = offY + (bx[1] + fhn * 0.28f) * dh
                } else { l = W * .3f; r = W * .7f; t = H * .36f; b = H * .48f }
                val minW = 150f * d; val minH = 96f * d
                if (r - l < minW) { val c = (l + r) / 2; l = c - minW / 2; r = c + minW / 2 }
                if (b - t < minH) { val c = (t + b) / 2; t = c - minH / 2; b = c + minH / 2 }
                val line = Color.White.copy(.35f)
                drawLine(line, Offset(l, 0f), Offset(l, H), 1.5f); drawLine(line, Offset(r, 0f), Offset(r, H), 1.5f)
                drawLine(line, Offset(0f, t), Offset(W, t), 1.5f); drawLine(line, Offset(0f, b), Offset(W, b), 1.5f)
                for (p in listOf(Offset(l, t), Offset(r, b))) {
                    drawLine(Color.White, Offset(p.x - 7f * d, p.y), Offset(p.x + 7f * d, p.y), 2f)
                    drawLine(Color.White, Offset(p.x, p.y - 7f * d), Offset(p.x, p.y + 7f * d), 2f)
                }
                // dotted tracking squares
                val marks = if (bx != null) listOf(
                    Offset(offX + bx[0] * dw - 16 * d, offY + bx[1] * dh - 16 * d), Offset(offX + bx[2] * dw + 4 * d, offY + bx[1] * dh + 20 * d),
                    Offset(offX + bx[0] * dw - 20 * d, offY + (bx[1] + (bx[3] - bx[1]) * .55f) * dh), Offset(offX + bx[2] * dw + 2 * d, offY + bx[3] * dh - 30 * d),
                    Offset(offX + (bx[0] + (bx[2] - bx[0]) * .3f) * dw, offY + bx[3] * dh + 14 * d))
                else listOf(Offset(W * .62f, H * .17f), Offset(W * .88f, H * .3f), Offset(W * .06f, H * .38f), Offset(W * .24f, H * .5f), Offset(W * .8f, H * .56f))
                marks.forEach { o ->
                    val sz = 26f * d
                    drawRect(Color.Black.copy(.35f), o, Size(sz, sz))
                    drawRect(Color.White.copy(.8f), o, Size(sz, sz), style = Stroke(1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * d, 3f * d))))
                }
                // glass patch with dot grid and lens hairlines
                val tl = Offset(l, t); val sz = Size(r - l, b - t)
                drawRoundRect(Color.White.copy(.10f), tl, sz, CornerRadius(6f * d))
                drawRoundRect(Color.White.copy(.3f), tl, sz, CornerRadius(6f * d), style = Stroke(1.5f))
                val cx = (l + r) / 2; val cy = (t + b) / 2
                for (k in 0 until 48) {
                    val a = k * (2f * Math.PI.toFloat() / 48f)
                    drawLine(Color.White.copy(.07f), Offset(cx, cy), Offset(cx + kotlin.math.cos(a) * (r - l), cy + kotlin.math.sin(a) * (b - t)), 1f)
                }
                for (i in 0 until 14) for (j in 0 until 6)
                    drawCircle(Color.White.copy(.3f), 1.7f * d, Offset(l + (r - l) * (i + .5f) / 14f, t + (b - t) * (j + .5f) / 6f))
                if (cam.scanning) {
                    val y = t + (b - t) * sweep
                    drawLine(FTeal.copy(.9f), Offset(l, y), Offset(r, y), 3f)
                }
                val tk = flick.toInt()
                for (i in 0 until 12) {
                    val px = l + (r - l) * (((i * 37 + tk * 13) % 100) / 100f)
                    val py = t + (b - t) * (((i * 53 + tk * 29) % 100) / 100f)
                    drawCircle(Color.White.copy(.85f), 2.2f * d, Offset(px, py))
                }
                // glass orbs with arrows at opposite corners
                for ((o, up) in listOf(Offset(r, t) to true, Offset(l, b) to false)) {
                    drawCircle(Color.White.copy(.12f), 17f * d, o)
                    drawCircle(Color.White.copy(.5f), 17f * d, o, style = Stroke(1.5f))
                    val s = if (up) 1f else -1f
                    drawLine(Color.White, Offset(o.x - 5f * d * s, o.y + 5f * d * s), Offset(o.x + 5f * d * s, o.y - 5f * d * s), 2f)
                    drawLine(Color.White, Offset(o.x + 5f * d * s, o.y - 5f * d * s), Offset(o.x + 1f * d * s, o.y - 5f * d * s), 2f)
                    drawLine(Color.White, Offset(o.x + 5f * d * s, o.y - 5f * d * s), Offset(o.x + 5f * d * s, o.y - 1f * d * s), 2f)
                }
            }
        }

        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
            // logo (back) + menu
            Box(Modifier.align(Alignment.TopStart).padding(top = 8.dp).size(62.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(.08f))
                .clickable { tick(); onBack() }, Alignment.Center) {
                Canvas(Modifier.size(40.dp)) {
                    drawCircle(Color.White.copy(.9f), size.width * .27f, Offset(size.width * .38f, size.height * .36f))
                    drawCircle(Color.White.copy(.45f), size.width * .27f, Offset(size.width * .6f, size.height * .6f))
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp).size(62.dp).clip(CircleShape).background(Color.White.copy(.08f))
                .clickable { tick(); dlg = "menu" }, Alignment.Center) {
                Canvas(Modifier.size(22.dp)) {
                    for (i in 0..2) drawLine(Color.White, Offset(0f, size.height * (.2f + i * .3f)), Offset(size.width, size.height * (.2f + i * .3f)), 2.5f, StrokeCap.Round)
                }
            }
            Column(Modifier.align(Alignment.TopStart).padding(top = hDp * 0.17f, start = 8.dp)) {
                Text("Facial data\ntelemetry", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.SansSerif, lineHeight = 34.sp, letterSpacing = (-0.5).sp)
                T(stamp + (m?.let { " · ${it.s.hr} bpm" } ?: ""), 11.sp, Color.White.copy(.5f), modifier = Modifier.padding(top = 10.dp))
            }
            Row(Modifier.align(Alignment.TopStart).padding(top = hDp * 0.31f, start = (wDp * 0.42f)), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 3.dp).width(2.dp).height(14.dp).background(Color.White))
                Column(Modifier.padding(start = 6.dp)) {
                    T("Hemodynamic Index" + (m?.let { " · ${it.s.rmssd} ms" } ?: ""), 12.sp, Color.White, FontWeight.Normal)
                    T("20 – 100+ ms", 11.sp, Color.White.copy(.5f))
                }
            }
            // center pill (starts / cancels the scan)
            Column(Modifier.align(Alignment.TopCenter).padding(top = hDp * 0.40f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(.14f)).border(1.dp, Color.White.copy(.35f), RoundedCornerShape(50))
                    .clickable {
                        thud()
                        if (!granted) launcher.launch(Manifest.permission.CAMERA)
                        else if (cam.scanning) cam.cancel() else if (cam.box != null) cam.startScan()
                    }.padding(horizontal = 30.dp, vertical = 16.dp)) { T(pill, 17.sp) }
                if (cam.msg.isNotEmpty()) T(cam.msg, 12.sp, Color.White.copy(.7f), modifier = Modifier.padding(top = 10.dp).width(220.dp), align = TextAlign.Center)
                if (!granted) T("Camera permission is needed for the scan.", 12.sp, Color.White.copy(.7f), modifier = Modifier.padding(top = 10.dp), align = TextAlign.Center)
            }
            // temperature (user entered) and status badge
            Column(Modifier.align(Alignment.BottomStart).padding(bottom = 214.dp, start = 12.dp).clickable { tick(); dlg = "temp" }) {
                Text(if (cam.temp > 0f) String.format(Locale.US, "%.1f °C", cam.temp) else "--.- °C", color = Color.White, fontSize = 40.sp,
                    fontWeight = FontWeight.Thin, fontFamily = FontFamily.SansSerif, letterSpacing = (-1).sp)
                T("Body temp", 13.sp, Color.White.copy(.5f))
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 214.dp, end = 4.dp).size(84.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(.06f)),
                Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                    drawArc(FTeal.copy(.35f), 200f, 300f, false, style = Stroke(10f, cap = StrokeCap.Round))
                    drawArc(FTeal, 215f, 250f, false, style = Stroke(3.5f, cap = StrokeCap.Round))
                    drawArc(FTeal.copy(.8f), 20f, 130f, false, Offset(size.width * .18f, size.height * .18f), Size(size.width * .64f, size.height * .64f), style = Stroke(3f, cap = StrokeCap.Round))
                }
                T(status, 12.sp, Color.White)
            }
            // bottom panel
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).fillMaxWidth().height(200.dp).clip(RoundedCornerShape(36.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF123A30), Color(0xFF08150F)))).border(1.dp, FTeal.copy(.2f), RoundedCornerShape(36.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Canvas(Modifier.size(22.dp)) {
                            val w = size.width
                            val p = Path().apply {
                                moveTo(w * .5f, w * .9f)
                                cubicTo(w * -.15f, w * .45f, w * .15f, w * .02f, w * .5f, w * .28f)
                                cubicTo(w * .85f, w * .02f, w * 1.15f, w * .45f, w * .5f, w * .9f)
                            }
                            drawPath(p, Color.White)
                        }
                        T("Mental Stress", 17.sp, Color.White, FontWeight.Normal)
                    }
                    T("···", 18.sp, Color.White.copy(.8f))
                }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Column { T("Recovery", 12.sp, Color.White.copy(.5f)); T(recTxt, 14.sp, Color.White, FontWeight.Normal) }
                    Column { T("HRV Gap", 12.sp, Color.White.copy(.5f)); T(gapTxt, 14.sp, Color.White, FontWeight.Normal) }
                    Column { T("Tension", 12.sp, Color.White.copy(.5f)); T(tensionTxt, 14.sp, Color.White, FontWeight.Normal) }
                }
                Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(.08f)),
                    Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black).clickable {
                        thud()
                        if (!granted) launcher.launch(Manifest.permission.CAMERA)
                        else if (cam.scanning) cam.cancel() else if (cam.box != null) cam.startScan()
                    }, Alignment.Center) {
                        Box(Modifier.size(if (cam.scanning) 14.dp else 10.dp).clip(if (cam.scanning) RoundedCornerShape(3.dp) else CircleShape).background(Color.White))
                    }
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(.9f)).clickable { tick(); dlg = "history" }, Alignment.Center) {
                        Canvas(Modifier.size(18.dp)) {
                            for (i in 0..2) drawLine(Color(0xFF10201C), Offset(size.width * (.2f + i * .3f), size.height * .9f),
                                Offset(size.width * (.2f + i * .3f), size.height * (.7f - i * .22f)), 3.5f, StrokeCap.Round)
                        }
                    }
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(.9f)).clickable { tick(); dlg = "info" }, Alignment.Center) { T("!", 18.sp, Color(0xFF10201C), FontWeight.Bold) }
                    Box(Modifier.padding(end = 10.dp).size(40.dp).clickable { tick(); hud = !hud }, Alignment.Center) {
                        Canvas(Modifier.size(22.dp)) {
                            for (i in 0..2) for (j in 0..1) drawCircle(Color.White.copy(if (hud) .95f else .4f), 2.2f, Offset(size.width * (.15f + i * .35f), size.height * (.3f + j * .4f)))
                        }
                    }
                }
            }
        }

        when (dlg) {
            "info" -> DDialog("About this scan", { dlg = "" }) {
                T("Hold the phone at eye level in even light and keep still. The front camera tracks tiny colour changes in your forehead to estimate pulse and heart-rate variability for ${cam.scanLen} seconds.", 13.sp, Color.White.copy(.8f))
                T("Recovery, HRV gap and tension compare each scan with your own earlier scans, so the first scan only sets your baseline. Body temperature is the value you enter from a thermometer.", 13.sp, Color.White.copy(.8f))
                T("These are wellness estimates, not medical measurements. Do not use them to diagnose or treat anything.", 13.sp, FTeal)
                DChip("Close", true, Modifier.fillMaxWidth()) { dlg = "" }
            }
            "menu" -> DDialog("Telemetry settings", { dlg = ""; confirm = false }) {
                T("Scan length", 12.sp, Color.White.copy(.6f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DChip("30 s", cam.scanLen == 30, Modifier.weight(1f)) { cam.setLen(30) }
                    DChip("60 s", cam.scanLen == 60, Modifier.weight(1f)) { cam.setLen(60) }
                }
                DChip(if (confirm) "Tap again to delete all scans" else "Clear all scans", false, Modifier.fillMaxWidth()) {
                    if (confirm) { cam.clearScans(); thud(); confirm = false; dlg = "" } else confirm = true
                }
                DChip("Close", true, Modifier.fillMaxWidth()) { dlg = ""; confirm = false }
            }
            "temp" -> {
                var txt by remember { mutableStateOf(if (cam.temp > 0f) String.format(Locale.US, "%.1f", cam.temp) else "") }
                DDialog("Body temperature", { dlg = "" }) {
                    T("Enter the reading from your thermometer (°C).", 13.sp, Color.White.copy(.7f))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(.1f)).padding(14.dp)) {
                        BasicTextField(txt, { if (it.length <= 4 && it.all { c -> c.isDigit() || c == '.' } && it.count { c -> c == '.' } <= 1) txt = it },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            textStyle = TextStyle(color = Color.White, fontSize = 20.sp, fontFamily = FontFamily.SansSerif),
                            cursorBrush = SolidColor(FTeal), modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DChip("Cancel", false, Modifier.weight(1f)) { dlg = "" }
                        DChip("Save", true, Modifier.weight(1f)) {
                            val v = txt.toFloatOrNull()
                            if (v != null && v in 30f..45f) { cam.setTemp(v); thud(); dlg = "" }
                        }
                    }
                }
            }
            "history" -> DDialog("Scan history", { dlg = "" }) {
                val list = cam.scans.take(10).reversed()
                if (list.isEmpty()) T("No scans yet. Tap the centre pill to start.", 13.sp, Color.White.copy(.7f))
                else {
                    val mx = max(list.maxOf { it.rmssd }, 1)
                    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                        val bw = size.width / list.size
                        list.forEachIndexed { i, sc ->
                            val h = size.height * sc.rmssd / mx
                            drawRoundRect(FTeal.copy(.85f), Offset(i * bw + bw * .2f, size.height - h), Size(bw * .6f, h), CornerRadius(bw * .3f))
                        }
                    }
                    T("HRV (RMSSD, ms) — latest scans", 11.sp, Color.White.copy(.5f))
                    cam.scans.take(10).forEach { sc ->
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            T(SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(sc.t)), 13.sp, Color.White.copy(.8f))
                            T("${sc.hr} bpm · ${sc.rmssd} ms", 13.sp, Color.White)
                        }
                    }
                }
                DChip("Close", true, Modifier.fillMaxWidth()) { dlg = "" }
            }
        }
    }
}
