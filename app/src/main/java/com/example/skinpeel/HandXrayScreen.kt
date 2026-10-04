package com.example.skinpeel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import androidx.compose.runtime.SideEffect
import android.os.Looper
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val PenColor = Color(0xFF10B6C9)

// ---------- Tuning ----------
private const val START_FRAMES = 2
private const val STOP_FRAMES = 6
private const val LOST_FRAMES = 6
private const val MIN_POINT_PX = 2f
private const val PALM_FRAMES = 8
private const val REARM_FRAMES = 8
private const val FIST_FRAMES = 10

// ---------- Gesture helpers ----------
private fun toScreen(l: NormalizedLandmark, r: HandResult, w: Float, h: Float): Offset {
    val iw = r.imageWidth.toFloat()
    val ih = r.imageHeight.toFloat()
    val scale = max(w / iw, h / ih)
    val dx = (w - iw * scale) / 2f
    val dy = (h - ih * scale) / 2f
    return Offset(l.x() * iw * scale + dx, l.y() * ih * scale + dy)
}

private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float =
    hypot(a.x() - b.x(), a.y() - b.y())

private fun isExtended(lm: List<NormalizedLandmark>, tip: Int, pip: Int): Boolean =
    dist(lm[0], lm[tip]) > dist(lm[0], lm[pip]) * 1.05f

private fun isDrawGesture(lm: List<NormalizedLandmark>): Boolean =
    isExtended(lm, 8, 6) &&
            !isExtended(lm, 12, 10) &&
            !isExtended(lm, 16, 14) &&
            !isExtended(lm, 20, 18)

private fun isOpenPalm(lm: List<NormalizedLandmark>): Boolean =
    isExtended(lm, 8, 6) &&
            isExtended(lm, 12, 10) &&
            isExtended(lm, 16, 14) &&
            isExtended(lm, 20, 18) &&
            dist(lm[4], lm[17]) > dist(lm[3], lm[17]) * 1.1f   // angootha bahar

// Chaaron ungliyan band = mutthi
private fun isFist(lm: List<NormalizedLandmark>): Boolean =
    !isExtended(lm, 8, 6) &&
            !isExtended(lm, 12, 10) &&
            !isExtended(lm, 16, 14) &&
            !isExtended(lm, 20, 18)

private class StrokeData(val points: List<Offset>) {
    private var path: Path? = null
    private var cw = 0f
    private var ch = 0f

    fun path(w: Float, h: Float): Path {
        val cached = path
        if (cached != null && cw == w && ch == h) return cached
        return buildSmoothPath(points, w, h).also { path = it; cw = w; ch = h }
    }
}

private fun buildSmoothPath(points: List<Offset>, w: Float, h: Float): Path {
    val path = Path()
    if (points.isEmpty()) return path
    val p = points.map { Offset(it.x * w, it.y * h) }
    path.moveTo(p[0].x, p[0].y)
    if (p.size == 1) {
        path.lineTo(p[0].x + 0.1f, p[0].y)
        return path
    }
    for (i in 1 until p.size - 1) {
        val mx = (p[i].x + p[i + 1].x) / 2f
        val my = (p[i].y + p[i + 1].y) / 2f
        path.quadraticBezierTo(p[i].x, p[i].y, mx, my)
    }
    path.lineTo(p.last().x, p.last().y)
    return path
}

private fun DrawScope.drawStrokes(
    strokes: List<StrokeData>,
    current: List<Offset>,
    color: Color
) {
    val style = Stroke(
        width = size.width * 0.014f,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round
    )
    strokes.forEach { drawPath(it.path(size.width, size.height), color, style = style) }
    if (current.isNotEmpty()) {
        drawPath(buildSmoothPath(current, size.width, size.height), color, style = style)
    }
}

private class DrawingEngine {
    val strokes = ArrayList<StrokeData>()
    val current = ArrayList<Offset>()
    var cursor: Offset? = null
    var drawing = false
    var width = 0f
    var height = 0f
    var version by mutableIntStateOf(0)

    var paused = false
    var onOpenPalm: (() -> Unit)? = null

    private var smooth: Offset? = null
    private var onFrames = 0
    private var offFrames = 0
    private var lostFrames = 0
    private var palmFrames = 0
    private var notPalmFrames = 0
    private var armed = true
    private var fistFrames = 0
    private var notFistFrames = 0
    private var fistArmed = true

    private val main = Handler(Looper.getMainLooper())
    private val latest = AtomicReference<HandResult?>(null)

    fun post(result: HandResult) {
        if (latest.getAndSet(result) == null) {
            main.post { latest.getAndSet(null)?.let { update(it) } }
        }
    }

    fun clear() {
        strokes.clear()
        current.clear()
        drawing = false
        onFrames = 0
        offFrames = 0
        version++
    }

    private fun finish(trimTail: Int = 0) {
        repeat(min(trimTail, max(0, current.size - 2))) { current.removeAt(current.lastIndex) }
        if (current.size >= 2) strokes.add(StrokeData(current.toList()))
        current.clear()
    }

    private fun update(r: HandResult) {
        if (width <= 0f || height <= 0f) return
        val lm = r.hands.firstOrNull()

        if (lm == null) {
            lostFrames++
            palmFrames = 0
            fistFrames = 0
            if (lostFrames > LOST_FRAMES) {
                if (drawing) finish()
                drawing = false
                cursor = null
                smooth = null
                onFrames = 0
                offFrames = 0
                armed = true
                fistArmed = true
            }
            version++
            return
        }
        lostFrames = 0

        if (isOpenPalm(lm)) {
            palmFrames++
            notPalmFrames = 0
        } else {
            palmFrames = 0
            notPalmFrames++
            if (notPalmFrames >= REARM_FRAMES) armed = true
        }

        val hasDrawing = strokes.isNotEmpty() || current.size >= 2
        if (armed && !paused && palmFrames >= PALM_FRAMES && hasDrawing) {
            armed = false
            if (drawing) { drawing = false; finish() }
            onOpenPalm?.invoke()
            version++
            return
        }

        if (isFist(lm)) {
            fistFrames++
            notFistFrames = 0
        } else {
            fistFrames = 0
            notFistFrames++
            if (notFistFrames >= REARM_FRAMES) fistArmed = true
        }

        if (fistArmed && fistFrames >= FIST_FRAMES && hasDrawing) {
            fistArmed = false
            clear()
            return
        }

        val raw = toScreen(lm[8], r, width, height)
        val prev = smooth
        val s = if (prev == null) raw else {
            val d = (raw - prev).getDistance()
            val a = (d / (d + 25f)).coerceIn(0.3f, 0.9f)
            prev + (raw - prev) * a
        }
        smooth = s
        cursor = s

        if (paused) { version++; return }

        if (isDrawGesture(lm)) { onFrames++; offFrames = 0 } else { offFrames++; onFrames = 0 }

        if (!drawing && onFrames >= START_FRAMES) {
            drawing = true
        } else if (drawing && offFrames >= STOP_FRAMES) {
            drawing = false
            finish(trimTail = STOP_FRAMES - 1)
        }

        if (drawing) {
            val n = Offset(s.x / width, s.y / height)
            val last = current.lastOrNull()
            if (last == null || (n - last).getDistance() * width > MIN_POINT_PX) current.add(n)
        }
        version++
    }
}

@Composable
fun HandXrayScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val engine = remember { DrawingEngine() }
    var showPopup by remember { mutableStateOf(false) }
    var screenSize by remember { mutableStateOf(IntSize.Zero) }
    SideEffect {
        engine.paused = showPopup
        engine.onOpenPalm = { showPopup = true }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }
    if (!hasPermission) return

    val useFront = true
    val tracker = remember { HandTracker(context) { engine.post(it) } }
    DisposableEffect(Unit) { onDispose { tracker.close() } }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged {
                screenSize = it
                engine.width = it.width.toFloat()
                engine.height = it.height.toFloat()
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(480, 640))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also {
                            it.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
                                tracker.detect(proxy, useFront)
                            }
                        }

                    val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA

                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        Canvas(Modifier.fillMaxSize()) {
            engine.version
            drawStrokes(engine.strokes, engine.current, PenColor)
            engine.cursor?.let {
                drawCircle(
                    color = if (engine.drawing) PenColor else Color.White,
                    radius = size.width * 0.025f,
                    center = it,
                    style = Stroke(width = 4f)
                )
            }
        }


    }

    if (showPopup) {
        Dialog(
            onDismissRequest = { showPopup = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF15181D),
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Your drawing", color = Color.White)
                    Spacer(Modifier.height(12.dp))

                    val ratio = if (screenSize.height > 0) {
                        screenSize.width.toFloat() / screenSize.height
                    } else 0.5f

                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                            .background(Color.Black, RoundedCornerShape(16.dp))
                    ) {
                        engine.version
                        drawStrokes(engine.strokes, engine.current, PenColor)
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { engine.clear() }) { Text("Clear") }
                        Button(onClick = { showPopup = false }) { Text("Close") }
                    }
                }
            }
        }
    }
}