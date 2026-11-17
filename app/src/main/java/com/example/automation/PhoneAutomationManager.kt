package com.example.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "PhoneAutomation"

data class PendingActionConfirmation(
    val id: String = java.util.UUID.randomUUID().toString(),
    val actionTitle: String,
    val appName: String,
    val postSummary: String,
    val proposedText: String,
    val buttonLabelToClick: String,
    val preferredFieldHint: String? = null,
    val isSocialComment: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

sealed interface AutomationResult {
    data class Success(val message: String) : AutomationResult
    data class RequiresPermission(val permissionName: String, val guidance: String) : AutomationResult
    data class Failed(val reason: String) : AutomationResult
}

class PhoneAutomationManager(private val context: Context) {

    private val _pendingConfirmation = MutableStateFlow<PendingActionConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingActionConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _lastActionLog = MutableStateFlow<String?>(null)
    val lastActionLog: StateFlow<String?> = _lastActionLog.asStateFlow()

    fun openUrl(url: String): AutomationResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AutomationResult.Success("Opened link: $url")
        } catch (e: Exception) {
            AutomationResult.Failed("Could not open link: ${e.localizedMessage}")
        }
    }

    fun openApp(appName: String): AutomationResult {
        val normalized = appName.trim().lowercase()
        val (packageName, fallbackUrl, label) = when {
            normalized.contains("facebook") || normalized.contains("fb") || normalized.contains("ফেসবুক") ->
                Triple("com.facebook.katana", "https://www.facebook.com", "Facebook")
            normalized.contains("whatsapp") || normalized.contains("হোয়াটসঅ্যাপ") || normalized.contains("হোয়াটসঅ্যাপ") ->
                Triple("com.whatsapp", "https://wa.me", "WhatsApp")
            normalized.contains("youtube") || normalized.contains("ইউটিউব") ->
                Triple("com.google.android.youtube", "https://www.youtube.com", "YouTube")
            normalized.contains("chrome") || normalized.contains("ক্রোম") || normalized.contains("browser") ->
                Triple("com.android.chrome", "https://www.google.com", "Chrome")
            else -> Triple("", "", appName)
        }

        if (packageName.isNotEmpty()) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                val msg = "$label opened."
                _lastActionLog.value = msg
                return AutomationResult.Success(msg)
            }
            if (fallbackUrl.isNotEmpty() && packageName != "com.whatsapp") {
                return try {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(webIntent)
                    val msg = "$label app is not installed; opened $label in browser."
                    _lastActionLog.value = msg
                    AutomationResult.Success(msg)
                } catch (e: Exception) {
                    AutomationResult.Failed("Failed to open $label: ${e.localizedMessage}")
                }
            }
            return AutomationResult.Failed("$label is not installed on this device.")
        }

        val resolved = resolveLaunchablePackageByLabel(normalized)
        if (resolved != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(resolved)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                val msg = "Opened $appName."
                _lastActionLog.value = msg
                return AutomationResult.Success(msg)
            }
        }
        return AutomationResult.Failed("Could not find supported app '$appName' to launch.")
    }

    private fun resolveLaunchablePackageByLabel(query: String): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return matches.firstOrNull {
            it.loadLabel(context.packageManager).toString().lowercase().contains(query)
        }?.activityInfo?.packageName
    }

    fun openPhoneSettings(settingType: String): AutomationResult {
        val normalized = settingType.trim().lowercase()
        val action = when {
            normalized.contains("wifi") || normalized.contains("wi-fi") || normalized.contains("ওয়াইফাই") ->
                Settings.ACTION_WIFI_SETTINGS
            normalized.contains("bluetooth") || normalized.contains("ব্লুটুথ") ->
                Settings.ACTION_BLUETOOTH_SETTINGS
            normalized.contains("sound") || normalized.contains("volume") || normalized.contains("সাউন্ড") ->
                Settings.ACTION_SOUND_SETTINGS
            normalized.contains("display") || normalized.contains("brightness") || normalized.contains("ডিসপ্লে") ->
                Settings.ACTION_DISPLAY_SETTINGS
            normalized.contains("battery") || normalized.contains("ব্যাটারি") ->
                Settings.ACTION_BATTERY_SAVER_SETTINGS
            normalized.contains("location") || normalized.contains("লোকেশন") ->
                Settings.ACTION_LOCATION_SOURCE_SETTINGS
            normalized.contains("app") ->
                Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        return try {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val msg = "Opened $settingType settings."
            _lastActionLog.value = msg
            AutomationResult.Success(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings: $settingType", e)
            AutomationResult.Failed("Unable to open $settingType settings.")
        }
    }

    fun searchWeb(query: String): AutomationResult {
        val clean = query.trim()
        if (clean.isEmpty()) return AutomationResult.Failed("Search query is empty.")
        return try {
            val encoded = Uri.encode(clean)
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=$encoded")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val msg = "Searched Google for \"$clean\"."
            _lastActionLog.value = msg
            AutomationResult.Success(msg)
        } catch (e: Exception) {
            AutomationResult.Failed("Failed to launch web search: ${e.localizedMessage}")
        }
    }

    fun queueConfirmationAction(
        actionTitle: String,
        appName: String,
        postSummary: String,
        proposedText: String,
        buttonLabelToClick: String,
        preferredFieldHint: String? = null,
        isSocialComment: Boolean = true
    ): PendingActionConfirmation {
        val confirmation = PendingActionConfirmation(
            actionTitle = actionTitle,
            appName = appName,
            postSummary = postSummary,
            proposedText = proposedText,
            buttonLabelToClick = buttonLabelToClick,
            preferredFieldHint = preferredFieldHint,
            isSocialComment = isSocialComment
        )
        _pendingConfirmation.value = confirmation
        return confirmation
    }

    fun updatePendingText(newText: String) {
        _pendingConfirmation.value = _pendingConfirmation.value?.copy(proposedText = newText)
    }

    fun cancelPendingAction() {
        _pendingConfirmation.value = null
        _lastActionLog.value = "Pending action cancelled by user."
    }

    fun executeApprovedAction(): AutomationResult {
        val pending = _pendingConfirmation.value
            ?: return AutomationResult.Failed("No pending draft to apply.")

        val copied = try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null && pending.proposedText.isNotBlank()) {
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("AURIX Draft", pending.proposedText)
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard copy failed", e)
            false
        }

        val launchResult = if (pending.appName.isNotBlank()) {
            openApp(pending.appName)
        } else {
            AutomationResult.Success("Draft ready.")
        }

        _pendingConfirmation.value = null

        return when {
            copied && launchResult is AutomationResult.Success -> {
                val msg = "Copied to clipboard & opened ${pending.appName} — paste and tap '${pending.buttonLabelToClick}'."
                _lastActionLog.value = msg
                AutomationResult.Success(msg)
            }
            copied -> {
                val msg = "Copied draft to clipboard: \"${pending.proposedText}\". Open ${pending.appName} and paste."
                _lastActionLog.value = msg
                AutomationResult.Success(msg)
            }
            launchResult is AutomationResult.Success -> launchResult
            else -> AutomationResult.Failed("Could not copy draft or open ${pending.appName}.")
        }
    }
}
