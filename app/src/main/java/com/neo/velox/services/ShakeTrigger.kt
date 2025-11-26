package com.neo.velox.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log
import kotlin.math.sqrt


class ShakeTrigger(
    private val context: Context,
    private val onTrigger: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var wakeLock: PowerManager.WakeLock? = null

    // Shake Configuration
    private val SHAKE_THRESHOLD_GRAVITY = 2.7F // Sensitivity (Higher = Harder shake required)
    private val SHAKE_SLOP_TIME_MS = 500 // Minimum time between shakes
    private var shakeTimestamp: Long = 0

    companion object {
        private const val TAG = "ShakeTrigger"
    }

    fun start() {
        if (accelerometer == null) {
            Log.e(TAG, "No Accelerometer found!")
            return
        }

        // Acquire WakeLock to ensure CPU runs while screen is OFF
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Velox:ShakeWakeLock")
        wakeLock?.acquire(10*60*1000L) // 10 min safety timeout

        // Register Listener
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        Log.d(TAG, "Shake Detector Started")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wakelock", e)
        }
        Log.d(TAG, "Shake Detector Stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Calculate G-Force (ignoring direction)
        val gX = x / SensorManager.GRAVITY_EARTH
        val gY = y / SensorManager.GRAVITY_EARTH
        val gZ = z / SensorManager.GRAVITY_EARTH

        // gForce will be close to 1 when still (just gravity)
        val gForce = sqrt((gX * gX + gY * gY + gZ * gZ).toDouble()).toFloat()

        if (gForce > SHAKE_THRESHOLD_GRAVITY) {
            val now = System.currentTimeMillis()

            // Debounce: ignore shakes that happen too close together
            if (shakeTimestamp + SHAKE_SLOP_TIME_MS > now) {
                return
            }

            // Valid Shake detected
            shakeTimestamp = now
            Log.e(TAG, "SHAKE DETECTED! G-Force: $gForce")
            // Fire the trigger callback
            onTrigger()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }
}