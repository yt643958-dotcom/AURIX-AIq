package com.example.core

object AurixIdentity {
    const val APP_NAME = "AURIX AI"
    const val OWNER_NAME = "RIAD"
    const val CREATOR_NAME = "RIAD"
    const val OFFICIAL_FACEBOOK_URL = "https://www.facebook.com/ZtrRiyvex"
    const val APP_VERSION = "1.0.3"

    const val OWNER_ANSWER_EN =
        "My owner and creator is RIAD. He is the person behind AURIX AI."
    const val OWNER_ANSWER_BN =
        "আমার মালিক ও নির্মাতা হলেন RIAD। তিনিই AURIX AI-এর পেছনের মানুষ।"

    const val LIVE_MODEL_NAME = "gemini-3.1-flash-live-preview"
    const val LIVE_FALLBACK_MODEL_NAME = "gemini-2.5-flash-native-audio-preview-12-2025"
    const val TEXT_MODEL_NAME = "gemini-3.8-flash"
    const val TEXT_FALLBACK_MODEL_NAME = "gemini-3.5-flash-lite"
}

enum class AurixLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String
) {
    BENGALI("bn-BD", "Bengali (Bangladesh)", "বাংলা (বাংলাদেশ)"),
    ENGLISH("en-US", "English", "English"),
    MULTILINGUAL("auto", "Multilingual Auto", "বহুভাষিক (Auto)");

    companion object {
        fun fromCode(code: String?): AurixLanguage {
            return entries.firstOrNull { it.code == code } ?: BENGALI
        }
    }
}

enum class AurixVoice(
    val apiName: String,
    val displayName: String,
    val gender: String,
    val toneDescription: String
) {
    CHARON("Charon", "Charon", "Male", "Deep, calm & intelligent"),
    ACHIRD("Achird", "Achird", "Male", "Friendly & warm"),
    ORUS("Orus", "Orus", "Male", "Firm & clear"),
    FENRIR("Fenrir", "Fenrir", "Male", "Expressive & energetic"),
    PUCK("Puck", "Puck", "Male", "Upbeat & conversational"),
    KORE("Kore", "Kore", "Female", "Firm & articulate"),
    AOEDE("Aoede", "Aoede", "Female", "Bright & breezy");

    companion object {
        fun fromApiName(name: String?): AurixVoice {
            return entries.firstOrNull { it.apiName.equals(name, ignoreCase = true) } ?: CHARON
        }
    }
}
