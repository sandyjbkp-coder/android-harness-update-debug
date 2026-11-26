package com.androidharness.app.agent

import android.content.Context
import android.util.Log
import com.androidharness.app.agent.ConversationContextStore
import com.androidharness.app.llm.PerplexityConversationManager
import com.androidharness.app.llm.PerplexityConversationManager.ConversationState
import com.androidharness.app.llm.PerplexityConversationManager.PerplexityMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * Coordinates multi-turn research sessions with Perplexity through the existing
 * PerplexityConversationManager bridge.
 *
 * AndroidHarness owns the research session. Perplexity responses are treated as
 * untrusted research input — no commands are executed and no device state is
 * modified based on Perplexity recommendations.
 *
 * All Android device-side interaction goes through PerplexityConversationManager
 * (which uses PerplexityAppAdapter → RootRunner su -c). This coordinator
 * orchestrates turns, tracks status, handles follow-ups, and produces a
 * structured ResearchResult.
 */
class ResearchCoordinator(
    private val context: Context,
    private val conversationManager: PerplexityConversationManager,
) {
    private val TAG = "ResearchCoordinator"

    // Backward-compatible context store accessor for AndroidHarnessExecutionController
    val contextStoreManager = ConversationContextStore(context)

    private val ongoingSessions = mutableMapOf<String, ResearchSession>()
    private val sessionCounter = AtomicInteger(0)

    private val URL_PATTERN = Pattern.compile(
        "https?://[^\\s<>\"]+",
        Pattern.CASE_INSENSITIVE,
    )

    /** Maximum turns per research session before forcing a stop. */
    private val maxTurns: Int = 10

    /** Per-turn timeout in seconds. */
    private val turnTimeoutSeconds: Int = 60

    // ==================== Data models ====================

    /**
     * A single research turn: one question sent, one response received (or error).
     */
    @Serializable
    data class ResearchTurn(
        val turnNumber: Int,
        val question: String,
        val response: String? = null,
        val status: TurnStatus = TurnStatus.PENDING,
        val error: String? = null,
        val sources: List<String> = emptyList(),
        val timestamp: Long = System.currentTimeMillis(),
        val isFollowUp: Boolean = false,
        val followUpOf: Int? = null,
    )

    enum class TurnStatus { PENDING, COMPLETED, FAILED, TIMED_OUT, SKIPPED, CANCELLED }

    /**
     * Structured result of a research session.
     */
    @Serializable
    data class ResearchResult(
        val taskId: String,
        val objective: String,
        val researchQuestions: List<String>,
        val turns: List<ResearchTurn>,
        val keyFindings: List<String> = emptyList(),
        val uncertainties: List<String> = emptyList(),
        val sourceUrls: List<String> = emptyList(),
        val status: SessionStatus = SessionStatus.IN_PROGRESS,
        val totalTurns: Int = 0,
        val completedTurns: Int = 0,
        val failedTurns: Int = 0,
        val startedAt: Long = System.currentTimeMillis(),
        val completedAt: Long? = null,
        val error: String? = null,
    )

    enum class SessionStatus { IN_PROGRESS, COMPLETED, TIMED_OUT, CANCELLED, FAILED }

    /**
     * Internal session state tracking turns and progress.
     */
    private data class ResearchSession(
        val sessionId: String,
        val taskId: String,
        val objective: String,
        val conversationId: String,
        val context: Map<String, String>,
        val researchQuestions: MutableList<String> = mutableListOf(),
        val turns: MutableList<ResearchTurn> = mutableListOf(),
        val cancelled: AtomicReference<Boolean> = AtomicReference(false),
        val startTime: Long = System.currentTimeMillis(),
    )

    // ==================== Session lifecycle ====================

    /**
     * Start a new research session with an objective.
     *
     * Breaks the objective into initial research questions and sends the first
     * one through the conversation bridge. All turns share the same Perplexity
     * thread (same conversationId).
     */
    suspend fun startResearchSession(
        taskId: String,
        initialPrompt: String,
        context: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting research session: $initialPrompt")

        val conversationState = conversationManager.startConversation(
            initialMessage = initialPrompt,
            context = context,
        )

        val session = ResearchSession(
            sessionId = conversationState.id,
            taskId = taskId,
            objective = initialPrompt,
            conversationId = conversationState.id,
            context = context,
        )
        ongoingSessions[conversationState.id] = session
        sessionCounter.incrementAndGet()

        // Decompose objective into initial research questions
        val questions = decomposeObjective(initialPrompt)
        session.researchQuestions.addAll(questions)

        // Persist conversation context
        contextStoreManager.saveConversation(
            ConversationContextStore.ConversationRecord(
                id = conversationState.id,
                title = conversationState.title,
                createdAt = conversationState.createdAt,
                updatedAt = conversationState.updatedAt,
                messages = conversationState.messages.map {
                    ConversationContextStore.MessageRecord(
                        id = it.id,
                        role = it.role,
                        content = it.content,
                        timestamp = it.timestamp,
                        sources = it.sources,
                    )
                },
                status = conversationState.status.name,
                contextIncluded = conversationState.contextIncluded,
            )
        ).getOrDefault(Unit)

        Log.d(TAG, "Created session ${conversationState.id} with ${questions.size} initial questions")

        return@withContext conversationState.id
    }

    /**
     * Handle to an active research session, allowing the caller to drive turns
     * and cancellation.
     */
    class ResearchSessionHandle private constructor(
        val sessionId: String,
        private val coordinator: ResearchCoordinator,
    ) {
        fun cancel() {
            coordinator.cancelSessionSync(sessionId)
        }
        fun isCancelled(): Boolean {
            coordinator.sessionsLock.lock()
            try {
                val cancelled = coordinator.ongoingSessions[sessionId]?.cancelled?.get() ?: true
                return cancelled
            } finally {
                coordinator.sessionsLock.unlock()
            }
        }
    }

    private val sessionsLock = java.util.concurrent.locks.ReentrantLock()

    /** Cancel a research session. Turns in progress finish; no new turns start. */
    suspend fun cancelSession(sessionId: String) {
        sessionsLock.lock()
        try {
            ongoingSessions[sessionId]?.cancelled?.set(true)
        } finally {
            sessionsLock.unlock()
        }
    }

    /** Non-suspend version of cancelSession for use from non-suspend callers. */
    fun cancelSessionSync(sessionId: String) {
        sessionsLock.lock()
        try {
            ongoingSessions[sessionId]?.cancelled?.set(true)
        } finally {
            sessionsLock.unlock()
        }
    }

    /** Get the current structured result for a session (partial results available). */
    suspend fun getResearchResult(sessionId: String): ResearchResult? {
        sessionsLock.lock()
        try {
            val session = ongoingSessions[sessionId] ?: return null
            return buildResult(session)
        } finally {
            sessionsLock.unlock()
        }
    }

    /** Get the conversation state for a session (backward-compatible). */
    fun getResearchState(sessionId: String): ConversationState? {
        sessionsLock.lock()
        try {
            val session = ongoingSessions[sessionId] ?: return null
            return conversationManager.getConversation(session.conversationId)
        } finally {
            sessionsLock.unlock()
        }
    }

    /** List active session IDs. */
    fun getActiveSessionIds(): List<String> {
        sessionsLock.lock()
        try {
            return ongoingSessions.keys.toList()
        } finally {
            sessionsLock.unlock()
        }
    }

    /** Mark a session complete (backward-compatible). */
    suspend fun completeSession(sessionId: String) {
        sessionsLock.lock()
        try {
            ongoingSessions[sessionId]?.let { session ->
                conversationManager.completeConversation(session.conversationId)
            }
        } finally {
            sessionsLock.unlock()
        }
    }

    // ==================== Research execution ====================

    /**
     * Run the full research session: iterate through questions sequentially,
     * asking follow-ups when needed, until sufficient info or turn limit.
     *
     * This is the main entry point AndroidHarness uses to drive a session.
     */
    suspend fun runResearch(sessionId: String): ResearchResult = withContext(Dispatchers.IO) {
        sessionsLock.lock()
        val session = ongoingSessions[sessionId]
        sessionsLock.unlock()

        if (session == null) {
            return@withContext ResearchResult(
                taskId = "",
                objective = "",
                researchQuestions = emptyList(),
                turns = emptyList(),
                status = SessionStatus.FAILED,
                error = "Session not found: $sessionId",
            )
        }

        Log.d(TAG, "Running research session $sessionId (${session.researchQuestions.size} questions)")

        var currentQuestionIndex = 0
        var turnNumber = 0

        while (currentQuestionIndex < session.researchQuestions.size) {
            sessionsLock.lock()
            val isCancelled = session.cancelled.get()
            sessionsLock.unlock()

            if (isCancelled) {
                Log.d(TAG, "Session $sessionId cancelled")
                return@withContext buildResult(session, status = SessionStatus.CANCELLED)
            }

            if (turnNumber >= maxTurns) {
                Log.d(TAG, "Session $sessionId reached turn limit $maxTurns")
                return@withContext buildResult(session, status = SessionStatus.TIMED_OUT)
            }

            val question = session.researchQuestions[currentQuestionIndex]
            turnNumber++

            Log.d(TAG, "Session $sessionId turn $turnNumber: $question")

            val turn = try {
                withTimeout(turnTimeoutSeconds * 1000L) {
                    sendTurn(session, turnNumber, question, isFollowUp = false)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Session $sessionId turn $turnNumber cancelled")
                session.turns.add(
                    ResearchTurn(
                        turnNumber = turnNumber,
                        question = question,
                        status = TurnStatus.CANCELLED,
                        error = "Turn cancelled",
                    ),
                )
                return@withContext buildResult(session, status = SessionStatus.CANCELLED)
            } catch (e: Exception) {
                Log.w(TAG, "Session $sessionId turn $turnNumber failed: ${e.message}")
                session.turns.add(
                    ResearchTurn(
                        turnNumber = turnNumber,
                        question = question,
                        status = TurnStatus.FAILED,
                        error = e.message ?: "Unknown error",
                    ),
                )
                // Error in one turn does not corrupt previous turns; continue to next question
                currentQuestionIndex++
                continue
            }

            // Check if response is incomplete/ambiguous → ask follow-up
            val needsFollowUp = shouldAskFollowUp(turn)
            if (needsFollowUp && turnNumber < maxTurns) {
                val followUpQuestion = generateFollowUp(question, turn.response ?: "")
                turnNumber++

                if (turnNumber >= maxTurns) {
                    Log.d(TAG, "Session $sessionId turn limit reached before follow-up")
                    return@withContext buildResult(session, status = SessionStatus.TIMED_OUT)
                }

                Log.d(TAG, "Session $sessionId turn $turnNumber follow-up: $followUpQuestion")

                val followUpTurn = try {
                    withTimeout(turnTimeoutSeconds * 1000L) {
                        sendTurn(session, turnNumber, followUpQuestion, isFollowUp = true, followUpOf = turn.turnNumber)
                    }
                } catch (e: CancellationException) {
                    session.turns.add(
                        ResearchTurn(
                            turnNumber = turnNumber,
                            question = followUpQuestion,
                            status = TurnStatus.CANCELLED,
                            error = "Follow-up cancelled",
                            followUpOf = turn.turnNumber,
                        ),
                    )
                    return@withContext buildResult(session, status = SessionStatus.CANCELLED)
                } catch (e: Exception) {
                    Log.w(TAG, "Session $sessionId follow-up turn $turnNumber failed: ${e.message}")
                    session.turns.add(
                        ResearchTurn(
                            turnNumber = turnNumber,
                            question = followUpQuestion,
                            status = TurnStatus.FAILED,
                            error = e.message ?: "Unknown error",
                            followUpOf = turn.turnNumber,
                        ),
                    )
                    currentQuestionIndex++
                    continue
                }
            }

            currentQuestionIndex++
        }

        // Determine final status
        val hasFailures = session.turns.any { it.status == TurnStatus.FAILED || it.status == TurnStatus.TIMED_OUT }
        val allCompleted = session.turns.all { it.status == TurnStatus.COMPLETED }

        val finalStatus = when {
            !allCompleted && hasFailures -> SessionStatus.FAILED
            !allCompleted -> SessionStatus.IN_PROGRESS
            else -> SessionStatus.COMPLETED
        }

        return@withContext buildResult(session, status = finalStatus)
    }

    /**
     * Send a single turn through the conversation bridge and record it.
     * Errors here are caught by the caller — this method does not swallow them.
     */
    private suspend fun sendTurn(
        session: ResearchSession,
        turnNumber: Int,
        question: String,
        isFollowUp: Boolean = false,
        followUpOf: Int? = null,
    ): ResearchTurn {
        // Send through existing bridge (same conversation = same thread)
        val response = conversationManager.sendMessage(
            conversationId = session.conversationId,
            message = question,
            context = session.context,
        )

        val sources = extractSources(response)
        val status = if (response.isNotBlank()) TurnStatus.COMPLETED else TurnStatus.FAILED

        val turn = ResearchTurn(
            turnNumber = turnNumber,
            question = question,
            response = response.takeIf { it.isNotBlank() },
            status = status,
            sources = sources,
            isFollowUp = isFollowUp,
            followUpOf = followUpOf,
        )

        sessionsLock.lock()
        try {
            session.turns.add(turn)
        } finally {
            sessionsLock.unlock()
        }

        Log.d(TAG, "Session ${session.sessionId} turn $turnNumber completed: ${response.length} chars, ${sources.size} sources")
        return turn
    }

    // ==================== Question decomposition & follow-up ====================

    /**
     * Break a research objective into focused initial questions.
     * Heuristic-based — no LLM call, keeps AndroidHarness as orchestrator.
     */
    private fun decomposeObjective(objective: String): List<String> {
        val questions = mutableListOf<String>()

        // If objective already looks like a question, use it directly
        if (objective.contains("?") || objective.lowercase().startsWith("explain") ||
            objective.lowercase().startsWith("describe") || objective.lowercase().startsWith("what") ||
            objective.lowercase().startsWith("how") || objective.lowercase().startsWith("compare")) {
            questions.add(objective)
            return questions
        }

        // Otherwise split into sub-questions
        questions.add("What is $objective?")
        questions.add("What are the key components and mechanisms of $objective?")
        questions.add("What are the official documentation references for $objective?")

        return questions
    }

    /**
     * Determine whether a response warrants a follow-up question.
     * Heuristics: too short, uncertainty language, or missing key question terms.
     */
    private fun shouldAskFollowUp(turn: ResearchTurn): Boolean {
        val response = turn.response ?: return true
        val question = turn.question.lowercase()

        // Too short to be substantive
        if (response.length < 100) return true

        // Uncertainty language
        val uncertaintyWords = listOf("uncertain", "not sure", "unclear", "depends on", "may vary", "not clear")
        if (uncertaintyWords.any { response.lowercase().contains(it) }) return true

        // Response doesn't address key terms from the question
        val keyTerms = question.split(" ").filter { it.length > 4 }.toSet()
        val matchedTerms = keyTerms.count { term -> response.lowercase().contains(term) }
        if (keyTerms.size > 2 && matchedTerms < keyTerms.size / 2) return true

        return false
    }

    /**
     * Generate a targeted follow-up question based on the original question
     * and the (incomplete) response.
     */
    private fun generateFollowUp(originalQuestion: String, response: String): String {
        return "Could you elaborate on: $originalQuestion. Your previous response was: '$response'. " +
            "Please provide more specific details and official documentation references."
    }

    // ==================== Result building ====================

    private suspend fun buildResult(
        session: ResearchSession,
        status: SessionStatus? = null,
    ): ResearchResult {
        sessionsLock.lock()
        try {
            val turns = session.turns.toList()
            val completed = turns.count { it.status == TurnStatus.COMPLETED }
            val failed = turns.count { it.status == TurnStatus.FAILED || it.status == TurnStatus.TIMED_OUT || it.status == TurnStatus.CANCELLED }

            val sourceUrls = turns.flatMap { it.sources }.distinct()
            val keyFindings = extractKeyFindings(turns)
            val uncertainties = extractUncertainties(turns)

            val finalStatus = status ?: when {
                failed > 0 && completed == 0 -> SessionStatus.FAILED
                failed > 0 -> SessionStatus.IN_PROGRESS
                turns.isEmpty() -> SessionStatus.IN_PROGRESS
                else -> SessionStatus.COMPLETED
            }

            return ResearchResult(
                taskId = session.taskId,
                objective = session.objective,
                researchQuestions = session.researchQuestions.toList(),
                turns = turns,
                keyFindings = keyFindings,
                uncertainties = uncertainties,
                sourceUrls = sourceUrls,
                status = finalStatus,
                totalTurns = turns.size,
                completedTurns = completed,
                failedTurns = failed,
                startedAt = session.startTime,
                completedAt = if (finalStatus == SessionStatus.COMPLETED ||
                    finalStatus == SessionStatus.FAILED ||
                    finalStatus == SessionStatus.CANCELLED) System.currentTimeMillis() else null,
                error = if (finalStatus == SessionStatus.FAILED) "One or more turns failed" else null,
            )
        } finally {
            sessionsLock.unlock()
        }
    }

    /** Extract likely key findings from completed turns (heuristic). */
    private fun extractKeyFindings(turns: List<ResearchTurn>): List<String> {
        return turns.filter { it.status == TurnStatus.COMPLETED && it.response != null }
            .map { turn ->
                val response = turn.response!!
                // Take first meaningful sentence(s)
                val sentences = response.split(Regex("[.!?]")).filter { it.length > 20 }
                sentences.firstOrNull() ?: response.take(200)
            }
    }

    /** Extract uncertainties from failed/incomplete turns. */
    private fun extractUncertainties(turns: List<ResearchTurn>): List<String> {
        val uncertainties = mutableListOf<String>()
        turns.filter { it.status != TurnStatus.COMPLETED }.forEach { turn ->
            uncertainties.add("Turn ${turn.turnNumber} ('${turn.question}'): ${turn.error ?: "No response"}")
        }
        turns.filter { it.status == TurnStatus.COMPLETED && it.response != null }.forEach { turn ->
            val response = turn.response!!
            if (response.lowercase().contains("uncertain") || response.lowercase().contains("unclear")) {
                uncertainties.add("Turn ${turn.turnNumber}: response contains uncertainty language")
            }
        }
        return uncertainties
    }

    /** Extract URLs from turn responses. */
    private fun extractSources(response: String): List<String> {
        if (response.isBlank()) return emptyList()
        val matcher = URL_PATTERN.matcher(response)
        val urls = mutableListOf<String>()
        while (matcher.find()) {
            urls.add(matcher.group())
        }
        return urls
    }

    companion object {
        private const val TAG_COMPANION = "ResearchCoordinator"
    }
}

/** Handle returned to the caller for an active research session. */
typealias ResearchSessionHandle = ResearchCoordinator.ResearchSessionHandle
