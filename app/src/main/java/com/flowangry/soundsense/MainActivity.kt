package com.flowangry.soundsense

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRecord.Builder
import android.media.AudioPlaybackCaptureConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    private val requestRecordPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // no-op for now
    }

    private var pendingAction: String? = null

    private val mediaProjLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            Toast.makeText(this, "MediaProjection permission denied or failed", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        handleMediaProjectionResult(result.resultCode, result.data!!)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startBtn = findViewById<MaterialButton>(R.id.btn_start)
        val stopBtn = findViewById<MaterialButton>(R.id.btn_stop)
        val overlayBtn = findViewById<MaterialButton>(R.id.btn_overlay_settings)
        val pickAppBtn = findViewById<MaterialButton>(R.id.btn_pick_app)
        val testCaptureBtn = findViewById<MaterialButton>(R.id.btn_test_capture)

        startBtn.setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            val prefs = getSharedPreferences("sound_dir_prefs", MODE_PRIVATE)
            val selected = prefs.getString("selected_package", null)
            if (selected == null || selected == "__MICROPHONE_ONLY__") {
                // Start service immediately using microphone
                val svc = Intent(this, SoundCaptureService::class.java)
                svc.action = SoundCaptureService.ACTION_START
                startService(svc)
                return@setOnClickListener
            }

            // Selected a package - attempt MediaProjection flow on Android Q+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                pendingAction = "start_service"
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val intent = mpm.createScreenCaptureIntent()
                mediaProjLauncher.launch(intent)
            } else {
                // older devices - fallback to mic
                val svc = Intent(this, SoundCaptureService::class.java)
                svc.action = SoundCaptureService.ACTION_START
                startService(svc)
            }
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

        testCaptureBtn.setOnClickListener {
            // start MediaProjection permission flow to test playback-capture for the selected app
            val prefs = getSharedPreferences("sound_dir_prefs", MODE_PRIVATE)
            val selected = prefs.getString("selected_package", null)
            if (selected == null) {
                Toast.makeText(this, "No app selected. Choose an app or use Microphone-only mode.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selected == "__MICROPHONE_ONLY__") {
                Toast.makeText(this, "Microphone-only mode selected — playback capture not applicable.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                pendingAction = "test_capture"
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val intent = mpm.createScreenCaptureIntent()
                mediaProjLauncher.launch(intent)
            } else {
                Toast.makeText(this, "Playback capture requires Android 10+", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleMediaProjectionResult(resultCode: Int, data: Intent) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val prefs = getSharedPreferences("sound_dir_prefs", MODE_PRIVATE)
            val selected = prefs.getString("selected_package", null) ?: run {
                Toast.makeText(this, "No app selected", Toast.LENGTH_SHORT).show()
                return
            }
            if (selected == "__MICROPHONE_ONLY__") {
                Toast.makeText(this, "Microphone-only mode selected", Toast.LENGTH_SHORT).show()
                return
            }

            val pm = packageManager
            val appInfo = pm.getApplicationInfo(selected, 0)
            val uid = appInfo.uid

            // If this projection result was for starting the service, pass the projection intent to the service
            if (pendingAction == "start_service") {
                val svc = Intent(this, SoundCaptureService::class.java)
                svc.action = SoundCaptureService.ACTION_START
                svc.putExtra("media_result_code", resultCode)
                svc.putExtra("media_intent", data)
                startService(svc)
                pendingAction = null
                return
            }

            // Otherwise this was a test capture request. Try to perform a quick read to ensure playback capture works
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mediaProjection = mpm.getMediaProjection(resultCode, data)

            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUid(uid)
                .build()

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val rec = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(audioFormat).setSampleRate(sampleRate).setChannelMask(channelConfig).build())
                .setBufferSizeInBytes(minBuf)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            rec.startRecording()
            val buf = ShortArray(minBuf)
            var success = false
            var attempts = 0
            while (attempts < 5) {
                val r = rec.read(buf, 0, buf.size)
                if (r > 0) {
                    success = true
                    break
                }
                attempts++
                Thread.sleep(150)
            }
            rec.stop()
            rec.release()

            if (success) {
                Toast.makeText(this, "Playback capture succeeded for $selected", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Playback capture failed or target app opted out; service will fallback to microphone.", Toast.LENGTH_LONG).show()
            }

            mediaProjection.stop()
            pendingAction = null
        } catch (e: Exception) {
            Log.e("MainActivity", "Playback capture test failed", e)
            Toast.makeText(this, "Playback capture test failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
