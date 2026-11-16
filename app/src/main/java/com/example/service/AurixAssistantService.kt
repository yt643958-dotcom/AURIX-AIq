package com.example.service

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.R
import com.example.ai.AurixAssistantEngine
import com.example.ai.TextChatState
import com.example.ai.VoiceSessionState
import com.example.core.AurixIdentity
import com.example.ui.components.AurixLogoMark
import com.example.ui.components.ChatMessageBubble
import com.example.ui.components.LiveTranscriptBanner
import com.example.ui.components.PendingActionConfirmationCard
import com.example.ui.components.VoiceWaveformVisualizer
import com.example.ui.theme.AurixElectricBlue
import com.example.ui.theme.AurixEmerald
import com.example.ui.theme.AurixGlassBorder
import com.example.ui.theme.AurixMidnight
import com.example.ui.theme.AurixNeonCyan
import com.example.ui.theme.AurixNeonPurple
import com.example.ui.theme.AurixObsidian
import com.example.ui.theme.AurixRoseError
import com.example.ui.theme.AurixTextPrimary
import com.example.ui.theme.AurixTextSecondary
import com.example.ui.theme.AurixTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val TAG = "AurixAssistantService"
private const val CHANNEL_ID = "aurix_assistant_channel"
private const val NOTIFICATION_ID = 1001

class AurixAssistantService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var engine: AurixAssistantEngine
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var compactWindowView: ComposeView? = null
    private var compactParams: WindowManager.LayoutParams? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null

    private var isCompactOpen = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        engine = AurixAssistantEngine.getInstance(applicationContext)
        createNotificationChannel()
        startForegroundSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUBBLE -> {
                if (Settings.canDrawOverlays(this) && engine.settings.value.floatingBubbleEnabled) {
                    if (!isCompactOpen) showBubble()
                }
            }
            ACTION_HIDE_BUBBLE -> {
                removeBubble()
                removeCompactWindow()
            }
            ACTION_OPEN_COMPACT -> {
                if (Settings.canDrawOverlays(this)) {
                    openCompactWindow()
                }
            }
            ACTION_TOGGLE_VOICE -> {
                when (engine.voiceState.value) {
                    VoiceSessionState.DISCONNECTED, VoiceSessionState.ERROR -> {
                        if (hasRecordAudioPermission()) engine.startVoiceConversation()
                    }
                    else -> engine.stopVoiceConversation()
                }
            }
            ACTION_STOP_SERVICE -> {
                engine.stopVoiceConversation()
                removeBubble()
                removeCompactWindow()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Default start: show bubble if app is backgrounded & overlay allowed
                if (!engine.isAppInForeground.value &&
                    Settings.canDrawOverlays(this) &&
                    engine.settings.value.floatingBubbleEnabled &&
                    !isCompactOpen
                ) {
                    showBubble()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeBubble()
        removeCompactWindow()
        serviceScope.cancel()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundSafely() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Microphone FGS permission missing; starting basic foreground fallback: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ex: Exception) {
                Log.e(TAG, "Foreground service start failed", ex)
            }
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleVoiceIntent = Intent(this, AurixAssistantService::class.java).apply {
            action = ACTION_TOGGLE_VOICE
        }
        val voicePending = PendingIntent.getService(
            this, 1, toggleVoiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AurixAssistantService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIconOpts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val largeIcon = BitmapFactory.decodeResource(resources, R.drawable.aurix_logo, largeIconOpts)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(largeIcon)
            .setContentTitle("${AurixIdentity.APP_NAME} • Active")
            .setContentText("Personal AI Assistant by ${AurixIdentity.OWNER_NAME}")
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "Voice", voicePending)
            .addAction(0, "Stop", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubbleView != null || !Settings.canDrawOverlays(this)) return

        val density = resources.displayMetrics.density
        val sizePx = (64 * density).toInt()
        val padPx = (4 * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val savedSettings = engine.settings.value
        val initialX = if (savedSettings.bubbleX >= 0) savedSettings.bubbleX else (screenWidth - sizePx - (12 * density).toInt())
        val initialY = savedSettings.bubbleY.coerceIn(100, (screenHeight - sizePx - 120).coerceAtLeast(100))

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        // Preserve original aspect ratio using ImageView inside a rounded neon frame
        val cleanContainer = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            elevation = 18f
        }
        val logoImage = ImageView(this).apply {
            setImageResource(R.drawable.aurix_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.logo_content_description)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 18f * density)
                }
            }
        }
        cleanContainer.addView(
            logoImage,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var isDrag = false

        cleanContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    isDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDrag = true
                    }
                    params.x = (startX + dx).coerceIn(0, (screenWidth - sizePx).coerceAtLeast(0))
                    params.y = (startY + dy).coerceIn(60, (screenHeight - sizePx - 80).coerceAtLeast(60))
                    try {
                        windowManager.updateViewLayout(cleanContainer, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDrag) {
                        openCompactWindow()
                    } else {
                        snapBubbleToNearestEdge(cleanContainer, params, sizePx, screenWidth)
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = cleanContainer
        bubbleParams = params
        try {
            windowManager.addView(cleanContainer, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating bubble overlay", e)
            bubbleView = null
        }
    }

    private fun snapBubbleToNearestEdge(
        view: View,
        params: WindowManager.LayoutParams,
        sizePx: Int,
        screenWidth: Int
    ) {
        val margin = (12 * resources.displayMetrics.density).toInt()
        val targetX = if (params.x + sizePx / 2 < screenWidth / 2) {
            margin
        } else {
            (screenWidth - sizePx - margin).coerceAtLeast(margin)
        }
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                try {
                    if (view.isAttachedToWindow) {
                        windowManager.updateViewLayout(view, params)
                    }
                } catch (_: Exception) {
                }
            }
            start()
        }
        serviceScope.launch {
            engine.settingsRepository.setBubblePosition(targetX, params.y)
        }
    }

    private fun removeBubble() {
        bubbleView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (_: Exception) {
            }
        }
        bubbleView = null
        bubbleParams = null
    }

    private fun openCompactWindow() {
        if (compactWindowView != null || !Settings.canDrawOverlays(this)) return
        removeBubble()
        isCompactOpen = true

        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * 0.92f).toInt().coerceAtMost((380 * density).toInt())

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val lifecycleOwner = OverlayLifecycleOwner().apply {
            performRestore(null)
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        overlayLifecycleOwner = lifecycleOwner

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)

            setContent {
                AurixTheme {
                    CompactAssistantOverlayContent(
                        engine = engine,
                        hasMicPermission = hasRecordAudioPermission(),
                        onMinimize = {
                            removeCompactWindow()
                            if (engine.settings.value.floatingBubbleEnabled && !engine.isAppInForeground.value) {
                                showBubble()
                            }
                        },
                        onOpenFullApp = {
                            removeCompactWindow()
                            val openIntent = Intent(this@AurixAssistantService, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            startActivity(openIntent)
                        },
                        onCloseOverlay = {
                            removeCompactWindow()
                        }
                    )
                }
            }
        }

        compactWindowView = composeView
        compactParams = params
        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open compact assistant window", e)
            removeCompactWindow()
        }
    }

    private fun removeCompactWindow() {
        isCompactOpen = false
        compactWindowView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (_: Exception) {
            }
        }
        compactWindowView = null
        compactParams = null

        overlayLifecycleOwner?.let { owner ->
            owner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            owner.clear()
        }
        overlayLifecycleOwner = null
    }

    companion object {
        const val ACTION_SHOW_BUBBLE = "com.example.aurix.ACTION_SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.example.aurix.ACTION_HIDE_BUBBLE"
        const val ACTION_OPEN_COMPACT = "com.example.aurix.ACTION_OPEN_COMPACT"
        const val ACTION_TOGGLE_VOICE = "com.example.aurix.ACTION_TOGGLE_VOICE"
        const val ACTION_STOP_SERVICE = "com.example.aurix.ACTION_STOP_SERVICE"

        fun startServiceAction(context: Context, action: String? = null) {
            val intent = Intent(context, AurixAssistantService::class.java).apply {
                if (action != null) this.action = action
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not send action $action to AurixAssistantService", e)
            }
        }
    }
}

@Composable
private fun CompactAssistantOverlayContent(
    engine: AurixAssistantEngine,
    hasMicPermission: Boolean,
    onMinimize: () -> Unit,
    onOpenFullApp: () -> Unit,
    onCloseOverlay: () -> Unit
) {
    val messages by engine.messages.collectAsStateWithLifecycle()
    val voiceState by engine.voiceState.collectAsStateWithLifecycle()
    val isMuted by engine.isMuted.collectAsStateWithLifecycle()
    val micAmp by engine.micAmplitude.collectAsStateWithLifecycle()
    val aiAmp by engine.aiAmplitude.collectAsStateWithLifecycle()
    val liveUser by engine.liveUserTranscript.collectAsStateWithLifecycle()
    val liveAi by engine.liveAiTranscript.collectAsStateWithLifecycle()
    val textChatState by engine.textChatState.collectAsStateWithLifecycle()
    val statusBanner by engine.statusBanner.collectAsStateWithLifecycle()
    val pending by engine.pendingConfirmation.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val recent = remember(messages) { messages.takeLast(8) }

    LaunchedEffect(recent.size, textChatState) {
        val extra = if (textChatState !is TextChatState.Idle) 1 else 0
        if (recent.isNotEmpty() || extra > 0) {
            listState.animateScrollToItem((recent.size + extra - 1).coerceAtLeast(0))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(AurixObsidian.copy(alpha = 0.97f), AurixMidnight.copy(alpha = 0.97f))
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(listOf(AurixNeonCyan, AurixNeonPurple)),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(14.dp)
    ) {
        // Header with exact AURIX AI logo + RIAD + Minimize / Expand / Close
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            AurixLogoMark(size = 36.dp, cornerRadius = 10.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${AurixIdentity.APP_NAME} • ${AurixIdentity.OWNER_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                    color = AurixTextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (voiceState) {
                        VoiceSessionState.LISTENING -> "Listening (বাংলা / EN)"
                        VoiceSessionState.SPEAKING -> "AURIX Speaking..."
                        VoiceSessionState.CONNECTING -> "Connecting Gemini Live..."
                        VoiceSessionState.MUTED -> "Microphone Muted"
                        VoiceSessionState.ERROR -> "Connection Error"
                        VoiceSessionState.DISCONNECTED -> "Ready • Voice & Text"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AurixNeonCyan
                )
            }
            IconButton(onClick = onOpenFullApp, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.OpenInFull, contentDescription = "Open full app", tint = AurixTextSecondary)
            }
            IconButton(onClick = onMinimize, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Minimize to bubble", tint = AurixTextSecondary)
            }
            IconButton(onClick = onCloseOverlay, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close compact window", tint = AurixRoseError)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        VoiceWaveformVisualizer(
            voiceState = voiceState,
            micAmplitude = micAmp,
            aiAmplitude = aiAmp,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        LiveTranscriptBanner(
            userTranscript = liveUser,
            aiTranscript = liveAi,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        pending?.let { conf ->
            PendingActionConfirmationCard(
                pending = conf,
                onEditProposedText = { engine.automationManager.updatePendingText(it) },
                onApprove = { engine.approvePendingConfirmation() },
                onCancel = { engine.cancelPendingConfirmation() },
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (!statusBanner.isNullOrBlank()) {
            Text(
                text = statusBanner.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = AurixRoseError,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 210.dp)
        ) {
            if (recent.isEmpty()) {
                item {
                    Text(
                        text = "বাংলায় বা ইংরেজিতে বলুন অথবা লিখুন...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurixTextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(recent, key = { it.id }) { msg ->
                    ChatMessageBubble(message = msg)
                }
            }

            if (textChatState is TextChatState.Sending) {
                item(key = "compact_sending") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = AurixNeonCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AURIX AI চিন্তা করছে...",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurixTextSecondary
                        )
                    }
                }
            } else if (textChatState is TextChatState.Error) {
                item(key = "compact_error") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AurixRoseError.copy(alpha = 0.16f))
                            .border(1.dp, AurixRoseError.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = (textChatState as TextChatState.Error).message,
                            style = MaterialTheme.typography.bodySmall,
                            color = AurixTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { engine.retryLastMessage() }) {
                            Text("Retry", color = AurixNeonCyan)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Unified Voice + Text controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val isVoiceActive = voiceState == VoiceSessionState.LISTENING ||
                voiceState == VoiceSessionState.SPEAKING ||
                voiceState == VoiceSessionState.MUTED ||
                voiceState == VoiceSessionState.CONNECTING

            IconButton(
                onClick = {
                    if (isVoiceActive) {
                        engine.stopVoiceConversation()
                    } else if (hasMicPermission) {
                        engine.startVoiceConversation()
                    } else {
                        onOpenFullApp()
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isVoiceActive) AurixRoseError.copy(alpha = 0.22f)
                        else AurixNeonCyan.copy(alpha = 0.18f)
                    )
                    .border(
                        1.dp,
                        if (isVoiceActive) AurixRoseError else AurixNeonCyan,
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (isVoiceActive) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (isVoiceActive) "Stop voice" else "Start voice",
                    tint = if (isVoiceActive) AurixRoseError else AurixNeonCyan
                )
            }

            if (isVoiceActive) {
                IconButton(
                    onClick = { engine.toggleMute() },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AurixElectricBlue.copy(alpha = 0.18f))
                        .border(1.dp, AurixGlassBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = if (isMuted) AurixRoseError else AurixEmerald
                    )
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("লিখুন / Type...", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AurixNeonCyan,
                    unfocusedBorderColor = AurixGlassBorder,
                    focusedTextColor = AurixTextPrimary,
                    unfocusedTextColor = AurixTextPrimary
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { }
            )

            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        engine.sendTextMessage(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AurixNeonPurple.copy(alpha = 0.28f))
                    .border(1.dp, AurixNeonPurple, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = Color.White
                )
            }
        }
    }
}
