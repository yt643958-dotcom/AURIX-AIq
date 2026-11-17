package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.ai.AurixAssistantEngine
import com.example.core.configureAppCheck
import com.example.service.AurixAssistantService
import com.example.ui.AurixApp
import com.example.ui.AurixViewModel
import com.example.ui.components.AurixLogoCache
import com.example.ui.theme.AurixTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AurixViewModel by viewModels()
    private var startVoiceAfterMicGrant = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissions()
        if (granted) {
            if (viewModel.settings.value.backgroundServiceEnabled) {
                AurixAssistantService.startServiceAction(this, null)
            }
            if (startVoiceAfterMicGrant) {
                startVoiceAfterMicGrant = false
                viewModel.startVoiceConversation()
            }
        } else {
            startVoiceAfterMicGrant = false
            viewModel.onMicPermissionDenied()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureAppCheck(this, intent)
        AurixLogoCache.preloadAsync(applicationContext)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        setContent {
            AurixTheme {
                AurixApp(
                    viewModel = viewModel,
                    onRequestMicPermissionAndStartVoice = {
                        if (hasMicPermission()) {
                            viewModel.startVoiceConversation()
                        } else {
                            startVoiceAfterMicGrant = true
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onRequestMicPermission = {
                        if (!hasMicPermission()) {
                            startVoiceAfterMicGrant = false
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!intent.getStringExtra("FIREBASE_APPCHECK_DEBUG_TOKEN").isNullOrEmpty()) {
            configureAppCheck(this, intent)
        }
    }

    override fun onStart() {
        super.onStart()
        AurixAssistantEngine.getInstance(applicationContext).setAppForeground(true)
        viewModel.refreshPermissions()
    }

    override fun onStop() {
        super.onStop()
        AurixAssistantEngine.getInstance(applicationContext).setAppForeground(false)
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}
