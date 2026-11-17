package com.example.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckToken
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "AppCheckInit"

// App Check contract keys defined by Firebase SDK and runtime harness.
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val TOKEN_STORE_PREFIX = "com.google.firebase.appcheck.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val META_PREFS_NAME = "aurix_appcheck_meta"
private const val META_INJECTED_DEBUG_KEY = "has_injected_debug_token"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"
private const val TOKEN_EXPIRY_SAFETY_WINDOW_MS = 3 * 60 * 1000L

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

private val appCheckLock = Any()

@Volatile
private var installedModeSignature: String? = null

@JvmOverloads
fun configureAppCheck(context: Context, intent: Intent?, forceReinstall: Boolean = false) {
    synchronized(appCheckLock) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }

            val appCheck = FirebaseAppCheck.getInstance()
            val metaPrefs = context.getSharedPreferences(META_PREFS_NAME, Context.MODE_PRIVATE)
            runCatching { appCheck.setTokenAutoRefreshEnabled(true) }

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

                    val targetSig = "DEBUG_INJECTED:$debugToken"
                    if (forceReinstall || installedModeSignature != targetSig) {
                        appCheck.installAppCheckProviderFactory(
                            DebugAppCheckProviderFactory.getInstance(),
                            true
                        )
                        installedModeSignature = targetSig
                    }
                    AppCheckStateHolder.update(
                        AppCheckStatus.DEBUG_ACTIVE,
                        "Debug App Check active (token injected)"
                    )
                    Log.d(TAG, "DebugAppCheckProviderFactory installed with injected token.")
                } else if (metaPrefs.getBoolean(META_INJECTED_DEBUG_KEY, false) || isAndroidEmulator()) {
                    val isPersisted = metaPrefs.getBoolean(META_INJECTED_DEBUG_KEY, false)
                    val targetSig = if (isPersisted) "DEBUG_PERSISTED" else "DEBUG_EMULATOR"
                    if (forceReinstall || installedModeSignature != targetSig) {
                        appCheck.installAppCheckProviderFactory(
                            DebugAppCheckProviderFactory.getInstance(),
                            true
                        )
                        installedModeSignature = targetSig
                    }
                    AppCheckStateHolder.update(
                        AppCheckStatus.DEBUG_ACTIVE,
                        if (isPersisted) {
                            "Debug App Check active (persisted token)"
                        } else {
                            "Debug App Check active (emulator)"
                        }
                    )
                    Log.d(TAG, "DebugAppCheckProviderFactory installed ($targetSig).")
                } else {
                    // Standalone debug APK on a physical device has no harness debug token; use provisioned reCAPTCHA Enterprise.
                    val installedRecaptcha = installRecaptchaProvider(context, appCheck, forceReinstall)
                    if (!installedRecaptcha) {
                        val targetSig = "DEBUG_FALLBACK"
                        if (forceReinstall || installedModeSignature != targetSig) {
                            appCheck.installAppCheckProviderFactory(
                                DebugAppCheckProviderFactory.getInstance(),
                                true
                            )
                            installedModeSignature = targetSig
                        }
                        AppCheckStateHolder.update(
                            AppCheckStatus.DEBUG_ACTIVE,
                            "Debug App Check active (no injected token)"
                        )
                        Log.w(TAG, "No debug token or recaptcha_site_key found; installed DebugAppCheckProviderFactory.")
                    }
                }
            } else {
                val installedRecaptcha = installRecaptchaProvider(context, appCheck, forceReinstall)
                if (!installedRecaptcha) {
                    AppCheckStateHolder.update(
                        AppCheckStatus.ERROR,
                        "recaptcha_site_key string resource missing or blank."
                    )
                }
            }

            // Non-blocking diagnostic verification & proactive expiry check.
            prefetchAppCheckHealth(context)
        } catch (e: Exception) {
            AppCheckStateHolder.update(
                AppCheckStatus.ERROR,
                e.localizedMessage ?: "Failed to initialize Firebase App Check"
            )
            Log.e(TAG, "Failed to initialize Firebase App Check", e)
        }
    }
}

fun prefetchAppCheckHealth(context: Context) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) return
        val appCheck = FirebaseAppCheck.getInstance()
        appCheck.getAppCheckToken(false)
            .addOnSuccessListener { token ->
                val remainingMs = token.expireTimeMillis - System.currentTimeMillis()
                if (token.token.isBlank() || remainingMs < TOKEN_EXPIRY_SAFETY_WINDOW_MS) {
                    Log.i(TAG, "Cached App Check token missing or expiring soon (${remainingMs}ms); refreshing.")
                    appCheck.getAppCheckToken(true)
                        .addOnSuccessListener { refreshed ->
                            if (refreshed.token.isNotBlank()) {
                                markVerifiedStatus()
                            }
                        }
                        .addOnFailureListener { refreshEx ->
                            Log.w(TAG, "App Check background refresh failed: ${refreshEx.message}", refreshEx)
                        }
                } else {
                    markVerifiedStatus()
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
        Log.w(TAG, "App Check prefetch skipped: ${e.message}")
    }
}

suspend fun ensureAppCheckTokenFresh(
    context: Context,
    forceRefresh: Boolean = false,
    clearCacheAndReinstall: Boolean = false
): Boolean = withContext(Dispatchers.IO) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        if (clearCacheAndReinstall) {
            clearCachedAppCheckTokenStore(context)
            configureAppCheck(context, null, forceReinstall = true)
        } else if (installedModeSignature == null) {
            configureAppCheck(context, null, forceReinstall = false)
        }

        val appCheck = FirebaseAppCheck.getInstance()
        runCatching { appCheck.setTokenAutoRefreshEnabled(true) }

        val firstAttempt = awaitAppCheckToken(appCheck, forceRefresh = forceRefresh)
        val firstExpiringSoon = firstAttempt != null &&
            (firstAttempt.token.isBlank() ||
                (firstAttempt.expireTimeMillis - System.currentTimeMillis()) < TOKEN_EXPIRY_SAFETY_WINDOW_MS)

        val finalToken = if (firstAttempt != null && !firstExpiringSoon) {
            firstAttempt
        } else {
            if (!forceRefresh) {
                Log.i(TAG, "Forcing App Check token refresh because cached token is missing or near expiry.")
            }
            awaitAppCheckToken(appCheck, forceRefresh = true)
        }

        if (finalToken != null && finalToken.token.isNotBlank()) {
            markVerifiedStatus()
            Log.d(TAG, "App Check token confirmed fresh (expiresInMs=${finalToken.expireTimeMillis - System.currentTimeMillis()}).")
            true
        } else {
            AppCheckStateHolder.update(
                AppCheckStatus.ERROR,
                "App Check token refresh returned empty or timed out."
            )
            false
        }
    } catch (e: Exception) {
        Log.w(TAG, "ensureAppCheckTokenFresh failed: ${e.message}", e)
        AppCheckStateHolder.update(
            AppCheckStatus.ERROR,
            "App Check refresh failed: ${e.localizedMessage ?: e.message ?: "Unknown error"}"
        )
        false
    }
}

private suspend fun awaitAppCheckToken(
    appCheck: FirebaseAppCheck,
    forceRefresh: Boolean
): AppCheckToken? = withTimeoutOrNull(12_000L) {
    suspendCancellableCoroutine { cont ->
        appCheck.getAppCheckToken(forceRefresh)
            .addOnSuccessListener { token ->
                if (cont.isActive) cont.resume(token)
            }
            .addOnFailureListener { ex ->
                Log.w(TAG, "getAppCheckToken(forceRefresh=$forceRefresh) failed: ${ex.message}", ex)
                if (cont.isActive) cont.resume(null)
            }
    }
}

private fun clearCachedAppCheckTokenStore(context: Context) {
    try {
        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val storePrefs = context.getSharedPreferences(
            "$TOKEN_STORE_PREFIX$persistenceKey",
            Context.MODE_PRIVATE
        )
        storePrefs.edit().clear().commit()
        Log.d(TAG, "Cleared persisted Firebase App Check token cache before re-attestation.")
    } catch (e: Exception) {
        Log.w(TAG, "Unable to clear App Check token store: ${e.message}")
    }
}

private fun markVerifiedStatus() {
    val current = AppCheckStateHolder.status
    val verifiedStatus = if (current == AppCheckStatus.ERROR || current == AppCheckStatus.INITIALIZING) {
        if (installedModeSignature?.startsWith("DEBUG") == true) {
            AppCheckStatus.DEBUG_ACTIVE
        } else {
            AppCheckStatus.RECAPTCHA_ACTIVE
        }
    } else {
        current
    }
    val modeLabel = if (verifiedStatus == AppCheckStatus.DEBUG_ACTIVE) {
        "Debug App Check verified"
    } else {
        "reCAPTCHA Enterprise verified"
    }
    AppCheckStateHolder.update(verifiedStatus, modeLabel)
}

private fun installRecaptchaProvider(
    context: Context,
    appCheck: FirebaseAppCheck,
    forceReinstall: Boolean
): Boolean {
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
    val targetSig = "RECAPTCHA:$siteKey"
    if (forceReinstall || installedModeSignature != targetSig) {
        appCheck.installAppCheckProviderFactory(
            RecaptchaAppCheckProviderFactory.getInstance(siteKey),
            true
        )
        installedModeSignature = targetSig
        Log.d(TAG, "RecaptchaAppCheckProviderFactory installed (forceReinstall=$forceReinstall).")
    } else {
        Log.d(TAG, "RecaptchaAppCheckProviderFactory already active for this process.")
    }
    AppCheckStateHolder.update(
        AppCheckStatus.RECAPTCHA_ACTIVE,
        "reCAPTCHA Enterprise App Check active"
    )
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
