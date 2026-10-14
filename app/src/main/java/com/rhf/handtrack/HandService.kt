package com.rhf.handtrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

class OneEuro(
    private val minCutoff: Double = 1.7,
    private val beta: Double = 20.0,
    private val dCutoff: Double = 1.0
) {
    private var xPrev = 0.0
    private var dxPrev = 0.0
    private var tPrev = -1.0

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * Math.PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    fun filter(x: Double, t: Double): Double {
        if (tPrev < 0) { tPrev = t; xPrev = x; return x }
        val dt = maxOf(t - tPrev, 1e-3)
        tPrev = t
        val dx = (x - xPrev) / dt
        val ad = alpha(dCutoff, dt)
        dxPrev = ad * dx + (1 - ad) * dxPrev
        val a = alpha(minCutoff + beta * abs(dxPrev), dt)
        xPrev = a * x + (1 - a) * xPrev
        return xPrev
    }

    fun reset() { tPrev = -1.0 }
}

class HandService : LifecycleService() {
    companion object {
        private const val USE_GPU = true
        private const val POINTER = 5          // 5 = buku jari telunjuk (stabil), 8 = ujung telunjuk
        private const val PINCH_ON = 0.28
        private const val PINCH_OFF = 0.42
        private const val PINCH_OPEN = 0.55
        private const val TAP_STILL = 0.30
        private const val TAP_COOLDOWN = 350L
        private const val SWIPE_PALMS = 1.0
        private const val SWIPE_SPEED = 3.0
        private const val SWIPE_WINDOW = 0.35
        private const val SWIPE_COOLDOWN = 450L
        private const val OPPOSITE_LOCK = 800L
    }

    private class Sample(
        val t: Double,
        val sx: Double,
        val sy: Double,
        val px: Double,
        val py: Double,
        val pose: Boolean
    )

    private lateinit var executor: ExecutorService
    private lateinit var nm: NotificationManager
    private var landmarker: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null

    private val fx = Array(21) { OneEuro() }
    private val fy = Array(21) { OneEuro() }
    private val fz = Array(21) { OneEuro() }
    private val hist = ArrayDeque<Sample>()

    private var visibleSince = 0L
    private var pinching = false
    private var pinchFrames = 0
    private var openFrames = 0
    private var tapReady = false
    private var lastTapAt = 0L
    private var lastSwipeAt = 0L
    private var lastDir = 0
    private var fistSince = 0L
    private var fistDone = false
    private var lastStatus = ""

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("hand", "HandTrack", NotificationManager.IMPORTANCE_LOW)
            )
        }
        ServiceCompat.startForeground(this, 1, notif("Siap"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        executor = Executors.newSingleThreadExecutor()
        landmarker = try {
            buildLandmarker(if (USE_GPU) Delegate.GPU else Delegate.CPU)
        } catch (e: Exception) {
            try {
                buildLandmarker(Delegate.CPU)
            } catch (e2: Exception) {
                status("Gagal load model")
                stopSelf()
                return
            }
        }
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        provider?.unbindAll()
        landmarker?.close()
        ControlService.instance?.hideCursor()
        if (::executor.isInitialized) executor.shutdown()
        super.onDestroy()
    }

    private fun notif(text: String): Notification =
        NotificationCompat.Builder(this, "hand")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("HandTrack aktif")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun status(t: String) {
        if (t != lastStatus) {
            lastStatus = t
            nm.notify(1, notif(t))
        }
    }

    private fun buildLandmarker(delegate: Delegate): HandLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(delegate)
            .build()
        val o = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.6f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { r, img -> onResult(r, img) }
            .setErrorListener { e -> status("Error: ${e.message}") }
            .build()
        return HandLandmarker.createFromOptions(this, o)
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            provider = p
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { img -> analyze(img) }
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(p: ImageProxy) {
        try {
            val lm = landmarker ?: return
            val raw = p.toBitmap()
            val m = Matrix().apply { postRotate(p.imageInfo.rotationDegrees.toFloat()) }
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            lm.detectAsync(BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis())
        } finally {
            p.close()
        }
    }

    private fun lost() {
        for (f in fx) f.reset()
        for (f in fy) f.reset()
        for (f in fz) f.reset()
        hist.clear()
        pinching = false
        pinchFrames = 0
        openFrames = 0
        tapReady = false
        fistSince = 0L
        fistDone = false
        visibleSince = 0L
        ControlService.instance?.hideCursor()
        status("Tangan tidak terlihat")
    }

    // seberapa jauh telapak bergerak dalam 0,2 detik terakhir (satuan: panjang telapak)
    private fun moved(palm: Double): Double {
        val last = hist.last()
        for (s in hist) {
            if (last.t - s.t <= 0.2) return hypot(last.px - s.px, last.py - s.py) / palm
        }
        return 0.0
    }

    // 1 = kanan, 2 = kiri, 3 = atas, 4 = bawah, 0 = tidak ada
    private fun detectSwipe(palm: Double): Int {
        val last = hist.last()
        var i = hist.size - 2
        while (i >= 0) {
            val s = hist[i]
            val dt = last.t - s.t
            if (dt > SWIPE_WINDOW || !s.pose) break
            if (dt >= 0.06) {
                val dx = (last.sx - s.sx) / palm
                val dy = (last.sy - s.sy) / palm
                val dist = hypot(dx, dy)
                if (dist >= SWIPE_PALMS && dist / dt >= SWIPE_SPEED) {
                    if (abs(dx) > abs(dy) * 2.0) return if (dx > 0) 1 else 2
                    if (abs(dy) > abs(dx) * 2.0) return if (dy < 0) 3 else 4
                }
            }
            i--
        }
        return 0
    }

    private fun onResult(r: HandLandmarkerResult, img: MPImage) {
        val hands = r.landmarks()
        if (hands.isEmpty()) { lost(); return }
        val now = SystemClock.uptimeMillis()
        val t = now / 1000.0
        val w = img.width.toDouble()
        val h = img.height.toDouble()
        val lm = hands[0]
        if (visibleSince == 0L) visibleSince = now

        val px = DoubleArray(21)
        val py = DoubleArray(21)
        val pz = DoubleArray(21)
        for (i in 0 until 21) {
            px[i] = fx[i].filter(1.0 - lm[i].x().toDouble(), t) * w
            py[i] = fy[i].filter(lm[i].y().toDouble(), t) * h
            pz[i] = fz[i].filter(lm[i].z().toDouble(), t) * w
        }

        fun d3(a: Int, b: Int): Double {
            val dx = px[a] - px[b]
            val dy = py[a] - py[b]
            val dz = pz[a] - pz[b]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun ang(a: Int, b: Int, c: Int): Double {
            val ux = px[a] - px[b]
            val uy = py[a] - py[b]
            val uz = pz[a] - pz[b]
            val vx = px[c] - px[b]
            val vy = py[c] - py[b]
            val vz = pz[c] - pz[b]
            val n = sqrt(ux * ux + uy * uy + uz * uz) * sqrt(vx * vx + vy * vy + vz * vz)
            if (n < 1e-6) return 180.0
            val cs = ((ux * vx + uy * vy + uz * vz) / n).coerceIn(-1.0, 1.0)
            return Math.toDegrees(acos(cs))
        }

        val palm = d3(0, 9)
        if (palm < 20.0) return

        // 0 = telunjuk, 1 = tengah, 2 = manis, 3 = kelingking
        val ext = BooleanArray(4)
        val curl = BooleanArray(4)
        for (f in 0 until 4) {
            val m = 5 + f * 4
            val a1 = ang(m, m + 1, m + 2)
            val a2 = ang(m + 1, m + 2, m + 3)
            ext[f] = a1 > 150.0 && a2 > 145.0 && d3(m + 3, 0) > d3(m + 1, 0)
            curl[f] = a1 < 130.0
        }
        val swipePose = ext[0] && ext[1] && !ext[2] && !ext[3]
        val fist = curl[0] && curl[1] && curl[2] && curl[3]
        val canPinch = !swipePose && !fist

        val ratio = d3(4, 8) / palm
        val was = pinching
        pinching = if (!canPinch) false else if (pinching) ratio < PINCH_OFF else ratio < PINCH_ON
        if (pinching && !was) {
            tapReady = openFrames >= 2
            openFrames = 0
            pinchFrames = 1
        } else if (pinching) {
            pinchFrames++
        } else {
            pinchFrames = 0
            if (ratio > PINCH_OPEN) openFrames++
        }

        val pcx = (px[0] + px[5] + px[9] + px[13] + px[17]) / 5.0
        val pcy = (py[0] + py[5] + py[9] + py[13] + py[17]) / 5.0
        hist.addLast(Sample(t, (px[8] + px[12]) / 2.0, (py[8] + py[12]) / 2.0, pcx, pcy, swipePose))
        while (hist.size > 1 && t - hist.first().t > 0.6) hist.removeFirst()

        val svc = ControlService.instance
        if (svc == null) { status("Aksesibilitas belum aktif"); return }
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()
        val cxs = (((px[POINTER] / w) - 0.12) / 0.76).coerceIn(0.0, 1.0).toFloat() * sw
        val cys = (((py[POINTER] / h) - 0.12) / 0.76).coerceIn(0.0, 1.0).toFloat() * sh
        svc.showCursor(cxs, cys, pinching)

        // Kepalan ditahan = Kembali
        if (fist) {
            if (fistSince == 0L) fistSince = now
            if (!fistDone && now - fistSince > 900) {
                fistDone = true
                svc.back()
                status("Kembali")
            }
        } else {
            fistSince = 0L
            fistDone = false
        }

        // Ketukan jempol + telunjuk = Tap
        if (pinchFrames == 2 && tapReady && now - lastTapAt > TAP_COOLDOWN && moved(palm) < TAP_STILL) {
            lastTapAt = now
            svc.tap(cxs, cys)
            status("Tap")
            return
        }

        // 2 jari (telunjuk + tengah) digeser cepat = Swipe
        if (swipePose && now - visibleSince > 400 && now - lastSwipeAt > SWIPE_COOLDOWN) {
            val dir = detectSwipe(palm)
            if (dir != 0) {
                val opp = (dir == 1 && lastDir == 2) || (dir == 2 && lastDir == 1) ||
                    (dir == 3 && lastDir == 4) || (dir == 4 && lastDir == 3)
                if (!(opp && now - lastSwipeAt < OPPOSITE_LOCK)) {
                    when (dir) {
                        1 -> { svc.swipe(sw * 0.2f, sh * 0.5f, sw * 0.8f, sh * 0.5f); status("Geser kanan") }
                        2 -> { svc.swipe(sw * 0.8f, sh * 0.5f, sw * 0.2f, sh * 0.5f); status("Geser kiri") }
                        3 -> { svc.swipe(sw * 0.5f, sh * 0.7f, sw * 0.5f, sh * 0.3f); status("Geser atas") }
                        else -> { svc.swipe(sw * 0.5f, sh * 0.3f, sw * 0.5f, sh * 0.7f); status("Geser bawah") }
                    }
                    lastSwipeAt = now
                    lastDir = dir
                    hist.clear()
                    return
                }
            }
        }

        if (now - lastSwipeAt > 1200 && now - lastTapAt > 1200) status("Tangan terdeteksi")
    }
}
