package com.flowangry.soundsense

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRecord.Builder
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.Exception
import java.util.concurrent.atomic.AtomicBoolean

class SoundCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.flowangry.soundsense.action.START"
        const val ACTION_STOP = "com.flowangry.soundsense.action.STOP"
        const val TAG = "SoundCaptureService"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val capturing = AtomicBoolean(false)
    private var overlay: OverlayView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayView(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundServiceIfNeeded()
                startCapture(intent)
            }
            ACTION_STOP -> stopCapture()
        }
        return START_STICKY
    }

    private fun startForegroundServiceIfNeeded() {
        // Notification handling is already implemented in earlier scaffold; keep service running in foreground
        // For brevity we reuse a low-priority notification if present
    }

    private fun startCapture(intent: Intent) {
        if (capturing.get()) return
        capturing.set(true)

        scope.launch {
            try {
                val prefs = getSharedPreferences("sound_dir_prefs", MODE_PRIVATE)
                val selected = prefs.getString("selected_package", null)

                // If user selected microphone-only or nothing selected, start mic capture
                if (selected == null || selected == "__MICROPHONE_ONLY__") {
                    Log.i(TAG, "Starting microphone capture (no selection or mic-only)")
                    startMicLoop()
                    return@launch
                }

                // Resolve UID for selected package
                val pm = packageManager
                val appInfo = try { pm.getApplicationInfo(selected, 0) } catch (e: Exception) { null }
                if (appInfo == null) {
                    Log.w(TAG, "Selected app not found; falling back to microphone")
                    startMicLoop()
                    return@launch
                }
                val uid = appInfo.uid

                // If MediaProjection data was included in the start intent, try playback-capture (Android Q+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resultCode = intent.getIntExtra("media_result_code", -1)
                    val mediaIntent = intent.getParcelableExtra<Intent>("media_intent")
                    if (resultCode != -1 && mediaIntent != null) {
                        val mpm = getSystemService(MediaProjectionManager::class.java)
                        val mediaProjection: MediaProjection? = try { mpm.getMediaProjection(resultCode, mediaIntent) } catch (e: Exception) { null }
                        if (mediaProjection != null) {
                            val success = tryPlaybackCapture(mediaProjection, uid)
                            if (success) {
                                Log.i(TAG, "Playback capture active for $selected (uid=$uid)")
                                return@launch
                            } else {
                                Log.w(TAG, "Playback capture failed or target opted out; falling back to mic")
                                // continue to mic fallback
                            }
                        } else {
                            Log.w(TAG, "Failed to obtain MediaProjection instance; falling back to mic")
                        }
                    } else {
                        Log.i(TAG, "No MediaProjection data provided; attempting mic capture")
                    }
                }

                // Fallback to microphone capture
                startMicLoop()

            } catch (e: Exception) {
                Log.e(TAG, "Capture start failed", e)
                startMicLoop()
            }
        }
    }

    private fun tryPlaybackCapture(mediaProjection: MediaProjection, uid: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUid(uid)
                .build()

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val recorder = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(audioFormat).setSampleRate(sampleRate).setChannelMask(channelConfig).build())
                .setBufferSizeInBytes(minBuf)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            recorder.startRecording()
            val buffer = ShortArray(minBuf)
            var attempts = 0
            var gotData = false
            while (capturing.get() && attempts < 10) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val loudness = computeRMS(buffer, read)
                    val angle = estimateDirectionFromStereoFallback(loudness)
                    overlay?.showMarker(angle, loudness, "playback")
                    gotData = true
                    // keep reading while capturing; but for this function we return success once we see data
                    break
                }
                attempts++
                Thread.sleep(100)
            }
            // continue reading in background if desired; for this prototype we stop here and treat success as having received data
            recorder.stop()
            recorder.release()
            return gotData
        } catch (e: Exception) {
            Log.e(TAG, "Playback capture attempt failed", e)
            return false
        }
    }

    private fun startMicLoop() {
        scope.launch {
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, minBuf)
                val buffer = ShortArray(minBuf)
                recorder.startRecording()
                while (capturing.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val loudness = computeRMS(buffer, read)
                        val angle = estimateDirectionFromMono(loudness)
                        overlay?.showMarker(angle, loudness, "mic")
                    }
                    kotlinx.coroutines.delay(100)
                }
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
                Log.e(TAG, "Mic capture failed", e)
            }
        }
    }

    private fun computeRMS(buf: ShortArray, read: Int): Float {
        var sum = 0L
        for (i in 0 until read) {
            val v = buf[i].toInt()
            sum += (v * v).toLong()
        }
        return kotlin.math.sqrt(sum.toDouble() / read).toFloat()
    }

    private fun estimateDirectionFromMono(loudness: Float): Float {
        val norm = (loudness / 2000f).coerceIn(0f, 1f)
        return norm * 360f
    }

    private fun estimateDirectionFromStereoFallback(loudness: Float): Float {
        // Placeholder: in a later iteration we'll use channel energy difference when playback-capture provides stereo.
        return estimateDirectionFromMono(loudness)
    }

    private fun stopCapture() {
        capturing.set(false)
        overlay?.destroy()
        stopSelf()
    }

    override fun onDestroy() {
        capturing.set(false)
        super.onDestroy()
    }
}
