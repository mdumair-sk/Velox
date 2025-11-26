package com.neo.velox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.neo.velox.services.CoreService

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnTestVoice: Button
    private lateinit var btnEnableAccessibility: Button
    private lateinit var tvAccessibilityStatus: TextView

    private val requiredPermissions by lazy {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBatteryOptimization()
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnTestVoice = findViewById(R.id.btnTestVoice)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnStart.setOnClickListener { startCoreService() }
        btnStop.setOnClickListener { stopCoreService() }
        btnTestVoice.setOnClickListener { triggerVoiceCommand() }
        btnEnableAccessibility.setOnClickListener { openAccessibilitySettings() }

        checkRuntimePermissions()
        updateAccessibilityStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        if (isEnabled) {
            tvAccessibilityStatus.text = "✓ Volume Button Trigger: Active"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            btnEnableAccessibility.isEnabled = false
            btnEnableAccessibility.alpha = 0.5f
        } else {
            tvAccessibilityStatus.text = "✗ Volume Button Trigger: Inactive"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            btnEnableAccessibility.isEnabled = true
            btnEnableAccessibility.alpha = 1.0f
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "${packageName}/.services.VolumeButtonAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":")
            .any { it.equals(expectedComponentName, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Enable 'Velox' in Accessibility settings to use volume button trigger",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerVoiceCommand() {
        val intent = Intent(this, CoreService::class.java).apply {
            action = CoreService.ACTION_TRIGGER_VOICE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show()
    }

    private fun checkRuntimePermissions() {
        if (!areRuntimePermissionsGranted()) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            checkBatteryOptimization()
        }
    }

    private fun areRuntimePermissionsGranted(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }
    }

    private fun startCoreService() {
        if (!areRuntimePermissionsGranted()) {
            Toast.makeText(this, "Permissions missing", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CoreService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        tvStatus.text = "Service Active"
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
    }

    private fun stopCoreService() {
        val intent = Intent(this, CoreService::class.java)
        stopService(intent)
        tvStatus.text = "Service Inactive"
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
    }
}