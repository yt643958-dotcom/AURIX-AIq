package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AurixTools
import com.example.ai.ChatHistorySanitizer
import com.example.automation.PhoneAutomationManager
import com.example.core.AurixIdentity
import com.example.core.AurixLanguage
import com.example.core.AurixVoice
import com.example.core.SystemPromptBuilder
import com.example.data.local.AurixDatabase
import com.example.data.local.AurixRepository
import com.example.data.local.MessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name and identity constants match AURIX AI and RIAD`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AURIX AI", appName)
        assertEquals("RIAD", AurixIdentity.OWNER_NAME)
        assertEquals("https://www.facebook.com/ZtrRiyvex", AurixIdentity.OFFICIAL_FACEBOOK_URL)
        assertEquals(AurixVoice.CHARON, AurixVoice.fromApiName(null))
        assertEquals("Male", AurixVoice.CHARON.gender)
    }

    @Test
    fun `system prompt defaults to Bangladeshi Bengali and enforces RIAD identity`() {
        val prompt = SystemPromptBuilder.build(
            language = AurixLanguage.BENGALI,
            memories = listOf("User prefers concise answers"),
            accessibilityEnabled = true
        )
        assertTrue(prompt.contains("Bangladeshi Bengali"))
        assertTrue(prompt.contains("bn-BD"))
        assertTrue(prompt.contains("RIAD"))
        assertTrue(prompt.contains("https://www.facebook.com/ZtrRiyvex"))
        assertTrue(prompt.contains("User prefers concise answers"))
    }

    @Test
    fun `room repository saves Bengali and English messages and blocks sensitive credentials in memory`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AurixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = AurixRepository(db.aurixDao())
            val convId = repo.createNewConversation("bn-BD")
            repo.appendMessage(convId, "user", "কেমন আছো AURIX?", "VOICE", "bn-BD")
            repo.appendMessage(convId, "model", "আমি ভালো আছি! আমি AURIX AI, আমার নির্মাতা RIAD।", "VOICE", "bn-BD")

            val msgs = repo.getMessagesOnce(convId)
            assertEquals(2, msgs.size)
            assertEquals("VOICE", msgs[0].inputMode)

            // Safe memory fact should be stored
            assertTrue(repo.saveMemoryFact("preference", "বাংলায় উত্তর পছন্দ করে"))
            // Sensitive credentials must be rejected
            assertFalse(repo.saveMemoryFact("general", "My password is 123456"))
            assertFalse(repo.saveMemoryFact("general", "API key: AIzaSy123"))

            val facts = repo.getMemoryFactsOnce()
            assertEquals(1, facts.size)
            assertEquals("বাংলায় উত্তর পছন্দ করে", facts[0].fact)
        } finally {
            db.close()
        }
    }

    @Test
    fun `automation manager queues and cancels confirmation for social comments`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PhoneAutomationManager(context)

        val pending = manager.queueConfirmationAction(
            actionTitle = "Confirm Facebook Action",
            appName = "Facebook",
            postSummary = "একটি সুন্দর ভ্রমণের ছবি",
            proposedText = "অসাধারণ ছবি! খুব সুন্দর লাগছে।",
            buttonLabelToClick = "Post",
            isSocialComment = true
        )
        assertNotNull(manager.pendingConfirmation.value)
        assertEquals("অসাধারণ ছবি! খুব সুন্দর লাগছে।", pending.proposedText)

        manager.updatePendingText("চমৎকার পোস্ট!")
        assertEquals("চমৎকার পোস্ট!", manager.pendingConfirmation.value?.proposedText)

        manager.cancelPendingAction()
        assertNull(manager.pendingConfirmation.value)
    }

    @Test
    fun `all AurixTools function declarations have non-empty parameter schemas`() {
        assertEquals(AurixTools.allDeclarations.size, AurixTools.registeredToolMetadata.size)
        for (meta in AurixTools.registeredToolMetadata) {
            assertTrue(
                "FunctionDeclaration ${meta.name} must have non-empty parameters for Gemini OpenAPI validation",
                meta.parameterNames.isNotEmpty()
            )
        }
    }

    @Test
    fun `chat history sanitizer merges consecutive roles and guarantees user-model alternation`() {
        val raw = listOf(
            MessageEntity(id = 1, conversationId = 1, role = "system_action", content = "Launched Facebook", inputMode = "ACTION", languageCode = "bn-BD"),
            MessageEntity(id = 2, conversationId = 1, role = "model", content = "Orphan leading model", inputMode = "TEXT", languageCode = "bn-BD"),
            MessageEntity(id = 3, conversationId = 1, role = "user", content = "হ্যালো", inputMode = "TEXT", languageCode = "bn-BD"),
            MessageEntity(id = 4, conversationId = 1, role = "user", content = "কেমন আছো?", inputMode = "TEXT", languageCode = "bn-BD"),
            MessageEntity(id = 5, conversationId = 1, role = "model", content = "ভালো আছি!", inputMode = "TEXT", languageCode = "bn-BD"),
            MessageEntity(id = 6, conversationId = 1, role = "user", content = "আগের ব্যর্থ প্রশ্ন", inputMode = "TEXT", languageCode = "bn-BD"),
            MessageEntity(id = 7, conversationId = 1, role = "user", content = "বর্তমান প্রশ্ন", inputMode = "TEXT", languageCode = "bn-BD")
        )

        val history = ChatHistorySanitizer.buildChatHistoryBeforeCurrentPrompt(raw)
        assertEquals(2, history.size)
        assertEquals("user", history[0].role)
        assertEquals("model", history[1].role)
    }
}
