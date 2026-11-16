package com.example.core

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

// App Check contract keys defined by Firebase SDK and runtime harness.
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

enum class AppCheckStatus {
    INITIALIZING,
    DEBUG_ACTIVE,
    RECAPTCHA_ACTIVE,
    ERROR
}

object AppCheckStateHolder {
    @Volatile
    var status: AppCheckStatus = AppCheckStatus.INITIALIZING
        private set

    @Volatile
    var lastDiagnostic: String = "Initializing App Check..."
        private set

    fun update(newStatus: AppCheckStatus, diagnostic: String) {
        status = newStatus
        lastDiagnostic = diagnostic
    }
}

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

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
                Log.d(TAG, "App Check debug secret configured successfully.")
            } else {
                Log.w(TAG, "No debug token found in Intent extra; using SDK default debug secret if registered.")
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            AppCheckStateHolder.update(
                AppCheckStatus.DEBUG_ACTIVE,
                "Debug App Check active" + if (!debugToken.isNullOrEmpty()) " (token injected)" else ""
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val resId = context.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                context.packageName
            )
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    AppCheckStateHolder.update(
                        AppCheckStatus.RECAPTCHA_ACTIVE,
                        "reCAPTCHA Enterprise App Check active"
                    )
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    AppCheckStateHolder.update(
                        AppCheckStatus.ERROR,
                        "recaptcha_site_key resource string is blank."
                    )
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                AppCheckStateHolder.update(
                    AppCheckStatus.ERROR,
                    "recaptcha_site_key string resource not found."
                )
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
    } catch (e: Exception) {
        AppCheckStateHolder.update(
            AppCheckStatus.ERROR,
            e.localizedMessage ?: "Failed to initialize Firebase App Check"
        )
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
