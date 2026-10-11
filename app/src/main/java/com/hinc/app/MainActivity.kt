package com.hinc.app

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val Blue = Color(0xFF1F6BFF)
val Lime = Color(0xFFC9F31D)
val Soft = Color(0xFFE9E9E9)
val Dark = Color(0xFF1C1C1E)
val STAGES = listOf("Claim\nInitiated", "Survey\nCompleted", "Documents\nUploaded", "Claim\nSettled")
val STATUS = listOf("Your claim has been initiated", "Your survey is completed", "Your documents are uploaded", "Your claim has been settled")

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
        setContent { HincApp(applicationContext) { moveTaskToBack(true) } }
    }
}

// ---------- state ----------
class Ui(val c: Context) {
    private val s = Store(c)
    var name by mutableStateOf(s.name)
    var dos by mutableStateOf(s.dos)
    var cid by mutableStateOf(s.cid)
    var plan by mutableStateOf(s.plan)
    var pay by mutableStateOf(s.pay)
    var stage by mutableStateOf(s.stage)
    var docs by mutableStateOf(s.docs())
    var msgs by mutableStateOf(s.msgs())

    fun save() {
        s.name = name; s.dos = dos; s.cid = cid; s.plan = plan; s.pay = pay; s.stage = stage
        HincWidget.refresh(c)
    }
    fun setStage(i: Int) { stage = i; save() }
    fun addMsg(t: String) { msgs = msgs + Msg(t, System.currentTimeMillis()); s.saveMsgs(msgs) }
    fun importDoc(ctx: Context, u: Uri, cat: String): Doc? {
        try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
        val n = try {
            ctx.contentResolver.query(u, null, null, null, null)?.use { cur ->
                val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cur.moveToFirst() && i >= 0) cur.getString(i) else null
            }
        } catch (e: Exception) { null } ?: "Document"
        val d = Doc(u.toString(), n, cat, System.currentTimeMillis(), ctx.contentResolver.getType(u) ?: "*/*")
        docs = listOf(d) + docs; s.saveDocs(docs)
        if (stage < 2) stage = 2
        save()
        addMsg("Attached ${cat.lowercase()}: $n")
        return d
    }
}

fun openDoc(c: Context, d: Doc) {
    try {
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(d.uri), d.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {}
}

fun thumb(c: Context, d: Doc): ImageBitmap? = try {
    val uri = Uri.parse(d.uri)
    if (d.mime.startsWith("image")) {
        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }?.asImageBitmap()
    } else if (d.mime == "application/pdf") {
        c.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val r = PdfRenderer(pfd)
            val p = r.openPage(0)
            val w = 360; val h = (w.toFloat() * p.height / p.width).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close(); r.close()
            bmp.asImageBitmap()
        }
    } else null
} catch (e: Exception) { null }

// ---------- root ----------
@Composable
fun HincApp(ctx: Context, onExit: () -> Unit) {
    val ui = remember { Ui(ctx) }
    val act = LocalContext.current
    var screen by remember { mutableStateOf(0) }
    var edit by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<List<Doc>>(emptyList()) }
    var cat by remember { mutableStateOf("Document") }
    val haptic = LocalHapticFeedback.current
    val tick: () -> Unit = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val thud: () -> Unit = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val added = uris.mapNotNull { ui.importDoc(act, it, cat) }
        if (added.isNotEmpty()) { sheet = added; thud() }
    }
    val attach: (String) -> Unit = { c -> cat = c; picker.launch(arrayOf("*/*")) }
    BackHandler(screen == 1 || sheet.isNotEmpty()) { if (sheet.isNotEmpty()) sheet = emptyList() else screen = 0 }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(if (screen == 1) Blue else Color.Black)) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (screen == 0) ClaimScreen(ui, tick, onBack = onExit, onChat = { screen = 1 },
                    onEdit = { edit = true }, onAdd = { attach("Document") }, onOpen = { openDoc(act, it) })
                else ChatScreen(ui, tick, thud, onBack = { screen = 0 }, onAttach = attach)
            }
            if (sheet.isNotEmpty()) UploadedSheet(sheet, onOpen = { openDoc(act, it) }, onClose = { sheet = emptyList() })
            if (edit) EditDialog(ui, onClose = { edit = false }, thud = thud)
        }
    }
}

// ---------- helpers ----------
@Composable
fun T(s: String, size: TextUnit = 16.sp, color: Color = Color.White, weight: FontWeight = FontWeight.Normal,
      modifier: Modifier = Modifier, align: TextAlign? = null) =
    Text(s, modifier = modifier, color = color, fontSize = size, fontWeight = weight,
        fontFamily = FontFamily.SansSerif, letterSpacing = (-0.3).sp, textAlign = align)

@Composable
fun Circle(label: String, bg: Color, fg: Color = Color.White, sz: Dp = 44.dp, onClick: () -> Unit) {
    Box(Modifier.size(sz).clip(CircleShape).background(bg).clickable { onClick() }, Alignment.Center) { T(label, 18.sp, fg) }
}

@Composable
fun Ico(kind: String, color: Color, sz: Dp = 26.dp) {
    Canvas(Modifier.size(sz)) {
        val w = size.width
        val st = Stroke(w * .075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            "card" -> {
                drawRoundRect(color, Offset(w * .08f, w * .2f), Size(w * .84f, w * .6f), CornerRadius(w * .1f), style = st)
                drawLine(color, Offset(w * .08f, w * .4f), Offset(w * .92f, w * .4f), st.width)
            }
            "bank" -> {
                val p = Path().apply { moveTo(w * .5f, w * .12f); lineTo(w * .9f, w * .34f); lineTo(w * .1f, w * .34f); close() }
                drawPath(p, color, style = st)
                for (x in listOf(.22f, .5f, .78f)) drawLine(color, Offset(w * x, w * .44f), Offset(w * x, w * .72f), st.width)
                drawLine(color, Offset(w * .1f, w * .84f), Offset(w * .9f, w * .84f), st.width)
            }
            "coin" -> {
                drawCircle(color, w * .4f, center, style = st)
                drawLine(color, Offset(w * .5f, w * .26f), Offset(w * .5f, w * .74f), st.width)
                drawLine(color, Offset(w * .38f, w * .42f), Offset(w * .62f, w * .42f), st.width)
                drawLine(color, Offset(w * .38f, w * .58f), Offset(w * .62f, w * .58f), st.width)
            }
            else -> {
                drawRoundRect(color, Offset(w * .18f, w * .1f), Size(w * .64f, w * .8f), CornerRadius(w * .1f), style = st)
                drawLine(color, Offset(w * .32f, w * .36f), Offset(w * .68f, w * .36f), st.width)
                drawLine(color, Offset(w * .32f, w * .54f), Offset(w * .68f, w * .54f), st.width)
                drawLine(color, Offset(w * .32f, w * .72f), Offset(w * .5f, w * .72f), st.width)
            }
        }
    }
}

fun money(v: Int) = if (v < 0) "--" else "\$$v"
val DAY = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
val CLOCK = SimpleDateFormat("h:mm a", Locale.getDefault())

@Composable
fun Thumb(d: Doc, m: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, d.uri) { value = withContext(Dispatchers.IO) { thumb(ctx, d) } }
    Box(m.clip(RoundedCornerShape(14.dp)).background(Color.White), Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        else Ico("doc", Color.Gray, 34.dp)
    }
}

// ---------- claim screen ----------
@Composable
fun ClaimScreen(ui: Ui, tick: () -> Unit, onBack: () -> Unit, onChat: () -> Unit,
                onEdit: () -> Unit, onAdd: () -> Unit, onOpen: (Doc) -> Unit) {
    val scroll = rememberScrollState()
    val docScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Circle("‹", Dark, sz = 54.dp) { tick(); onBack() }
                Column {
                    T(if (ui.name.isBlank()) "Hi!" else "Hi, ${ui.name.trim().split(" ").first()}!", 24.sp, weight = FontWeight.Medium)
                    T(STATUS[ui.stage.coerceIn(0, 3)], 13.sp)
                }
            }
            Column(Modifier.padding(top = 6.dp)) {
                Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                    val cw = size.width / 4; val cy = size.height / 2
                    fun cx(i: Int) = cw * (i + .5f)
                    drawLine(Color(0xFF2A2A2D), Offset(cx(0), cy), Offset(cx(3), cy), 16f, StrokeCap.Round)
                    var x = cx(0)
                    while (x < cx(ui.stage)) { drawCircle(Color.White, 2f, Offset(x, cy)); x += 12f }
                    for (i in 0..3) {
                        val c = when { i == 3 -> Lime; i == ui.stage -> Blue; i < ui.stage -> Color.White; else -> Color(0xFF3A3A3D) }
                        drawCircle(c, 10f, Offset(cx(i), cy))
                        if (i < ui.stage) drawCircle(Color.Black, 4f, Offset(cx(i), cy))
                    }
                }
                Row(Modifier.padding(top = 6.dp)) {
                    STAGES.forEachIndexed { i, l ->
                        Box(Modifier.weight(1f).clickable { tick(); ui.setStage(i) }, Alignment.Center) { T(l, 11.sp, align = TextAlign.Center) }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(36.dp)).background(Blue).clickable { tick(); onEdit() }.padding(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(.25f)), Alignment.Center) {
                            T(ui.name.trim().firstOrNull()?.uppercase() ?: "+", 22.sp)
                        }
                        T(if (ui.name.isBlank()) "Tap to add your claim" else ui.name, 20.sp, weight = FontWeight.Medium)
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { T("Date of service", 14.sp); T(ui.dos.ifBlank { "--" }, 14.sp) }
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { T("Claim ID", 14.sp); T(ui.cid.ifBlank { "--" }, 14.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val sh = RoundedCornerShape(34.dp)
                Box(Modifier.weight(1f).height(170.dp).clip(sh).background(Color.Black).border(1.dp, Color(0xFF3A3A3D), sh)
                    .clickable { tick(); onEdit() }.padding(18.dp)) {
                    Ico("doc", Color.White)
                    Column(Modifier.align(Alignment.BottomStart)) { T("Your plan pays", 13.sp); T(money(ui.plan), 36.sp, weight = FontWeight.Medium) }
                }
                Box(Modifier.weight(1f).height(170.dp).clip(sh).background(Lime).clickable { tick(); onEdit() }.padding(18.dp)) {
                    Ico("coin", Color.Black)
                    Column(Modifier.align(Alignment.BottomStart)) { T("You need to pay", 13.sp, Color.Black); T(money(ui.pay), 36.sp, Color.Black, FontWeight.Medium) }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(36.dp)).background(Color.White).padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    T("Documents", 20.sp, Color.Black, FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Circle("←", Soft, Color.Black) { tick(); scope.launch { docScroll.animateScrollTo((docScroll.value - 320).coerceAtLeast(0)) } }
                        Circle("→", Color.Black) { tick(); scope.launch { docScroll.animateScrollTo(docScroll.value + 320) } }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.horizontalScroll(docScroll), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ui.docs.forEachIndexed { i, d ->
                        val first = i == 0
                        Column(Modifier.width(150.dp).height(210.dp).clip(RoundedCornerShape(28.dp))
                            .background(if (first) Color.Black else Soft).clickable { tick(); onOpen(d) }.padding(14.dp)) {
                            T(DAY.format(Date(d.t)), 11.sp, if (first) Color(0xFFAAAAAA) else Color(0xFF777777))
                            T(d.cat, 16.sp, if (first) Color.White else Color.Black, FontWeight.Medium)
                            Spacer(Modifier.height(10.dp))
                            Thumb(d, Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                    Column(Modifier.width(150.dp).height(210.dp).clip(RoundedCornerShape(28.dp)).background(Soft)
                        .clickable { tick(); onAdd() }, Arrangement.Center, Alignment.CenterHorizontally) {
                        T("+", 40.sp, Color.Black); T("Add document", 13.sp, Color.Black)
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).clip(RoundedCornerShape(50)).background(Blue)
            .clickable { tick(); onChat() }.padding(start = 24.dp, end = 6.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            T("Broker chat", 16.sp, weight = FontWeight.Medium)
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black), Alignment.Center) { T("→", 18.sp) }
        }
    }
}


// ---------- chat ----------
@Composable
fun ChatScreen(ui: Ui, tick: () -> Unit, thud: () -> Unit, onBack: () -> Unit, onAttach: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("Surgery bill") }
    val scroll = rememberScrollState()
    LaunchedEffect(ui.msgs.size) { scroll.animateScrollTo(scroll.maxValue) }
    val items = listOf("Attach new\nsurgery bill" to "Surgery bill", "Attach your\nID Card" to "ID Card", "Attach new\ninvoices" to "Invoices",
        "Attach payment\nrecords" to "Payment records", "Attach bank\nrecords" to "Bank records", "Attach legal\ndocuments" to "Legal documents")
    val icons = listOf("doc", "card", "doc", "card", "bank", "doc")
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Circle("←", Color.White.copy(.2f)) { tick(); onBack() }
            T("Broker\nChat", 26.sp, weight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Circle("×", Color.White.copy(.2f)) { tick(); onBack() }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.msgs.isEmpty()) T("Write to your broker or attach a document below. Messages are saved on this device.", 14.sp, Color.White.copy(.85f))
            ui.msgs.forEach { m ->
                Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.Bottom) {
                    T(CLOCK.format(Date(m.t)), 11.sp, Color.White.copy(.75f), modifier = Modifier.padding(end = 8.dp, bottom = 4.dp))
                    Box(Modifier.widthIn(max = 250.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(horizontal = 18.dp, vertical = 12.dp)) {
                        T(m.text, 14.sp, Color.Black)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(.2f)).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(38.dp).clip(CircleShape).clickable { tick(); text += "🙂" }, Alignment.Center) { T("🙂", 18.sp) }
            BasicTextField(text, { text = it }, Modifier.weight(1f),
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontFamily = FontFamily.SansSerif),
                cursorBrush = SolidColor(Color.White), singleLine = true,
                decorationBox = { inner -> Box { if (text.isEmpty()) T("Type something…", 15.sp, Color.White.copy(.65f)); inner() } })
            Box(Modifier.size(38.dp).clip(CircleShape).clickable { tick(); onAttach(last) }, Alignment.Center) { Ico("doc", Color.White, 22.dp) }
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black).clickable {
                if (text.isNotBlank()) { ui.addMsg(text.trim()); text = ""; thud() }
            }, Alignment.Center) {
                Canvas(Modifier.size(20.dp)) {
                    val p = Path().apply { moveTo(size.width, 0f); lineTo(0f, size.height * .5f); lineTo(size.width * .4f, size.height * .6f); close() }
                    drawPath(p, Color.White, style = Stroke(3f, join = StrokeJoin.Round))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in 0..2) {
                    val k = r * 3 + c; val sel = items[k].second == last
                    Box(Modifier.weight(1f).height(110.dp).clip(RoundedCornerShape(26.dp)).background(if (sel) Color.Black else Color.White)
                        .clickable { tick(); last = items[k].second; onAttach(items[k].second) }.padding(12.dp)) {
                        Ico(icons[k], if (sel) Color.White else Color.Black, 24.dp)
                        T(items[k].first, 11.sp, if (sel) Color.White else Color.Black, modifier = Modifier.align(Alignment.BottomStart))
                    }
                }
            }
        }
    }
}

// ---------- uploaded sheet ----------
@Composable
fun UploadedSheet(docs: List<Doc>, onOpen: (Doc) -> Unit, onClose: () -> Unit) {
    val none = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(.55f)).clickable(interactionSource = none, indication = null) { onClose() }) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(40.dp)).background(Color.White)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.Black))
            Spacer(Modifier.height(18.dp))
            Box(Modifier.size(68.dp).clip(CircleShape).background(Lime), Alignment.Center) {
                Canvas(Modifier.size(24.dp)) {
                    val p = Path().apply { moveTo(size.width * .1f, size.height * .55f); lineTo(size.width * .4f, size.height * .85f); lineTo(size.width * .92f, size.height * .2f) }
                    drawPath(p, Color.Black, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            Spacer(Modifier.height(12.dp))
            T("Uploaded", 34.sp, Color.Black, FontWeight.Medium)
            T(if (docs.size == 1) "Your document was successfully uploaded" else "Your documents were successfully uploaded", 13.sp, Color.Black)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                docs.take(2).forEach { d ->
                    Box(Modifier.weight(1f).height(210.dp).clip(RoundedCornerShape(30.dp)).background(Soft).padding(12.dp)) {
                        Thumb(d, Modifier.fillMaxWidth().height(120.dp))
                        T(d.cat, 14.sp, Color.Black, modifier = Modifier.align(Alignment.BottomStart))
                        Box(Modifier.align(Alignment.BottomEnd).size(44.dp).clip(CircleShape).background(Color.Black).clickable { onOpen(d) }, Alignment.Center) { T("↗", 18.sp) }
                    }
                }
                if (docs.size == 1) Spacer(Modifier.weight(1f))
            }
            if (docs.size > 2) T("+${docs.size - 2} more in Documents", 13.sp, Color(0xFF777777), modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(14.dp))
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.Black).clickable { onClose() }.padding(horizontal = 40.dp, vertical = 14.dp)) { T("Done", 15.sp) }
        }
    }
}

// ---------- edit dialog ----------
@Composable
fun Field(label: String, value: String, kb: KeyboardType = KeyboardType.Text, onClick: (() -> Unit)? = null, onChange: (String) -> Unit) {
    Column {
        T(label, 12.sp, Color(0xFF777777), modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Soft)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(14.dp)) {
            if (onClick != null) T(value.ifBlank { "Select date" }, 16.sp, Color.Black)
            else BasicTextField(value, onChange, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = kb),
                textStyle = TextStyle(color = Color.Black, fontSize = 16.sp, fontFamily = FontFamily.SansSerif),
                cursorBrush = SolidColor(Blue), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun EditDialog(ui: Ui, onClose: () -> Unit, thud: () -> Unit) {
    val act = LocalContext.current
    var name by remember { mutableStateOf(ui.name) }
    var dos by remember { mutableStateOf(ui.dos) }
    var cid by remember { mutableStateOf(ui.cid) }
    var plan by remember { mutableStateOf(if (ui.plan >= 0) ui.plan.toString() else "") }
    var pay by remember { mutableStateOf(if (ui.pay >= 0) ui.pay.toString() else "") }
    val shape = RoundedCornerShape(36.dp)
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.clip(shape).background(Color.White).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            T("Claim details", 22.sp, Color.Black, FontWeight.Medium)
            Field("Full name", name) { name = it }
            Field("Date of service", dos, onClick = {
                val cal = Calendar.getInstance()
                DatePickerDialog(act, { _, y, m, d -> dos = String.format(Locale.US, "%02d/%02d/%02d", d, m + 1, y % 100) },
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }) {}
            Field("Claim ID", cid) { cid = it.uppercase() }
            Field("Your plan pays ($)", plan, KeyboardType.Number) { if (it.length <= 7 && it.all { c -> c.isDigit() }) plan = it }
            Field("You need to pay ($)", pay, KeyboardType.Number) { if (it.length <= 7 && it.all { c -> c.isDigit() }) pay = it }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Soft).clickable { onClose() }.padding(vertical = 14.dp), Alignment.Center) { T("Cancel", 15.sp, Color.Black) }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Blue).clickable {
                    ui.name = name.trim(); ui.dos = dos; ui.cid = cid.trim()
                    ui.plan = plan.toIntOrNull() ?: -1; ui.pay = pay.toIntOrNull() ?: -1
                    ui.save(); thud(); onClose()
                }.padding(vertical = 14.dp), Alignment.Center) { T("Save", 15.sp) }
            }
        }
    }
}
