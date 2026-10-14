package com.rhf.handtrack

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var info: TextView
    private lateinit var executor: ExecutorService
    private var landmarker: HandLandmarker? = null
    private var front = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = Executors.newSingleThreadExecutor()
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = OverlayView(this)
        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 48, 24, 24)
        }
        val btn = Button(this).apply {
            text = "Ganti kamera"
            setOnClickListener { front = !front; startCamera() }
        }
        val mp = FrameLayout.LayoutParams.MATCH_PARENT
        val wc = FrameLayout.LayoutParams.WRAP_CONTENT
        val root = FrameLayout(this)
        root.addView(previewView, FrameLayout.LayoutParams(mp, mp))
        root.addView(overlay, FrameLayout.LayoutParams(mp, mp))
        root.addView(info, FrameLayout.LayoutParams(mp, wc, Gravity.TOP))
        root.addView(btn, FrameLayout.LayoutParams(wc, wc, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)

        landmarker = try {
            buildLandmarker(Delegate.GPU)
        } catch (e: Exception) {
            try { buildLandmarker(Delegate.CPU) } catch (e2: Exception) {
                info.text = "Gagal load model: ${e2.message}"
                null
            }
        }

        val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) { startCamera() } else { info.text = "Izin kamera ditolak" }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ask.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildLandmarker(delegate: Delegate): HandLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(delegate)
            .build()
        val opts = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.6f)
            .setMinHandPresenceConfidence(0.6f)
            .setMinTrackingConfidence(0.6f)
            .setResultListener { r, img -> onResult(r, img) }
            .setErrorListener { e -> runOnUiThread { info.text = "Error: ${e.message}" } }
            .build()
        return HandLandmarker.createFromOptions(this, opts)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(executor) { p -> analyze(p) } }
            val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            provider.bindToLifecycle(this, sel, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(p: ImageProxy) {
        val lm = landmarker
        try {
            if (lm != null) {
                val raw = p.toBitmap()
                val m = Matrix().apply { postRotate(p.imageInfo.rotationDegrees.toFloat()) }
                val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                lm.detectAsync(BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis())
            }
        } finally {
            p.close()
        }
    }

    private fun onResult(r: HandLandmarkerResult, img: MPImage) {
        val hands = r.landmarks().map { h -> h.map { PointF(it.x(), it.y()) } }
        val text = if (hands.isEmpty()) "Tidak ada tangan"
        else hands.mapIndexed { i, h -> "Tangan ${i + 1}: ${gesture(h)}" }.joinToString("\n")
        runOnUiThread {
            overlay.update(hands, img.width, img.height, front)
            info.text = text
        }
    }

    private fun d(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)

    private fun gesture(h: List<PointF>): String {
        val palm = d(h[0], h[9])
        if (d(h[4], h[8]) < 0.35f * palm) return "PINCH"
        var n = 0
        for (t in intArrayOf(8, 12, 16, 20)) if (d(h[t], h[0]) > d(h[t - 2], h[0])) n++
        if (d(h[4], h[17]) > d(h[3], h[17])) n++
        return when (n) {
            0 -> "KEPALAN"
            5 -> "TELAPAK TERBUKA"
            else -> "$n jari terbuka"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        landmarker?.close()
        executor.shutdown()
    }
}

class OverlayView(c: Context) : View(c) {
    private var hands: List<List<PointF>> = emptyList()
    private var prev: List<List<PointF>> = emptyList()
    private var iw = 1
    private var ih = 1
    private var mirror = true
    private val line = Paint().apply { color = Color.GREEN; strokeWidth = 6f; isAntiAlias = true }
    private val dot = Paint().apply { color = Color.RED; isAntiAlias = true }

    fun update(new: List<List<PointF>>, w: Int, h: Int, front: Boolean) {
        iw = w; ih = h; mirror = front
        hands = new.mapIndexed { i, hand ->
            val old = prev.getOrNull(i)
            hand.mapIndexed { j, p ->
                val o = old?.getOrNull(j)
                if (o == null) PointF(p.x, p.y) else {
                    val a = min(1f, 0.35f + hypot(p.x - o.x, p.y - o.y) * 10f)
                    PointF(o.x + (p.x - o.x) * a, o.y + (p.y - o.y) * a)
                }
            }
        }
        prev = hands
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = max(width / iw.toFloat(), height / ih.toFloat())
        val dx = (width - iw * s) / 2f
        val dy = (height - ih * s) / 2f
        fun px(p: PointF) = (if (mirror) 1f - p.x else p.x) * iw * s + dx
        fun py(p: PointF) = p.y * ih * s + dy
        for (h in hands) {
            HandLandmarker.HAND_CONNECTIONS.forEach { k ->
                c.drawLine(px(h[k.start()]), py(h[k.start()]), px(h[k.end()]), py(h[k.end()]), line)
            }
            h.forEach { c.drawCircle(px(it), py(it), 9f, dot) }
        }
    }
}
