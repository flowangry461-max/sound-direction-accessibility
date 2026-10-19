package com.flowangry.soundsense

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    private val requestRecordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // no-op for now
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startBtn = findViewById<MaterialButton>(R.id.btn_start)
        val stopBtn = findViewById<MaterialButton>(R.id.btn_stop)
        val overlayBtn = findViewById<MaterialButton>(R.id.btn_overlay_settings)
        val pickAppBtn = findViewById<MaterialButton>(R.id.btn_pick_app)

        startBtn.setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            val svc = Intent(this, SoundCaptureService::class.java)
            svc.action = SoundCaptureService.ACTION_START
            startService(svc)
        }

        stopBtn.setOnClickListener {
            val svc = Intent(this, SoundCaptureService::class.java)
            svc.action = SoundCaptureService.ACTION_STOP
            startService(svc)
        }

        overlayBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                }
            }
        }

        pickAppBtn.setOnClickListener {
            val intent = Intent(this, AppPickerActivity::class.java)
            startActivity(intent)
        }
    }
}
