# AURIX AI — Personal Multilingual Voice & Phone Assistant

**Created, Owned, and Developed by RIAD**  
Official Facebook: [https://www.facebook.com/ZtrRiyvex](https://www.facebook.com/ZtrRiyvex)

AURIX AI is a native Android personal AI assistant built with **Kotlin**, **Jetpack Compose**, **Firebase AI Logic (Gemini Live API & Gemini 3.8 Flash)**, **Room Database**, **Foreground Services**, **Floating Overlay Windows**, and an **Android Accessibility Service**.

---

## Core Capabilities

1. **Official Persistent Identity**
   - Always identifies as **AURIX AI** and attributes ownership/creation to **RIAD** (`https://www.facebook.com/ZtrRiyvex`) across every conversation.
2. **Primary Bangladeshi Bengali (`bn-BD`) + Multilingual Voice & Text**
   - Defaults to natural Bangladeshi Bengali (`bn-BD`) with everyday conversational expressions.
   - Supports live language switching (`"Speak English"` / `"বাংলায় কথা বলো"`), persisted in DataStore Preferences.
3. **Real-Time Gemini Live Voice (`gemini-2.5-flash-native-audio-preview-12-2025`)**
   - Bidirectional 16kHz PCM microphone streaming (`AudioRecord` with AEC/NoiseSuppressor) and 24kHz PCM speech output (`AudioTrack`).
   - Natural male default voice (**Charon**, with Achird, Orus, Fenrir, Puck, Kore, Aoede selectable in Settings).
   - Server-side Voice Activity Detection (VAD) with instant **interruption handling** (flushes queued audio immediately when the user speaks).
   - Real-time input & output speech transcription, live amplitude waveform visualizer, mute/unmute, speaking speed (`0.75x–1.50x`), and volume controls.
4. **Unified Voice + Text Chat (`gemini-3.8-flash`)**
   - Speak and type seamlessly within the same conversation thread with timestamps, streaming updates, and persistent Room SQLite history.
5. **Background Foreground Service & Draggable Floating Bubble**
   - Displays the official **AURIX AI** logo as a draggable floating bubble when minimized (`TYPE_APPLICATION_OVERLAY`), snaps smoothly to the nearest screen edge, and remembers its last `(x, y)` coordinates.
   - Tapping the floating logo opens a **Compact Assistant Window** with voice controls, live transcript, recent messages, text input, and confirmation cards.
6. **Authorized Phone Control & Social Media Confirmation Gate**
   - Launches Facebook, WhatsApp, YouTube, Chrome, web search, and Android system settings.
   - Uses `AurixAccessibilityService` (explicitly enabled by the user) to read visible screen content, scroll, type text, and tap buttons.
   - Sensitive/external actions (e.g., reading a Facebook post and proposing a Bengali comment) always display an editable **Confirmation Card** and wait for explicit user approval before posting.
7. **Memory & Privacy Guard**
   - Stores conversation history and user-approved preferences in local Room SQLite (`AurixDatabase`) while actively blocking passwords, API keys, OTPs, and private secrets.

---

## Architecture

```text
app/src/main/java/com/example/
├── MainActivity.kt                  # Edge-to-edge host, App Check init, lifecycle bubble sync
├── core/
│   ├── AppCheckInit.kt              # Firebase App Check (Debug token commit / Release reCAPTCHA)
│   ├── AurixIdentity.kt             # Owner/creator identity, language & voice definitions
│   └── SystemPromptBuilder.kt       # Multilingual Bengali/English & safety system instructions
├── data/local/
│   ├── AurixEntities.kt             # Room entities (Conversation, Message, MemoryFact)
│   ├── AurixDao.kt                  # Reactive Flow DAOs
│   ├── AurixDatabase.kt             # Room database singleton
│   ├── AurixRepository.kt           # History & memory repository with credential filter
│   └── SettingsRepository.kt        # DataStore Preferences for language, voice, bubble position
├── ai/
│   ├── AurixTools.kt                # Gemini function declarations & phone automation dispatcher
│   ├── LiveAudioEngine.kt           # 16kHz/24kHz PCM streaming, VAD interruption, RMS meter
│   └── AurixAssistantEngine.kt      # Shared Live + Text + Room coordinator across App & Service
├── automation/
│   ├── AurixAccessibilityService.kt # Screen reader, scroll, setText, and click actions
│   └── PhoneAutomationManager.kt    # Authorized intents & confirmation queue
├── service/
│   ├── OverlayLifecycleOwner.kt     # Lifecycle/SavedState bridge for Compose overlay windows
│   └── AurixAssistantService.kt     # Foreground service, edge-snapping bubble, compact window
└── ui/
    ├── theme/                       # Obsidian + Cyan/Purple Neon Material 3 theme & bundled fonts
    ├── components/                  # Animated AurixVoiceOrb, WaveformVisualizer, Chat bubbles
    └── screens/                     # AssistantScreen, AutomationScreen, HistoryScreen, SettingsScreen
```

---

## Security & Credential Setup

AURIX AI routes Gemini requests through **Firebase AI Logic** protected by **Firebase App Check**, so no raw Gemini API key is bundled in the APK or committed to Git.

1. Place your Firebase project's `app/google-services.json` locally (excluded from public commits if private).
2. Review `.env.example` for environment & App Check documentation.
3. For **Debug builds**: pass `FIREBASE_APPCHECK_DEBUG_TOKEN` via launch Intent extras or register the debug secret in Firebase App Check.
4. For **Release builds**: configure `recaptcha_site_key` in `app/src/main/res/values/strings.xml` (or `firebase_applet_config.xml`) using your Google Cloud reCAPTCHA Enterprise Android site key.
5. **Never** commit `*.jks`, `*.keystore`, `.env`, or raw API secrets to GitHub.

---

## Build & APK Generation Instructions

### Debug Build & Unit Tests
```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```
Output APK: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK / AAB
Configure your signing environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) and run:
```bash
gradle :app:assembleRelease
gradle :app:bundleRelease
```
Outputs:
- APK: `app/build/outputs/apk/release/app-release.apk`
- App Bundle: `app/build/outputs/bundle/release/app-release.aab`
