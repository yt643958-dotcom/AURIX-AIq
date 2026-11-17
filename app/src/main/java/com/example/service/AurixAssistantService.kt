package com.example.service

import android.Manifest
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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.ai.AurixAssistantEngine
import com.example.ai.VoiceSessionState
import com.example.core.AurixIdentity

private const val TAG = "AurixAssistantService"
private const val CHANNEL_ID = "aurix_assistant_channel"
private const val NOTIFICATION_ID = 1001

class AurixAssistantService : Service() {

    private lateinit var engine: AurixAssistantEngine

    override fun onCreate() {
        super.onCreate()
        engine = AurixAssistantEngine.getInstance(applicationContext)
        createNotificationChannel()
        startForegroundSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    companion object {
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
