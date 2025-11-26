package com.neo.velox.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CoreService : LifecycleService(), RecognitionListener, TextToSpeech.OnInitListener {

    private lateinit var audioManager: AudioManager
    private lateinit var telecomManager: TelecomManager
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private lateinit var callHandler: CallHandler
    private lateinit var shakeTrigger: ShakeTrigger

    private var toneGenerator: ToneGenerator? = null
    private var isTtsReady = false
    private var isIncomingCallMode = false
    private var audioFocusRequest: AudioFocusRequest? = null

    // State management
    private var isRecognizerReady = false
    private var isCurrentlyListening = false
    private var isReinitializing = false
    private var hasAudioFocus = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reinitRunnable: Runnable? = null

    // Store last partial result as fallback
    private var lastPartialResult: String? = null

    private enum class TtsAction { NONE, LISTEN, CLOSE }
    private var currentTtsAction = TtsAction.NONE

    companion object {
        const val ACTION_TRIGGER_VOICE = "com.neo.velox.ACTION_TRIGGER"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "VeloxCoreChannel"
        private const val TAG = "CoreService"
        private const val UTTERANCE_ID_FEEDBACK = "velox_feedback"
    }

    override fun onCreate() {
        super.onCreate()
        checkSpeechRecognitionAvailability()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        callHandler = CallHandler(this) { callerName -> handleIncomingCall(callerName) }
        shakeTrigger = ShakeTrigger(this) {
            if (!isIncomingCallMode) startVoiceCommandFlow()
        }
        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
    }

    private fun initSpeechRecognizer() {
        if (isReinitializing) {
            Log.w(TAG, "Already reinitializing, skipping")
            return
        }

        isReinitializing = true
        isRecognizerReady = false

        mainHandler.post {
            try {
                speechRecognizer?.setRecognitionListener(null)
                speechRecognizer?.destroy()
                speechRecognizer = null

                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                    speechRecognizer?.setRecognitionListener(this)
                    isRecognizerReady = true
                    Log.d(TAG, "SpeechRecognizer Initialized Successfully")
                } else {
                    Log.e(TAG, "Speech Recognition NOT Available on this device!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init SpeechRecognizer", e)
                isRecognizerReady = false
            } finally {
                isReinitializing = false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundNotification()
        callHandler.start()
        shakeTrigger.start()

        if (intent?.action == ACTION_TRIGGER_VOICE) {
            Log.d(TAG, "Manual Trigger Received")
            startVoiceCommandFlow()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(CHANNEL_ID, "Velox Assistant")
        } else { "" }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Velox Active")
            .setContentText("Shake device or use Test button")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel(channelId: String, channelName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(chan)
        }
        return channelId
    }

    private fun startVoiceCommandFlow() {
        if (isCurrentlyListening || isReinitializing) {
            Log.w(TAG, "Already listening or reinitializing, ignoring trigger")
            return
        }

        lastPartialResult = null
        reinitRunnable?.let { mainHandler.removeCallbacks(it) }
        reinitRunnable = null

        safeStopListening()
        playShortBeep()

        mainHandler.postDelayed({
            startListening()
        }, 300)
    }

    private fun playShortBeep() {
        try {
            val feedbackTone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            feedbackTone.startTone(ToneGenerator.TONE_PROP_BEEP, 100)

            mainHandler.postDelayed({
                try {
                    feedbackTone.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing feedback tone: ${e.message}")
                }
            }, 150)
        } catch (e: Exception) {
            Log.e(TAG, "Beep playback error: ${e.message}")
        }
    }

    private fun safeStopListening() {
        if (!isCurrentlyListening) return

        try {
            speechRecognizer?.stopListening()
            Log.d(TAG, "Stopped listening")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognizer: ${e.message}")
        } finally {
            isCurrentlyListening = false
        }
    }

    private fun startListening() {
        if (!isRecognizerReady || speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer not ready")
            stopVoiceFlow()
            return
        }

        if (isCurrentlyListening) {
            Log.w(TAG, "Already listening")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }

        try {
            speechRecognizer?.startListening(intent)
            isCurrentlyListening = true
            Log.d(TAG, "SpeechRecognizer: Listening started")
        } catch (e: Exception) {
            Log.e(TAG, "Start Listening Error: ${e.message}")
            isCurrentlyListening = false
            stopVoiceFlow()
        }
    }

    private fun stopVoiceFlow() {
        safeStopListening()
        isIncomingCallMode = false
        lastPartialResult = null
    }

    private fun releaseAudioFocus() {
        if (!hasAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    Log.d(TAG, "Audio focus released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio focus: ${e.message}")
        } finally {
            hasAudioFocus = false
            audioFocusRequest = null
        }
    }

    // --- RecognitionListener ---

    override fun onResults(results: Bundle?) {
        isCurrentlyListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val command = matches[0]
            Log.d(TAG, "Heard (final): $command")
            processCommand(command)
        } else {
            Log.w(TAG, "No final results from recognizer")
            if (!lastPartialResult.isNullOrEmpty()) {
                Log.d(TAG, "Using partial result as fallback: $lastPartialResult")
                processCommand(lastPartialResult!!)
            } else {
                stopVoiceFlow()
            }
        }
    }

    override fun onError(error: Int) {
        isCurrentlyListening = false

        val errorMsg = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO - Check microphone"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT - Service issue"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK - Check internet connection"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT - Slow connection"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH - Speech not recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER - Google server issue"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT - No speech detected"
            else -> "UNKNOWN_ERROR"
        }
        Log.e(TAG, "Speech Error: $errorMsg (code: $error)")

        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> {
                if (!lastPartialResult.isNullOrEmpty()) {
                    Log.d(TAG, "No match error, but using partial result: $lastPartialResult")
                    processCommand(lastPartialResult!!)
                } else {
                    Log.e(TAG, "No match and no partial result available")
                    speakText("Sorry, I didn't catch that", TtsAction.CLOSE)
                }
            }
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                if (!lastPartialResult.isNullOrEmpty()) {
                    Log.d(TAG, "Network error, using partial result: $lastPartialResult")
                    processCommand(lastPartialResult!!)
                } else {
                    Log.e(TAG, "Network error - Check internet connection")
                    speakText("Network error", TtsAction.CLOSE)
                }
            }
            SpeechRecognizer.ERROR_CLIENT -> {
                Log.w(TAG, "Client error - scheduling careful reset")
                stopVoiceFlow()
                reinitRunnable?.let { mainHandler.removeCallbacks(it) }
                reinitRunnable = Runnable {
                    if (!isReinitializing && !isCurrentlyListening) {
                        Log.d(TAG, "Executing scheduled reinit after ERROR_CLIENT")
                        initSpeechRecognizer()
                    }
                    reinitRunnable = null
                }
                mainHandler.postDelayed(reinitRunnable!!, 2000)
            }
            else -> stopVoiceFlow()
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "Ready for speech - microphone opened")
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "Speech started - user is speaking")
    }

    override fun onEndOfSpeech() {
        Log.d(TAG, "Speech ended - processing...")
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty() && matches[0].isNotEmpty()) {
            lastPartialResult = matches[0]
            Log.d(TAG, "Partial result saved: $lastPartialResult")
        }
    }

    // --- Command Logic ---

    private fun processCommand(command: String) {
        val cmd = command.lowercase(Locale.US).trim()

        if (cmd.isEmpty()) {
            Log.w(TAG, "Empty command received")
            stopVoiceFlow()
            return
        }

        Log.d(TAG, "Processing command: '$cmd'")

        if (isIncomingCallMode) {
            when {
                cmd.contains("answer") -> {
                    callHandler.answerCall()
                    speakText("Answering", TtsAction.CLOSE)
                }
                cmd.contains("reject") -> {
                    callHandler.rejectCall()
                    speakText("Call rejected", TtsAction.CLOSE)
                }
                else -> {
                    Log.d(TAG, "Unknown call command: $command")
                    stopVoiceFlow()
                }
            }
        } else {
            when {
                cmd.contains("call") -> {
                    handleCallCommand(cmd)
                }
                cmd.contains("time") -> {
                    val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                    speakText("It is $time", TtsAction.CLOSE)
                }
                cmd.contains("battery") -> {
                    val level = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    speakText("Battery $level percent", TtsAction.CLOSE)
                }
                cmd.contains("next") || cmd.contains("skip") -> {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                    playSuccessTone()
                }
                cmd.contains("play") || cmd.contains("resume") -> {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                    playSuccessTone()
                }
                cmd.contains("pause") || cmd.contains("stop") -> {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    playSuccessTone()
                }
                else -> {
                    Log.d(TAG, "Unknown command: $command")
                    speakText("Unknown command", TtsAction.CLOSE)
                }
            }
        }
    }

    private fun handleCallCommand(command: String) {
        // Extract the contact name or number from the command
        // Examples: "call john", "call john doe", "call 1234567890"
        val callKeywords = listOf("call", "dial", "phone")
        var contactQuery = command

        for (keyword in callKeywords) {
            if (contactQuery.contains(keyword)) {
                contactQuery = contactQuery.substringAfter(keyword).trim()
                break
            }
        }

        if (contactQuery.isEmpty()) {
            speakText("Who should I call?", TtsAction.CLOSE)
            return
        }

        Log.d(TAG, "Attempting to call: $contactQuery")

        // Check if it's a phone number (digits only)
        val digitsOnly = contactQuery.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length >= 10) {
            // It's a phone number
            makePhoneCall(digitsOnly, contactQuery)
        } else {
            // It's a contact name - search contacts
            val phoneNumber = findContactNumber(contactQuery)
            if (phoneNumber != null) {
                makePhoneCall(phoneNumber, contactQuery)
            } else {
                speakText("Contact $contactQuery not found", TtsAction.CLOSE)
            }
        }
    }

    private fun findContactNumber(contactName: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )

        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$contactName%")

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)

            if (cursor != null && cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

                if (numberIndex >= 0 && nameIndex >= 0) {
                    val number = cursor.getString(numberIndex)
                    val name = cursor.getString(nameIndex)
                    Log.d(TAG, "Found contact: $name with number: $number")
                    return number
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching contacts", e)
        } finally {
            cursor?.close()
        }

        return null
    }

    private fun makePhoneCall(phoneNumber: String, displayName: String) {
        try {
            // Use TelecomManager to place call directly (works from background)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val uri = Uri.fromParts("tel", phoneNumber, null)
                telecomManager.placeCall(uri, null)
                speakText("Calling $displayName", TtsAction.CLOSE)
                Log.d(TAG, "Initiated call to $phoneNumber using TelecomManager")
            } else {
                // Fallback for older Android versions
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(callIntent)
                speakText("Calling $displayName", TtsAction.CLOSE)
                Log.d(TAG, "Initiated call to $phoneNumber using Intent")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing CALL_PHONE permission", e)
            speakText("Cannot make calls, permission denied", TtsAction.CLOSE)
        } catch (e: Exception) {
            Log.e(TAG, "Error making phone call", e)
            speakText("Call failed", TtsAction.CLOSE)
        }
    }

    private fun handleIncomingCall(callerName: String) {
        if (requestAudioFocusForTTS()) {
            isIncomingCallMode = true
            speakText("Call from $callerName", TtsAction.LISTEN)
        }
    }

    private fun speakText(text: String, nextAction: TtsAction) {
        if (isTtsReady) {
            currentTtsAction = nextAction
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID_FEEDBACK)
        } else {
            Log.e(TAG, "TTS not ready")
            stopVoiceFlow()
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        try {
            val d = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val u = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(d)
            audioManager.dispatchMediaKeyEvent(u)
            Log.d(TAG, "Media key dispatched: $keyCode")
        } catch (e: Exception) {
            Log.e(TAG, "Media key dispatch error: ${e.message}")
        }
    }

    private fun playSuccessTone() {
        try {
            val successTone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            successTone.startTone(ToneGenerator.TONE_PROP_ACK, 100)
            mainHandler.postDelayed({
                try {
                    successTone.release()
                    stopVoiceFlow()
                } catch (e: Exception) {}
            }, 200)
        } catch (e: Exception) {
            stopVoiceFlow()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US)
            isTtsReady = true
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started speaking")
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == UTTERANCE_ID_FEEDBACK) {
                        Log.d(TAG, "TTS finished speaking")
                        mainHandler.post {
                            when (currentTtsAction) {
                                TtsAction.LISTEN -> startListening()
                                TtsAction.CLOSE -> stopVoiceFlow()
                                TtsAction.NONE -> {}
                            }
                        }
                    }
                }
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error")
                    stopVoiceFlow()
                }
            })
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        reinitRunnable?.let { mainHandler.removeCallbacks(it) }

        callHandler.stop()
        shakeTrigger.stop()

        isRecognizerReady = false
        isCurrentlyListening = false

        speechRecognizer?.setRecognitionListener(null)
        speechRecognizer?.destroy()
        speechRecognizer = null

        releaseAudioFocus()

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        toneGenerator?.release()
        toneGenerator = null

        Log.d(TAG, "Service destroyed cleanly")
    }

    private fun requestAudioFocusForTTS(): Boolean {
        if (hasAudioFocus) {
            Log.d(TAG, "Already have audio focus for TTS")
            return true
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "TTS Audio focus changed: $focusChange")
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            Log.w(TAG, "Lost TTS audio focus permanently")
                            hasAudioFocus = false
                        }
                        AudioManager.AUDIOFOCUS_GAIN,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                            Log.d(TAG, "Gained TTS audio focus")
                            hasAudioFocus = true
                        }
                    }
                }
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

            if (hasAudioFocus) {
                Log.d(TAG, "TTS Audio focus granted")
            } else {
                Log.e(TAG, "TTS Audio focus request denied")
            }

            return hasAudioFocus
        }

        hasAudioFocus = true
        return true
    }

    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun checkSpeechRecognitionAvailability() {
        val available = SpeechRecognizer.isRecognitionAvailable(this)
        Log.d(TAG, "Speech Recognition Available: $available")

        val pm = packageManager
        val activities = pm.queryIntentActivities(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0
        )
        Log.d(TAG, "Available speech recognition services: ${activities.size}")
        activities.forEach {
            Log.d(TAG, "  - ${it.activityInfo.packageName}")
        }
    }
}