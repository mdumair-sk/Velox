package com.neo.velox.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat

class CallHandler(
    private val context: Context,
    private val onIncomingCall: (String) -> Unit
) {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var isListening = false

    companion object {
        private const val TAG = "CallHandler"
    }

    private val phoneStateListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                // Ensure we have permissions before processing
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    val name = if (!phoneNumber.isNullOrEmpty()) getContactName(phoneNumber) else "Unknown"
                    Log.d(TAG, "Incoming call detected from: $name")
                    onIncomingCall(name)
                }
            }
        }
    }

    fun start() {
        if (!isListening) {
            try {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
                isListening = true
                Log.d(TAG, "Call Handler started")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting Call Handler", e)
            }
        }
    }

    fun stop() {
        if (isListening) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            isListening = false
        }
    }

    @SuppressLint("MissingPermission")
    fun answerCall() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    telecomManager.acceptRingingCall()
                } else {
                    // Fallback for older APIs if necessary, though acceptRingingCall exists since API 26
                    telecomManager.acceptRingingCall()
                }
                Log.d(TAG, "Call Answered")

                // Smart Speakerphone Logic
                checkAndEnableSpeaker()
            } catch (e: Exception) {
                Log.e(TAG, "Error answering call", e)
            }
        } else {
            Log.e(TAG, "Missing ANSWER_PHONE_CALLS permission")
        }
    }

    @SuppressLint("MissingPermission")
    fun rejectCall() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    telecomManager.endCall()
                } else {
                    // Note: endCall() was added in API 28 (Pie).
                    // For older versions, this part of the API is hidden/restricted.
                    // Velox targets 34/26, so we assume functionality for modern phones.
                    Log.w(TAG, "Rejecting calls requires API 28+")
                }
                Log.d(TAG, "Call Rejected")
            } catch (e: Exception) {
                Log.e(TAG, "Error rejecting call", e)
            }
        }
    }

    private fun checkAndEnableSpeaker() {
        // Wait 500ms for the call to connect fully
        Handler(Looper.getMainLooper()).postDelayed({
            val isHeadsetConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                devices.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
            }

            if (!isHeadsetConnected) {
                Log.d(TAG, "No headset detected. Enabling Speakerphone.")
                audioManager.isSpeakerphoneOn = true
            } else {
                Log.d(TAG, "Headset detected. Keeping normal audio.")
            }
        }, 500)
    }

    @SuppressLint("Range")
    private fun getContactName(phoneNumber: String): String {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val cursor: Cursor? = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)

        var contactName = phoneNumber // Default to number if name not found
        cursor?.use {
            if (it.moveToFirst()) {
                contactName = it.getString(it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME))
            }
        }
        return contactName
    }
}