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
import kotlin.math.hypot

class OneEuro(
    private val minCutoff: Double = 1.2,
    private val beta: Double = 8.0,
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
        private const val PINCH_ON = 0.30
        private const val PINCH_OFF = 0.45
        private const val SWIPE_X = 0.22
        private const val SWIPE_Y = 0.18
        private const val STILL = 0.05
        private const val COOLDOWN = 800L
    }

    private lateinit var executor: ExecutorService
    private lateinit var nm: NotificationManager
    private var landmarker: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null

    private val fx = Array(21) { OneEuro() }
    private val fy = Array(21) { OneEuro() }
    private val hist = ArrayDeque<DoubleArray>()

    private var pinching = false
    private var pinchFrames = 0
    private var armed = false
    private var lastAction = 0L
    private var lastTapAt = 0L
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
            buildLandmarker()
        } catch (e: Exception) {
            status("Gagal load model")
            stopSelf()
            return
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

    private fun buildLandmarker(): HandLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(Delegate.CPU)
            .build()
        val o = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.7f)
            .setMinHandPresenceConfidence(0.7f)
            .setMinTrackingConfidence(0.7f)
            .setResultListener { r, img -> onResult(r, img) }
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
        hist.clear()
        pinching = false
        pinchFrames = 0
        armed = false
        fistSince = 0L
        fistDone = false
        status("Tangan tidak terlihat")
    }

    private fun isStill(t: Double): Boolean {
        val recent = hist.filter { t - it[0] <= 0.25 }
        if (recent.size < 3 || recent.last()[0] - recent.first()[0] < 0.18) return false
        val xs = recent.map { it[1] }
        val ys = recent.map { it[2] }
        return (xs.max() - xs.min()) < STILL && (ys.max() - ys.min()) < STILL
    }

    private fun onResult(r: HandLandmarkerResult, img: MPImage) {
        val hands = r.landmarks()
        if (hands.isEmpty()) { lost(); return }
        val now = SystemClock.uptimeMillis()
        val t = now / 1000.0
        val w = img.width.toDouble()
        val h = img.height.toDouble()
        val lm = hands[0]
        val x = DoubleArray(21)
        val y = DoubleArray(21)
        for (i in 0 until 21) {
            x[i] = fx[i].filter(1.0 - lm[i].x().toDouble(), t)
            y[i] = fy[i].filter(lm[i].y().toDouble(), t)
        }
        fun d(a: Int, b: Int) = hypot((x[a] - x[b]) * w, (y[a] - y[b]) * h)
        fun ext(tip: Int, pip: Int) = d(tip, 0) > d(pip, 0) * 1.08

        val palm = d(0, 9)
        if (palm < 1.0) return
        val four = intArrayOf(8, 12, 16, 20).count { ext(it, it - 2) }
        val others = intArrayOf(12, 16, 20).count { ext(it, it - 2) }
        val cx = (x[0] + x[5] + x[9] + x[13] + x[17]) / 5.0
        val cy = (y[0] + y[5] + y[9] + y[13] + y[17]) / 5.0

        hist.addLast(doubleArrayOf(t, cx, cy))
        while (hist.size > 1 && t - hist.first()[0] > 0.4) hist.removeFirst()

        val ratio = d(4, 8) / palm
        val was = pinching
        pinching = if (others < 1) false else if (pinching) ratio < PINCH_OFF else ratio < PINCH_ON
        pinchFrames = if (pinching && !was) 1 else if (pinching) pinchFrames + 1 else 0

        val svc = ControlService.instance
        if (svc == null) { status("Aksesibilitas belum aktif"); return }
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()

        // Kepalan ditahan = Kembali
        if (four == 0) {
            if (fistSince == 0L) fistSince = now
            if (!fistDone && now - fistSince > 900) {
                fistDone = true
                lastAction = now
                svc.back()
                status("Kembali")
            }
        } else {
            fistSince = 0L
            fistDone = false
        }

        val cool = now - lastAction > COOLDOWN
        if (!armed && cool && isStill(t)) armed = true

        // Pinch = Tap
        if (pinchFrames == 2 && now - lastTapAt > 450) {
            lastTapAt = now
            val px = (((cx - 0.15) / 0.7).coerceIn(0.0, 1.0) * sw).toFloat()
            val py = (((cy - 0.15) / 0.7).coerceIn(0.0, 1.0) * sh).toFloat()
            svc.tap(px, py)
            status("Tap")
            return
        }

        // Telapak terbuka digeser cepat = Swipe
        if (armed && cool && four >= 3 && !pinching && hist.size >= 3) {
            val o = hist.first()
            val dt = t - o[0]
            val dx = cx - o[1]
            val dy = cy - o[2]
            if (dt in 0.08..0.4) {
                val horiz = abs(dx) > SWIPE_X && abs(dx) > abs(dy) * 1.8
                val vert = abs(dy) > SWIPE_Y && abs(dy) > abs(dx) * 1.8
                if (horiz) {
                    if (dx > 0) { svc.swipe(sw * 0.2f, sh * 0.5f, sw * 0.8f, sh * 0.5f); status("Geser kanan") }
                    else { svc.swipe(sw * 0.8f, sh * 0.5f, sw * 0.2f, sh * 0.5f); status("Geser kiri") }
                } else if (vert) {
                    if (dy < 0) { svc.swipe(sw * 0.5f, sh * 0.7f, sw * 0.5f, sh * 0.3f); status("Geser atas") }
                    else { svc.swipe(sw * 0.5f, sh * 0.3f, sw * 0.5f, sh * 0.7f); status("Geser bawah") }
                }
                if (horiz || vert) {
                    lastAction = now
                    armed = false
                    hist.clear()
                    return
                }
            }
        }

        if (now - lastAction > 1200 && now - lastTapAt > 1200) status("Tangan terdeteksi")
    }
}
