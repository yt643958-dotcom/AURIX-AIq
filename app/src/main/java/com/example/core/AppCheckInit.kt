package com.example.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AppCheckInit"

// App Check contract keys defined by Firebase SDK and runtime harness.
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val META_PREFS_NAME = "aurix_appcheck_meta"
private const val META_INJECTED_DEBUG_KEY = "has_injected_debug_token"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

enum class AppCheckStatus {
    INITIALIZING,
    DEBUG_ACTIVE,
    RECAPTCHA_ACTIVE,
    ERROR
}

object AppCheckStateHolder {
    private val _statusFlow = MutableStateFlow(AppCheckStatus.INITIALIZING)
    val statusFlow: StateFlow<AppCheckStatus> = _statusFlow.asStateFlow()

    private val _diagnosticFlow = MutableStateFlow("Initializing App Check...")
    val diagnosticFlow: StateFlow<String> = _diagnosticFlow.asStateFlow()

    val status: AppCheckStatus
        get() = _statusFlow.value

    val lastDiagnostic: String
        get() = _diagnosticFlow.value

    fun update(newStatus: AppCheckStatus, diagnostic: String) {
        _statusFlow.value = newStatus
        _diagnosticFlow.value = diagnostic
    }
}

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        val appCheck = FirebaseAppCheck.getInstance()
        val metaPrefs = context.getSharedPreferences(META_PREFS_NAME, Context.MODE_PRIVATE)

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // Synchronous commit() ensures the secret is written before DebugAppCheckProviderFactory reads it.
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                metaPrefs.edit()
                    .putBoolean(META_INJECTED_DEBUG_KEY, true)
                    .commit()

                appCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                AppCheckStateHolder.update(
                    AppCheckStatus.DEBUG_ACTIVE,
                    "Debug App Check active (token injected)"
                )
                Log.d(TAG, "DebugAppCheckProviderFactory installed with injected token.")
            } else if (metaPrefs.getBoolean(META_INJECTED_DEBUG_KEY, false) || isAndroidEmulator()) {
                appCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                AppCheckStateHolder.update(
                    AppCheckStatus.DEBUG_ACTIVE,
                    if (metaPrefs.getBoolean(META_INJECTED_DEBUG_KEY, false)) {
                        "Debug App Check active (persisted token)"
                    } else {
                        "Debug App Check active (emulator)"
                    }
                )
                Log.d(TAG, "DebugAppCheckProviderFactory installed.")
            } else {
                // Standalone debug APK on a physical device has no harness debug token; use provisioned reCAPTCHA Enterprise if available.
                val installedRecaptcha = installRecaptchaProvider(context, appCheck)
                if (!installedRecaptcha) {
                    appCheck.installAppCheckProviderFactory(
                        DebugAppCheckProviderFactory.getInstance()
                    )
                    AppCheckStateHolder.update(
                        AppCheckStatus.DEBUG_ACTIVE,
                        "Debug App Check active (no injected token)"
                    )
                    Log.w(TAG, "No debug token or recaptcha_site_key found; installed DebugAppCheckProviderFactory.")
                }
            }
        } else {
            val installedRecaptcha = installRecaptchaProvider(context, appCheck)
            if (!installedRecaptcha) {
                AppCheckStateHolder.update(
                    AppCheckStatus.ERROR,
                    "recaptcha_site_key string resource missing or blank."
                )
            }
        }

        // Non-blocking diagnostic verification so Settings / error hints show live token status.
        appCheck.getAppCheckToken(false)
            .addOnSuccessListener { token ->
                if (token.token.isNotBlank()) {
                    val current = AppCheckStateHolder.status
                    val verifiedStatus = if (current == AppCheckStatus.ERROR) {
                        AppCheckStatus.RECAPTCHA_ACTIVE
                    } else {
                        current
                    }
                    val modeLabel = if (verifiedStatus == AppCheckStatus.DEBUG_ACTIVE) {
                        "Debug App Check verified"
                    } else {
                        "reCAPTCHA Enterprise verified"
                    }
                    AppCheckStateHolder.update(verifiedStatus, modeLabel)
                    Log.d(TAG, "App Check token verified successfully ($modeLabel).")
                }
            }
            .addOnFailureListener { ex ->
                Log.w(TAG, "App Check token prefetch failed: ${ex.message}", ex)
                AppCheckStateHolder.update(
                    AppCheckStatus.ERROR,
                    "App Check failed: ${ex.localizedMessage ?: ex.message ?: "Unknown attestation error"}"
                )
            }
    } catch (e: Exception) {
        AppCheckStateHolder.update(
            AppCheckStatus.ERROR,
            e.localizedMessage ?: "Failed to initialize Firebase App Check"
        )
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

private fun installRecaptchaProvider(context: Context, appCheck: FirebaseAppCheck): Boolean {
    val resId = context.resources.getIdentifier(
        RECAPTCHA_SITE_KEY_RES,
        "string",
        context.packageName
    )
    if (resId == 0) {
        Log.e(TAG, "recaptcha_site_key string resource not found in package.")
        return false
    }
    val siteKey = context.getString(resId)
    if (siteKey.isBlank()) {
        Log.e(TAG, "recaptcha_site_key resource string is blank.")
        return false
    }
    appCheck.installAppCheckProviderFactory(
        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
    )
    AppCheckStateHolder.update(
        AppCheckStatus.RECAPTCHA_ACTIVE,
        "reCAPTCHA Enterprise App Check active"
    )
    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
    return true
}

private fun isAndroidEmulator(): Boolean {
    if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
        return false
    }
    return Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("google_sdk") ||
        Build.MODEL.contains("Emulator") ||
        Build.MODEL.contains("Android SDK built for") ||
        Build.MANUFACTURER.contains("Genymotion") ||
        Build.HARDWARE.contains("goldfish") ||
        Build.HARDWARE.contains("ranchu") ||
        Build.PRODUCT.contains("sdk_gphone") ||
        Build.PRODUCT.contains("emulator") ||
        Build.PRODUCT.contains("simulator") ||
        (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
}
