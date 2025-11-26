package com.neo.velox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

/**
 * Optional diagnostic activity to test volume button detection
 * Add this to test if hardware buttons are being intercepted properly
 * 
 * To use: Create a button in MainActivity that opens this activity
 */
class DiagnosticActivity : AppCompatActivity() {
    
    private lateinit var tvLog: TextView
    private val logs = mutableListOf<String>()
    private val maxLogs = 50
    
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> addLog("📴 SCREEN OFF")
                Intent.ACTION_SCREEN_ON -> addLog("📱 SCREEN ON")
                Intent.ACTION_USER_PRESENT -> addLog("🔓 USER PRESENT")
            }
        }
    }
    
    companion object {
        private const val TAG = "DiagnosticActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        tvLog = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF000000.toInt())
        }
        setContentView(tvLog)
        
        addLog("🔬 Diagnostic Mode Started")
        addLog("Press volume buttons to test detection")
        addLog("---")
        
        // Register screen state receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        addLog("⬇️ DOWN: keyCode=$keyCode, repeat=${event?.repeatCount ?: 0}")
        
        // Don't call super to see if we can intercept
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP -> {
                addLog("   → Volume button (THIS ACTIVITY)")
                true // Try to consume it
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
    
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        addLog("⬆️ UP: keyCode=$keyCode")
        
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP -> {
                addLog("   → Volume button released")
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }
    
    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val logEntry = "[$timestamp] $message"
        
        Log.d(TAG, logEntry)
        
        logs.add(0, logEntry)
        if (logs.size > maxLogs) {
            logs.removeAt(maxLogs)
        }
        
        runOnUiThread {
            tvLog.text = logs.joinToString("\n")
        }
    }
}