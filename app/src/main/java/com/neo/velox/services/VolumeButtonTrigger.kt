package com.neo.velox.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Service to intercept volume button long-press
 *
 * ENHANCED FOR SCREEN-OFF RELIABILITY:
 * - WakeLock to ensure CPU stays awake
 * - Enhanced logging for debugging
 * - Multiple safeguards for event detection
 * - Strict event handling
 */
class VolumeButtonTrigger : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val vibrator by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
    }

    // WakeLock to ensure we receive events when screen is off
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private var isHoldingButton = false
    private var pressStartTime = 0L
    private val LONG_PRESS_THRESHOLD = 700L // 700ms

    private var longPressRunnable: Runnable? = null
    private var isLongPress = false
    private var initialVolume = -1
    private var volumeRestoreScheduled = false

    // Track consecutive events to detect missed inputs
    private var lastEventTime = 0L
    private var consecutiveDownEvents = 0

    companion object {
        private const val TAG = "VolumeButtonService"
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyEvent(event)
        }

        // Log ALL events for debugging
        val now = System.currentTimeMillis()
        val timeSinceLastEvent = now - lastEventTime
        lastEventTime = now

        Log.d(TAG, "KeyEvent: action=${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}, " +
                "repeat=${event.repeatCount}, timeSince=${timeSinceLastEvent}ms, " +
                "screenOn=${isScreenOn()}, holding=$isHoldingButton")

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Acquire wake lock on first press
                ensureWakeLock()

                if (!isHoldingButton) {
                    // Initial press - reset state
                    consecutiveDownEvents = 1
                    isHoldingButton = true
                    isLongPress = false
                    pressStartTime = now
                    volumeRestoreScheduled = false

                    // Save current volume BEFORE Android changes it
                    initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                    Log.d(TAG, "🎯 Button pressed START (initial volume: $initialVolume)")

                    // Schedule long press detection
                    longPressRunnable = Runnable {
                        if (isHoldingButton) {
                            Log.d(TAG, "✅ LONG PRESS DETECTED!")
                            isLongPress = true

                            // Give haptic feedback
                            vibrateShortPulse()

                            scheduleVolumeRestore()
                            triggerVoiceAssistant()
                        }
                    }
                    handler.postDelayed(longPressRunnable!!, LONG_PRESS_THRESHOLD)
                } else {
                    // Repeated DOWN event during hold
                    consecutiveDownEvents++
                    Log.d(TAG, "🔄 Repeated DOWN event #$consecutiveDownEvents")
                }

                // CRITICAL: Consume ALL down events to prevent volume change
                return true
            }

            KeyEvent.ACTION_UP -> {
                isHoldingButton = false
                longPressRunnable?.let { handler.removeCallbacks(it) }

                val duration = now - pressStartTime

                Log.d(TAG, "🔓 Button released (duration=${duration}ms, downEvents=$consecutiveDownEvents, longPress=$isLongPress)")

                if (isLongPress) {
                    // Long press completed
                    Log.d(TAG, "✅ Long press COMPLETED - voice triggered")
                    scheduleVolumeRestore()

                    // Reset after a delay
                    handler.postDelayed({
                        isLongPress = false
                        initialVolume = -1
                        consecutiveDownEvents = 0
                        releaseWakeLock()
                    }, 200)

                    return true // Consume UP event
                } else {
                    // Short press - manually decrease volume by 1 step
                    Log.d(TAG, "⏬ Short press (${duration}ms) - decreasing volume manually")
                    decreaseVolumeOnce()
                    initialVolume = -1
                    consecutiveDownEvents = 0
                    releaseWakeLock()
                    return true // Still consume the event since we handled it
                }
            }
        }

        return false
    }

    private fun ensureWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "Velox:VolumeButtonWakeLock"
            )
        }

        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10000) // 10 second timeout for safety
            Log.d(TAG, "🔋 WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "🔋 WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wakelock", e)
        }
    }

    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }
    }

    private fun decreaseVolumeOnce() {
        try {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            if (currentVolume > 0) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    currentVolume - 1,
                    AudioManager.FLAG_SHOW_UI // Show volume UI
                )
                Log.d(TAG, "Volume decreased: $currentVolume -> ${currentVolume - 1}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrease volume", e)
        }
    }

    private fun scheduleVolumeRestore() {
        if (volumeRestoreScheduled) return
        volumeRestoreScheduled = true

        // Restore volume after a short delay to ensure all events are processed
        handler.post {
            restoreVolume()
        }

        // Also schedule a backup restore
        handler.postDelayed({
            restoreVolume()
        }, 100)
    }

    private fun restoreVolume() {
        if (initialVolume < 0) return

        try {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            if (currentVolume != initialVolume) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    initialVolume,
                    0 // No UI flags during restore
                )
                Log.d(TAG, "Volume restored: $currentVolume -> $initialVolume")
            } else {
                Log.d(TAG, "Volume already correct: $initialVolume")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore volume", e)
        }
    }

    private fun triggerVoiceAssistant() {
        val intent = Intent(this, CoreService::class.java).apply {
            action = CoreService.ACTION_TRIGGER_VOICE
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "Voice assistant triggered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger voice assistant", e)
        }
    }

    private fun vibrateShortPulse() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val vibrationEffect = android.os.VibrationEffect.createOneShot(
                    50, // 50ms
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                )
                vibrator.vibrate(vibrationEffect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
            Log.d(TAG, "Haptic feedback given")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Log accessibility events for debugging
        event?.let {
            if (it.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                Log.v(TAG, "AccessibilityEvent: ${it.eventType}")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ Volume Button Service CONNECTED")
        Log.d(TAG, "📋 Configuration: Long press = ${LONG_PRESS_THRESHOLD}ms")
        Log.d(TAG, "⚙️ Flags: ${serviceInfo.flags}")
        Log.d(TAG, "🎯 Event types: ${serviceInfo.eventTypes}")

        // Verify configuration
        val canFilterKeys = (serviceInfo.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
        Log.d(TAG, "🔑 Can filter key events: $canFilterKeys")

        if (!canFilterKeys) {
            Log.e(TAG, "⚠️ WARNING: Key event filtering not enabled!")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        longPressRunnable?.let { handler.removeCallbacks(it) }
        releaseWakeLock()
        Log.d(TAG, "❌ Volume Button Service DESTROYED")
    }
}