# Sound Direction - Accessibility app

This repository contains a prototype Android app (Kotlin) that captures audio (microphone or, on Android 10+, AudioPlaybackCapture) and displays a simple overlay with directional hints.

Key features in this scaffold
- Foreground service that captures microphone audio (AudioRecord). Playback capture hooks will be added in follow-up commits.
- Simple overlay view that draws an arrow indicating a detected direction (best-effort; mono audio currently maps loudness to a demo angle).
- Basic UI to start/stop the service and request overlay permission.

Important notes
- minSdkVersion is 29 to support Audio Playback Capture APIs on modern Android 10+ devices.
- The current prototype uses microphone input only as a fallback. Playback capture integration (MediaProjection / AudioPlaybackCapture) will be implemented next.
- The TFLite audio model placeholder is set (tensorflow-lite dependency added). You can replace or add a model under app/src/main/assets/ and wire it into the classifier logic in the service.

Building APK (unsigned debug)
- The included GitHub Actions workflow will attempt to install Android command line tools and Gradle, generate a Gradle wrapper, and build the debug APK. The workflow produces an unsigned debug APK artifact.

Permissions
- RECORD_AUDIO: required to capture microphone audio.
- FOREGROUND_SERVICE: to run long-running capture in a foreground service.
- SYSTEM_ALERT_WINDOW: overlay permission must be granted by the user in system settings.

Next steps I will implement after feedback
- Add AudioPlaybackCapture-based capture for Android 10+ (best-effort when apps allow it).
- Integrate a TFLite classifier (YAMNet) and mapping of classes to events (gunshot, footstep examples).
- Add training sample recorder/exporter to collect labeled audio for improving the model.

Usage
1. Open the app and grant RECORD_AUDIO permission when prompted.
2. Grant overlay permission via the Overlay Settings button.
3. Press Start Capture to run the foreground service and see overlay markers when sound is detected.
