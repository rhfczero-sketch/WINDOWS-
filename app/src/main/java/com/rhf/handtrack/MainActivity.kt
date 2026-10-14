package com.rhf.handtrack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val ask = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r ->
        if (r[Manifest.permission.CAMERA] == true || cameraOk()) begin()
        else toast("Izin kamera ditolak")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun cameraOk() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun begin() {
        if (ControlService.instance == null) {
            toast("Aktifkan dulu: Aksesibilitas > HandTrack Kontrol")
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, HandService::class.java))
        toast("Aktif. Buka aplikasi lain lalu coba gerakan.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        box.addView(TextView(this).apply {
            text = "HandTrack Kontrol\n\n" +
                "Angkat 2 jari (telunjuk + tengah) lalu geser cepat = swipe\n" +
                "Ketuk jempol + telunjuk = tap (titik merah = posisi tap)\n" +
                "Kepalan ditahan 1 detik = kembali\n\n" +
                "Tahan pose 2 jari sebentar sebelum swipe."
            textSize = 16f
        })
        box.addView(Button(this).apply {
            text = "1. Aktifkan Aksesibilitas"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        box.addView(Button(this).apply {
            text = "2. Mulai kontrol"
            setOnClickListener {
                val need = mutableListOf(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
                ask.launch(need.toTypedArray())
            }
        })
        box.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { stopService(Intent(this@MainActivity, HandService::class.java)) }
        })
        setContentView(box)
    }
}
