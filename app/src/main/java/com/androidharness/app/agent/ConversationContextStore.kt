package com.androidharness.app.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent storage for Perplexity conversation contexts.
 * File-backed implementation (simple, reliable on-device).
 */
class ConversationContextStore(
    private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class ConversationRecord(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<MessageRecord> = emptyList(),
        val status: String = "ACTIVE",
        val contextIncluded: Boolean = false,
        val projectId: String? = null,
        val tags: List<String> = emptyList(),
    )

    @Serializable
    data class MessageRecord(
        val id: String,
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val sources: List<String> = emptyList(),
    )

    @Serializable
    data class ConversationMetadata(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messageCount: Int,
        val status: String,
        val projectId: String? = null,
    )

    private fun storeDir(): File = File(context.filesDir, "perplexity_conversations").apply { mkdirs() }
    private fun convFile(id: String): File = File(storeDir(), "${id}.json")

    /** Save a conversation record. */
    suspend fun saveConversation(record: ConversationRecord): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            convFile(record.id).writeText(json.encodeToString(record))
        }
    }

    /** Get a conversation by ID. */
    suspend fun getConversation(id: String): Result<ConversationRecord?> = withContext(Dispatchers.IO) {
        runCatching {
            val f = convFile(id)
            if (!f.exists()) null else json.decodeFromString<ConversationRecord>(f.readText())
        }
    }

    /** Load an existing conversation or create a new one. */
    suspend fun loadOrCreate(id: String): ConversationContextStore = withContext(Dispatchers.IO) {
        val existing = getConversation(id).getOrNull()
        if (existing == null) {
            val now = System.currentTimeMillis()
            saveConversation(
                ConversationRecord(
                    id = id,
                    title = "Conversation $id",
                    createdAt = now,
                    updatedAt = now,
                    messages = emptyList(),
                    status = "ACTIVE",
                    contextIncluded = false,
                )
            )
        }
        this@ConversationContextStore
    }

    /** Get all conversations. */
    suspend fun getAllConversations(): Result<List<ConversationRecord>> = withContext(Dispatchers.IO) {
        runCatching {
            storeDir().listFiles()?.filter { it.extension == "json" }?.mapNotNull { f ->
                runCatching { json.decodeFromString<ConversationRecord>(f.readText()) }.getOrNull()
            }?.sortedByDescending { it.updatedAt } ?: emptyList()
        }
    }

    /** Get conversation metadata for list display. */
    suspend fun getAllConversationMetadata(): Result<List<ConversationMetadata>> = withContext(Dispatchers.IO) {
        runCatching {
            getAllConversations().getOrThrow().map { r ->
                ConversationMetadata(
                    id = r.id,
                    title = r.title,
                    createdAt = r.createdAt,
                    updatedAt = r.updatedAt,
                    messageCount = r.messages.size,
                    status = r.status,
                    projectId = r.projectId,
                )
            }
        }
    }

    /** Set active conversation ID. */
    suspend fun setActiveConversation(id: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            File(storeDir(), "active.txt").writeText(id.orEmpty())
            Unit
            Unit
        }
    }

    /** Get active conversation ID. */
    suspend fun getActiveConversation(): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(storeDir(), "active.txt")
            if (!f.exists()) null else f.readText().ifBlank { null }
        }
    }

    /** Delete a conversation. */
    suspend fun deleteConversation(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            convFile(id).delete()
            val active = File(storeDir(), "active.txt")
            if (active.exists() && active.readText() == id) active.writeText("")
        }
    }

    /** Clear all conversations. */
    suspend fun clearAll(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            storeDir().listFiles()?.forEach { it.delete() }
            Unit
        }
    }

    /** Search conversations by query. */
    suspend fun searchConversations(query: String): Result<List<ConversationRecord>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = query.lowercase()
            getAllConversations().getOrThrow().filter { record ->
                record.title.lowercase().contains(q) ||
                    record.messages.any { it.content.lowercase().contains(q) } ||
                    record.tags.any { it.lowercase().contains(q) }
            }
        }
    }

    /** Get conversations for a project. */
    suspend fun getConversationsForProject(projectId: String): Result<List<ConversationRecord>> = withContext(Dispatchers.IO) {
        runCatching {
            getAllConversations().getOrThrow().filter { it.projectId == projectId }
        }
    }

    /** Archive old conversations. */
    suspend fun archiveOldConversations(olderThanDays: Int = 30): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val cutoff = System.currentTimeMillis() - (olderThanDays.toLong() * 24 * 60 * 60 * 1000)
            val toArchive = getAllConversations().getOrThrow().filter { it.updatedAt < cutoff && it.status != "ACTIVE" }
            toArchive.forEach { deleteConversation(it.id) }
            toArchive.size
        }
    }
}
