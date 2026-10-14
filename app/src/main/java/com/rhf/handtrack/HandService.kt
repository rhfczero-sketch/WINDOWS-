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
        private const val MARGIN = 0.15          // area aktif kamera (makin besar = makin sensitif)
        private const val DEAD_PX = 2.5          // getaran di bawah ini diabaikan
        private const val STABLE_FRAMES = 4      // pose harus stabil N frame sebelum dieksekusi
        private const val TAP_COOLDOWN = 400L
        private const val REPEAT_DELAY = 700L    // tahan jari = geser berulang
        private const val REPEAT_EVERY = 450L
        private const val BACK_HOLD = 1000L      // telapak terbuka ditahan = Kembali
    }

    private enum class Pose { NONE, FIST, THUMB, INDEX, MIDDLE, RING, PINKY, OPEN }

    private class Pos(val t: Double, val x: Float, val y: Float)

    private lateinit var executor: ExecutorService
    private lateinit var nm: NotificationManager
    private var landmarker: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null

    private val fx = Array(21) { OneEuro() }
    private val fy = Array(21) { OneEuro() }
    private val fz = Array(21) { OneEuro() }
    private val sxF = OneEuro(0.9, 0.015, 1.0)
    private val syF = OneEuro(0.9, 0.015, 1.0)
    private val posHist = ArrayDeque<Pos>()

    private var curX = -1f
    private var curY = -1f
    private var lastFistT = 0.0
    private var candidate = Pose.NONE
    private var candFrames = 0
    private var active = Pose.NONE
    private var poseStartAt = 0L
    private var lastFireAt = 0L
    private var lastTapAt = 0L
    private var repeatCount = 0
    private var backDone = false
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
            .setMinHandDetectionConfidence(0.5f)
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
        sxF.reset()
        syF.reset()
        posHist.clear()
        candidate = Pose.NONE
        candFrames = 0
        active = Pose.NONE
        backDone = false
        ControlService.instance?.hideCursor()
        status("Tangan tidak terlihat")
    }

    // posisi kursor sedikit sebelum jari mulai terbuka (supaya tap tepat di titik yang dibidik)
    private fun anchorPos(): Pos? {
        if (posHist.isEmpty()) return null
        val target = lastFistT - 0.12
        var best = posHist.first()
        for (p in posHist) {
            if (p.t <= target) best = p else break
        }
        return best
    }

    private fun fire(p: Pose, svc: ControlService, now: Long, sw: Float, sh: Float) {
        when (p) {
            Pose.THUMB -> if (now - lastTapAt > TAP_COOLDOWN) {
                lastTapAt = now
                svc.tap(curX, curY)
                status("Tap")
            }
            Pose.INDEX -> { svc.swipe(sw * 0.5f, sh * 0.7f, sw * 0.5f, sh * 0.3f); status("Geser atas") }
            Pose.MIDDLE -> { svc.swipe(sw * 0.5f, sh * 0.3f, sw * 0.5f, sh * 0.7f); status("Geser bawah") }
            Pose.RING -> { svc.swipe(sw * 0.2f, sh * 0.5f, sw * 0.8f, sh * 0.5f); status("Geser kanan") }
            Pose.PINKY -> { svc.swipe(sw * 0.8f, sh * 0.5f, sw * 0.2f, sh * 0.5f); status("Geser kiri") }
            else -> {}
        }
        lastFireAt = now
    }

    private fun onResult(r: HandLandmarkerResult, img: MPImage) {
        val hands = r.landmarks()
        if (hands.isEmpty()) { lost(); return }
        val now = SystemClock.uptimeMillis()
        val t = now / 1000.0
        val w = img.width.toDouble()
        val h = img.height.toDouble()
        val lm = hands[0]

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

        // ext[0..3] = telunjuk, tengah, manis, kelingking; thumb = jempol
        val ext = BooleanArray(4)
        for (f in 0 until 4) {
            val m = 5 + f * 4
            val a1 = ang(m, m + 1, m + 2)
            val a2 = ang(m + 1, m + 2, m + 3)
            ext[f] = a1 > 150.0 && a2 > 145.0 && d3(m + 3, 0) > d3(m + 1, 0)
        }
        val thumb = ang(2, 3, 4) > 145.0 && d3(4, 5) / palm > 0.8
        val count = ext.count { it } + (if (thumb) 1 else 0)
        val pose = when {
            count == 0 -> Pose.FIST
            count == 5 -> Pose.OPEN
            count == 1 -> when {
                thumb -> Pose.THUMB
                ext[0] -> Pose.INDEX
                ext[1] -> Pose.MIDDLE
                ext[2] -> Pose.RING
                else -> Pose.PINKY
            }
            else -> Pose.NONE
        }

        val svc = ControlService.instance
        if (svc == null) { status("Aksesibilitas belum aktif"); return }
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()
        if (curX < 0f) { curX = sw / 2f; curY = sh / 2f }

        // Kepalan = gerakkan kursor (titik acuan: rata-rata 4 buku jari, paling stabil)
        if (pose == Pose.FIST) {
            val ax = (px[5] + px[9] + px[13] + px[17]) / 4.0
            val ay = (py[5] + py[9] + py[13] + py[17]) / 4.0
            val span = 1.0 - 2.0 * MARGIN
            val rx = (((ax / w) - MARGIN) / span).coerceIn(0.0, 1.0) * sw
            val ry = (((ay / h) - MARGIN) / span).coerceIn(0.0, 1.0) * sh
            val nx = sxF.filter(rx, t)
            val ny = syF.filter(ry, t)
            if (hypot(nx - curX, ny - curY) >= DEAD_PX) {
                curX = nx.toFloat()
                curY = ny.toFloat()
            }
            lastFistT = t
            posHist.addLast(Pos(t, curX, curY))
            while (posHist.size > 1 && t - posHist.first().t > 0.6) posHist.removeFirst()
        }

        // pose harus stabil beberapa frame sebelum dianggap perintah
        if (pose == candidate) candFrames++ else { candidate = pose; candFrames = 1 }
        if (candFrames >= STABLE_FRAMES && candidate != active) {
            active = candidate
            poseStartAt = now
            repeatCount = 0
            backDone = false
            if (active == Pose.THUMB || active == Pose.INDEX || active == Pose.MIDDLE ||
                active == Pose.RING || active == Pose.PINKY) {
                anchorPos()?.let { curX = it.x; curY = it.y }
                fire(active, svc, now, sw, sh)
            }
        } else if (candidate == active) {
            // tahan jari geser = geser berulang
            if (active == Pose.INDEX || active == Pose.MIDDLE || active == Pose.RING || active == Pose.PINKY) {
                val gap = if (repeatCount == 0) REPEAT_DELAY else REPEAT_EVERY
                if (now - lastFireAt >= gap) {
                    repeatCount++
                    fire(active, svc, now, sw, sh)
                }
            }
            // telapak terbuka ditahan = Kembali
            if (active == Pose.OPEN && !backDone && now - poseStartAt >= BACK_HOLD) {
                backDone = true
                svc.back()
                status("Kembali")
            }
        }

        svc.showCursor(curX, curY, pose != Pose.FIST)

        if (now - lastFireAt > 1200 && now - lastTapAt > 1200 && !backDone) {
            status(if (pose == Pose.FIST) "Kursor" else "Tangan terdeteksi")
        }
    }
}
