package com.example.data.local

import kotlinx.coroutines.flow.Flow

class AurixRepository(private val dao: AurixDao) {

    val conversations: Flow<List<ConversationEntity>> = dao.observeConversations()
    val memoryFacts: Flow<List<MemoryFactEntity>> = dao.observeMemoryFacts()
    val totalMessageCount: Flow<Int> = dao.observeTotalMessageCount()

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        dao.observeMessages(conversationId)

    suspend fun ensureConversation(preferredId: Long?, languageCode: String): Long {
        if (preferredId != null && preferredId > 0L) {
            val existing = dao.getConversationById(preferredId)
            if (existing != null) return existing.id
        }
        val title = if (languageCode.startsWith("en")) "New Conversation" else "নতুন কথোপকথন"
        return dao.insertConversation(
            ConversationEntity(
                title = title,
                languageCode = languageCode
            )
        )
    }

    suspend fun createNewConversation(languageCode: String): Long {
        val title = if (languageCode.startsWith("en")) "New Conversation" else "নতুন কথোপকথন"
        return dao.insertConversation(
            ConversationEntity(
                title = title,
                languageCode = languageCode
            )
        )
    }

    suspend fun appendMessage(
        conversationId: Long,
        role: String,
        content: String,
        inputMode: String,
        languageCode: String
    ): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                inputMode = inputMode,
                languageCode = languageCode,
                timestamp = now
            )
        )
        val existingMessages = dao.getMessagesOnce(conversationId)
        if (role == "user" && existingMessages.count { it.role == "user" } <= 1) {
            val cleanTitle = content.trim().replace("\n", " ").take(42)
            if (cleanTitle.isNotEmpty()) {
                dao.updateConversationHeader(conversationId, cleanTitle, now)
            } else {
                dao.touchConversation(conversationId, now)
            }
        } else {
            dao.touchConversation(conversationId, now)
        }
        return id
    }

    suspend fun getMessagesOnce(conversationId: Long): List<MessageEntity> =
        dao.getMessagesOnce(conversationId)

    suspend fun deleteConversation(conversationId: Long) {
        dao.deleteConversationById(conversationId)
    }

    suspend fun clearAllConversations() {
        dao.deleteAllConversations()
    }

    suspend fun getMemoryFactsOnce(): List<MemoryFactEntity> = dao.getMemoryFactsOnce()

    /**
     * Stores user-approved preferences or context while blocking sensitive credentials.
     */
    suspend fun saveMemoryFact(category: String, rawFact: String): Boolean {
        val trimmed = rawFact.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()
        val forbiddenKeywords = listOf(
            "password", "passcode", "pin", "otp", "api_key", "apikey",
            "api key", "secret", "token", "private key", "credit card", "cvv",
            "পাসওয়ার্ড", "পাসওয়ার্ড", "গোপন কোড", "ওটিপি"
        )
        if (forbiddenKeywords.any { lower.contains(it) }) {
            return false
        }
        val existing = dao.getMemoryFactsOnce()
        if (existing.any { it.fact.equals(trimmed, ignoreCase = true) }) {
            return true
        }
        dao.insertMemoryFact(MemoryFactEntity(category = category, fact = trimmed))
        return true
    }

    suspend fun deleteMemoryFact(id: Long) {
        dao.deleteMemoryFactById(id)
    }

    suspend fun clearAllMemoryFacts() {
        dao.deleteAllMemoryFacts()
    }
}
