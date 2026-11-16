package com.example.automation

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

    fun isAccessibilityEnabled(): Boolean = AurixAccessibilityService.isConnected.value

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

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
            // Fallback to web URL for supported web apps if native app isn't installed
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

        // Try matching installed app label via package manager queryable packages
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
            normalized.contains("accessibility") || normalized.contains("অ্যাক্সেসিবিলিটি") ->
                Settings.ACTION_ACCESSIBILITY_SETTINGS
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

    fun readVisibleScreen(): AutomationResult {
        val service = AurixAccessibilityService.instance
            ?: return AutomationResult.RequiresPermission(
                permissionName = "Accessibility Service",
                guidance = "Please enable AURIX AI Accessibility Service in Android Settings so I can read visible screen content."
            )
        val snapshot = service.captureVisibleScreen()
        val msg = buildString {
            appendLine("Active app package: ${snapshot.packageName.ifEmpty { "Unknown" }}")
            if (snapshot.extractedPosts.isNotEmpty()) {
                appendLine("Main visible text/posts:")
                snapshot.extractedPosts.take(6).forEachIndexed { idx, post ->
                    appendLine("${idx + 1}. $post")
                }
            } else {
                appendLine(snapshot.summaryText)
            }
            if (snapshot.clickableLabels.isNotEmpty()) {
                appendLine("Visible buttons: ${snapshot.clickableLabels.take(8).joinToString(", ")}")
            }
        }
        _lastActionLog.value = "Captured visible screen from ${snapshot.packageName}"
        return AutomationResult.Success(msg.trim())
    }

    fun scrollScreen(direction: String): AutomationResult {
        val service = AurixAccessibilityService.instance
            ?: return AutomationResult.RequiresPermission(
                permissionName = "Accessibility Service",
                guidance = "Enable AURIX AI Accessibility Service to scroll content on screen."
            )
        val forward = !direction.lowercase().let { it.contains("up") || it.contains("back") || it.contains("উপরে") }
        val ok = service.scrollScreen(forward)
        return if (ok) {
            val dirText = if (forward) "down" else "up"
            _lastActionLog.value = "Scrolled $dirText"
            AutomationResult.Success("Scrolled $dirText successfully.")
        } else {
            AutomationResult.Failed("No scrollable content found on the current screen.")
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
            ?: return AutomationResult.Failed("No pending action to execute.")
        val service = AurixAccessibilityService.instance
        if (service == null) {
            _pendingConfirmation.value = null
            return AutomationResult.RequiresPermission(
                permissionName = "Accessibility Service",
                guidance = "Enable AURIX AI Accessibility Service to type and submit on external apps."
            )
        }

        // First tap "Comment" if needed to open the comment box on Facebook/social apps
        if (pending.isSocialComment) {
            service.clickNodeByLabel("Comment") || service.clickNodeByLabel("কমেন্ট")
        }

        var typed = true
        if (pending.proposedText.isNotBlank()) {
            typed = service.typeTextIntoEditable(
                text = pending.proposedText,
                preferredHint = pending.preferredFieldHint
            )
        }

        var clicked = true
        if (pending.buttonLabelToClick.isNotBlank()) {
            clicked = service.clickNodeByLabel(pending.buttonLabelToClick)
        }

        _pendingConfirmation.value = null

        return when {
            typed && clicked -> {
                val msg = "Approved action completed in ${pending.appName}."
                _lastActionLog.value = msg
                AutomationResult.Success(msg)
            }
            typed -> {
                val msg = "Typed \"${pending.proposedText}\" in ${pending.appName}, but could not find '${pending.buttonLabelToClick}' button automatically — please tap it to finish."
                _lastActionLog.value = msg
                AutomationResult.Success(msg)
            }
            else -> {
                AutomationResult.Failed(
                    "Could not find an editable input box on the current ${pending.appName} screen. Please open the post's comment field first and approve again."
                )
            }
        }
    }
}
