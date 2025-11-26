# Velox - Outdoor Earbuds Assistant: Build Prompts

App Name: Velox

Package Name: com.neo.velox

Target SDK: Android 14/15 (API 34)

Min SDK: Android 8.0 (API 26)

### How to use these prompts:

Copy and paste these into an AI coding assistant (like Gemini, ChatGPT, or Claude) in the **exact order** listed below. Each prompt is designed to be "standalone," meaning it contains enough context for the AI to understand the full project even if you start a new chat session.

### Prompt 1: The Foundation (Manifest, Gradle & Config)

**Goal:** Set up the project structure, permissions, and accessibility configuration.

> Prompt:
> 
> I am building an Android app called "Velox" (Package: com.neo.velox).
> 
> App Description: An outdoor earbuds assistant that runs as a Foreground Service. It uses an Accessibility Service to detect "Volume Button Long-Press" triggers even when the screen is off, allowing voice control for music and calls without waking the phone.
> 
> **Task:** Please generate the 3 foundational files for this project.
> 
> **1. app/build.gradle.kts:**
> 
> - Target SDK: 34, Min SDK: 26.
> 
> - Include dependencies for `androidx.lifecycle` (Service), `kotlinx-coroutines`, and standard UI libs.
> 
> **2. AndroidManifest.xml:**
> 
> - **Critical:** Include permissions for `FOREGROUND_SERVICE` (types: `microphone` and `phoneCall`), `RECORD_AUDIO`, `ANSWER_PHONE_CALLS`, `READ_PHONE_STATE`, `READ_CONTACTS`, `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`, `WAKE_LOCK`.
> 
> - Declare `MainActivity`.
> 
> - Declare a Service `.services.CoreService` (Foreground).
> 
> - Declare an Accessibility Service `.services.VolumeTriggerService` with `BIND_ACCESSIBILITY_SERVICE`.
> 
> **3. res/xml/accessibility_config.xml:**
> 
> - We need to intercept volume keys.
> 
> - Set `android:accessibilityFlags="flagRequestFilterKeyEvents"`.
> 
> - Set `android:canRetrieveWindowContent="false"` (for privacy).
> 
> - Set `android:description` and standard timeout settings.

### Prompt 2: The Trigger (Volume Button Logic)

**Goal:** Create the service that listens for the hardware button press.

> Prompt:
> 
> Project Context: App "Velox" (com.neo.velox). We are building the input mechanism.
> 
> Goal: Create the VolumeTriggerService.kt that detects a long-press on Volume Up/Down without waking the screen.
> 
> **Task:** Generate `services/VolumeTriggerService.kt`.
> 
> **Requirements:**
> 
> 1. **Extend:** `AccessibilityService`.
> 
> 2. **Key Event Logic (`onKeyEvent`):**
>    
>    - Listen for `KEYCODE_VOLUME_UP` and `KEYCODE_VOLUME_DOWN`.
>    
>    - **The Timer Logic:** When a key is *pressed* (ACTION_DOWN), start a generic timestamp check. Do not consume the event yet.
>    
>    - When the key is *released* (ACTION_UP): Calculate the duration.
>    
>    - **Short Press (< 500ms):** Let the event pass through to the system (return `false`) so normal volume control works.
>    
>    - **Long Press (> 500ms):** Consume the event (return `true`) so volume does *not* change. Then, send a broadcast or start the `CoreService` with an Action: `ACTION_TRIGGER_VOICE`.
> 
> 3. **Screen State:** Ensure this logic works even if the screen is off. Do *not* acquire a wakelock here; we just want to send the signal to the CoreService.

### Prompt 3: The Core Service (Focus & Feedback)

**Goal:** Create the main background service that manages the microphone and audio focus.

> Prompt:
> 
> Project Context: App "Velox" (com.neo.velox). We are building the brain of the app.
> 
> Goal: Create services/CoreService.kt which runs in the foreground and handles audio focus.
> 
> **Task:** Generate the `CoreService` class.
> 
> **Requirements:**
> 
> 1. **Setup:** Inherit from `LifecycleService`. In `onStartCommand`, start the Foreground Notification immediately (Title: "Velox Active", Text: "Ready for commands").
> 
> 2. **Trigger Handling:** Listen for the Intent `ACTION_TRIGGER_VOICE` (from the VolumeTriggerService).
> 
> 3. **The "Active" Flow (When triggered):**
>    
>    - **Step A:** Request `AudioFocus` (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK). This lowers the music volume.
>    
>    - **Step B:** Play a system "Beep" or "Tone" (using `ToneGenerator` or `MediaPlayer`) to tell the user the mic is open.
>    
>    - **Step C:** Initialize `SpeechRecognizer` (Android native).
>    
>    - **Step D:** Start Listening.
> 
> 4. **Privacy:** Ensure the microphone is *only* accessed during this specific flow, not constantly.
> 
> 5. **Screen Off:** The code must assume the screen is OFF. Do not attempt to launch UI activities.

### Prompt 4: Voice Logic (Media Controls)

**Goal:** Implement the specific voice commands for music control.

> Prompt:
> 
> Project Context: App "Velox" (com.neo.velox). We are implementing the voice command logic inside CoreService.
> 
> **Task:** Update `CoreService.kt` (or create a `VoiceProcessor` helper class) to handle recognition results.
> 
> **Requirements:**
> 
> 1. **Offline Recognition:** Configure the `SpeechRecognizer` Intent to prefer offline recognition (`EXTRA_PREFER_OFFLINE`).
> 
> 2. **Command List:** Handle these exact phrases (fuzzy matching):
>    
>    - "Next" / "Skip" -> Send `KEYCODE_MEDIA_NEXT`.
>    
>    - "Previous" / "Back" -> Send `KEYCODE_MEDIA_PREVIOUS`.
>    
>    - "Pause" / "Stop" -> Send `KEYCODE_MEDIA_PAUSE`.
>    
>    - "Play" / "Resume" -> Send `KEYCODE_MEDIA_PLAY`.
>    
>    - "Time" -> Use `TextToSpeech` (TTS) to speak the current time.
>    
>    - "Battery" -> Speak the battery percentage.
> 
> 3. **Media Control Implementation:** Use `AudioManager.dispatchMediaKeyEvent` to simulate the headset button presses.
> 
> 4. **Feedback:** After a command is executed, release Audio Focus (restore music volume) and play a "Success" tone.

### Prompt 5: Call Handling (The "Phone" Module)

**Goal:** Implement the Incoming Call detection, TTS announcement, and Answering logic.

> Prompt:
> 
> Project Context: App "Velox" (com.neo.velox). We are adding Phone Call handling.
> 
> **Task:** Create a `CallHandler` class and integrate it into `CoreService`.
> 
> **Requirements:**
> 
> 1. **Detection:** Use a `BroadcastReceiver` or `PhoneStateListener` to detect `RINGING` state.
> 
> 2. **Announcement:** When ringing:
>    
>    - Duck Audio.
>    
>    - Look up the contact name from the number.
>    
>    - Use TTS to say: "Call from [Name]".
>    
>    - Open the microphone immediately to listen for "Answer" or "Reject".
> 
> 3. **Answer Logic:**
>    
>    - If user says "Answer": Call `TelecomManager.acceptRingingCall()`.
>    
>    - **Smart Speakerphone:** After answering, check `AudioManager`. If `isBluetoothA2dpOn()` and `isWiredHeadsetOn()` are BOTH false, wait 500ms and force `setSpeakerphoneOn(true)`.
> 
> 4. **Reject Logic:** If user says "Reject", call `TelecomManager.endCall()`.
> 
> 5. **Timeout:** If no command is heard after 5 seconds, stop listening (let it ring) and restore audio focus.

### Prompt 6: The UI (MainActivity & Permissions)

**Goal:** Create the user interface for granting permissions.

> Prompt:
> 
> Project Context: App "Velox" (com.neo.velox). We need a simple UI to initialize the app.
> 
> **Task:** Generate `MainActivity.kt` and `activity_main.xml`.
> 
> **Requirements:**
> 
> 1. **Design:** "Big Button" interface. Dark background.
>    
>    - Button 1: "Start Velox" (Starts CoreService).
>    
>    - Button 2: "Stop Velox".
>    
>    - Status Text: "Service is [Active/Inactive]".
> 
> 2. **Permission Waterfall:** On `onCreate`, checks permissions in this order:
>    
>    - Runtime Permissions (Mic, Phone, Contacts, Bluetooth).
>    
>    - **Accessibility Check:** Check if `VolumeTriggerService` is enabled. If not, show a dialog explaining why it's needed and deep-link to `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
> 
> 3. **Privacy Note:** Add a text view explaining: "This app runs in the background. Long press Volume Up to speak commands."

### Prompt 7: Battery Optimization

**Goal:** Ensure the app doesn't get killed by Android's battery saver.

> Prompt:
> 
> Project Context: App "Velox" (com.neo.velox).
> 
> Task: Update MainActivity.kt to handle Battery Optimization.
> 
> **Requirements:**
> 
> 1. Check if the app is ignoring battery optimizations using `PowerManager.isIgnoringBatteryOptimizations()`.
> 
> 2. If `false`, show a dialog explaining: "To run efficiently outdoors, Velox needs to stay active in the background."
> 
> 3. If they agree, fire an Intent to `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (requires `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission in Manifest) OR send them to the general battery settings page if that permission is too aggressive.
> 
> 4. **Note:** This ensures the "Accessibility Service" doesn't get terminated by the OS during a long run.

### Prompt 8: Android 16 Compliance (Edge-to-Edge)

**Goal:** Polish the UI so it looks modern and doesn't conflict with system bars.

> Prompt:
> 
> Project Context: App "Velox" (com.neo.velox).
> 
> Task: Update MainActivity.kt and activity_main.xml for Modern Android UI standards (Edge-to-Edge).
> 
> **Requirements:**
> 
> 1. In `MainActivity.onCreate`, call `enableEdgeToEdge()`.
> 
> 2. In the layout XML, ensure the root view has `android:fitsSystemWindows="true"` OR handle the `WindowInsets` manually in Kotlin so the "Start" button isn't hidden behind the status bar or navigation bar.