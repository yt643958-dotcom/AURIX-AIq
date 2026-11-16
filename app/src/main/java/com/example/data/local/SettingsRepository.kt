package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.AurixIdentity
import com.example.core.AurixLanguage
import com.example.core.AurixVoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.aurixDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aurix_settings_prefs"
)

data class AurixSettings(
    val language: AurixLanguage = AurixLanguage.BENGALI,
    val voice: AurixVoice = AurixVoice.CHARON,
    val voiceSpeed: Float = 1.0f,
    val volume: Float = 0.9f,
    val floatingBubbleEnabled: Boolean = true,
    val backgroundServiceEnabled: Boolean = true,
    val memoryEnabled: Boolean = true,
    val bubbleX: Int = -1,
    val bubbleY: Int = 360,
    val activeConversationId: Long = -1L,
    val ownerName: String = AurixIdentity.OWNER_NAME,
    val officialFacebookUrl: String = AurixIdentity.OFFICIAL_FACEBOOK_URL
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("preferred_language")
        val VOICE = stringPreferencesKey("preferred_voice")
        val VOICE_SPEED = floatPreferencesKey("voice_speed")
        val VOLUME = floatPreferencesKey("volume_level")
        val FLOATING_BUBBLE = booleanPreferencesKey("floating_bubble_enabled")
        val BACKGROUND_SERVICE = booleanPreferencesKey("background_service_enabled")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val BUBBLE_X = intPreferencesKey("bubble_pos_x")
        val BUBBLE_Y = intPreferencesKey("bubble_pos_y")
        val ACTIVE_CONVERSATION_ID = longPreferencesKey("active_conversation_id")
        val IDENTITY_OWNER = stringPreferencesKey("identity_owner")
        val IDENTITY_FB = stringPreferencesKey("identity_facebook")
    }

    val settingsFlow: Flow<AurixSettings> = context.aurixDataStore.data.map { prefs ->
        AurixSettings(
            language = AurixLanguage.fromCode(prefs[Keys.LANGUAGE]),
            voice = AurixVoice.fromApiName(prefs[Keys.VOICE]),
            voiceSpeed = (prefs[Keys.VOICE_SPEED] ?: 1.0f).coerceIn(0.75f, 1.5f),
            volume = (prefs[Keys.VOLUME] ?: 0.9f).coerceIn(0.0f, 1.0f),
            floatingBubbleEnabled = prefs[Keys.FLOATING_BUBBLE] ?: true,
            backgroundServiceEnabled = prefs[Keys.BACKGROUND_SERVICE] ?: true,
            memoryEnabled = prefs[Keys.MEMORY_ENABLED] ?: true,
            bubbleX = prefs[Keys.BUBBLE_X] ?: -1,
            bubbleY = prefs[Keys.BUBBLE_Y] ?: 360,
            activeConversationId = prefs[Keys.ACTIVE_CONVERSATION_ID] ?: -1L,
            ownerName = prefs[Keys.IDENTITY_OWNER] ?: AurixIdentity.OWNER_NAME,
            officialFacebookUrl = prefs[Keys.IDENTITY_FB] ?: AurixIdentity.OFFICIAL_FACEBOOK_URL
        )
    }

    suspend fun getCurrentSettings(): AurixSettings = settingsFlow.first()

    suspend fun ensurePersistentIdentity() {
        context.aurixDataStore.edit { prefs ->
            prefs[Keys.IDENTITY_OWNER] = AurixIdentity.OWNER_NAME
            prefs[Keys.IDENTITY_FB] = AurixIdentity.OFFICIAL_FACEBOOK_URL
            if (prefs[Keys.LANGUAGE] == null) {
                prefs[Keys.LANGUAGE] = AurixLanguage.BENGALI.code
            }
            if (prefs[Keys.VOICE] == null) {
                prefs[Keys.VOICE] = AurixVoice.CHARON.apiName
            }
        }
    }

    suspend fun setLanguage(language: AurixLanguage) {
        context.aurixDataStore.edit { it[Keys.LANGUAGE] = language.code }
    }

    suspend fun setVoice(voice: AurixVoice) {
        context.aurixDataStore.edit { it[Keys.VOICE] = voice.apiName }
    }

    suspend fun setVoiceSpeed(speed: Float) {
        context.aurixDataStore.edit { it[Keys.VOICE_SPEED] = speed.coerceIn(0.75f, 1.5f) }
    }

    suspend fun setVolume(volume: Float) {
        context.aurixDataStore.edit { it[Keys.VOLUME] = volume.coerceIn(0.0f, 1.0f) }
    }

    suspend fun setFloatingBubbleEnabled(enabled: Boolean) {
        context.aurixDataStore.edit { it[Keys.FLOATING_BUBBLE] = enabled }
    }

    suspend fun setBackgroundServiceEnabled(enabled: Boolean) {
        context.aurixDataStore.edit { it[Keys.BACKGROUND_SERVICE] = enabled }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        context.aurixDataStore.edit { it[Keys.MEMORY_ENABLED] = enabled }
    }

    suspend fun setBubblePosition(x: Int, y: Int) {
        context.aurixDataStore.edit {
            it[Keys.BUBBLE_X] = x
            it[Keys.BUBBLE_Y] = y
        }
    }

    suspend fun setActiveConversationId(id: Long) {
        context.aurixDataStore.edit { it[Keys.ACTIVE_CONVERSATION_ID] = id }
    }
}
