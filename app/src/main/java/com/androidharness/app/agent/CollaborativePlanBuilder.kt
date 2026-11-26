package com.androidharness.app.agent

import android.content.Context
import com.androidharness.app.llm.PerplexityConversationManager
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.regex.Pattern

/**
 * Phase 4 — Collaborative Plan Builder.
 *
 * Converts verified Perplexity research findings (ResearchResult) into a
 * structured, reviewable execution plan. The plan is purely descriptive —
 * no executable shell commands are generated as trusted instructions, and
 * no device modifications are performed.
 *
 * Provenance: every proposed step identifies which research finding or
 * source supports it. Documented facts, research-based recommendations,
 * and assumptions are clearly distinguished.
 *
 * Incomplete research produces a clearly marked DRAFT plan — never silently
 * treated as complete.
 */
class CollaborativePlanBuilder(
    private val context: Context,
    private val conversationManager: PerplexityConversationManager? = null,
) {

    private val URL_PATTERN = Pattern.compile(
        "https?://[^\\s<>\"]+",
        Pattern.CASE_INSENSITIVE,
    )

    // ==================== Enhanced plan models ====================

    /** Confidence level for a provenance source. */
    enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

    /** Step classification: fact vs recommendation vs assumption. */
    enum class StepClassification {
        DOCUMENTED_FACT,      // directly supported by research source
        RESEARCH_RECOMMENDATION, // inferred from research, not directly cited
        ASSUMPTION,           // not verified by research
    }

    /** Risk level of a step. */
    enum class RiskLevel { NONE, LOW, MEDIUM, HIGH }

    /** Reversibility of a step's effects. */
    enum class Reversibility { FULLY, PARTIALLY, NOT }

    /** Status of the overall plan. */
    enum class PlanStatus { DRAFT, NEEDS_REVIEW, COMPLETE, BLOCKED }

    /** Provenance for a step — what research/source supports it. */
    @Serializable
    data class PlanProvenance(
        val sourceType: String, // "research_finding", "documentation_url", "assumption", "recommendation"
        val sourceReference: String, // URL, turn number, or description
        val supportingQuote: String? = null,
        val confidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    )

    /** A single plan step with full provenance and risk metadata. */
    @Serializable
    data class PlanStep(
        val stepId: String,
        val phaseId: String,
        val stepNumber: Int,
        val description: String,
        val expectedOutcome: String,
        val requiredCapabilities: List<String> = emptyList(),
        val dependencies: List<String> = emptyList(), // stepIds this step depends on
        val classification: StepClassification = StepClassification.RESEARCH_RECOMMENDATION,
        val riskLevel: RiskLevel = RiskLevel.LOW,
        val reversibility: Reversibility = Reversibility.PARTIALLY,
        val verificationCriteria: List<String> = emptyList(),
        val provenance: List<PlanProvenance> = emptyList(),
        /** When true, this step is inert text only — never executable. */
        val isInert: Boolean = true,
    )

    /** An ordered phase containing related steps. */
    @Serializable
    data class PlanPhase(
        val phaseId: String,
        val phaseName: String,
        val phaseNumber: Int,
        val description: String,
        val steps: List<PlanStep> = emptyList(),
        val estimatedDurationMinutes: Int = 0,
    )

    /** Structured, reviewable execution plan produced from research findings. */
    @Serializable
    data class StructuredPlan(
        val planId: String,
        val researchSessionId: String,
        val objective: String,
        val phases: List<PlanPhase> = emptyList(),
        val steps: List<PlanStep> = emptyList(), // flat view of all steps
        val requiredCapabilities: List<String> = emptyList(),
        val assumptions: List<String> = emptyList(),
        val uncertainties: List<String> = emptyList(),
        val unresolvedQuestions: List<String> = emptyList(),
        val riskLevel: RiskLevel = RiskLevel.LOW,
        val verificationCriteria: List<String> = emptyList(),
        val sourceReferences: List<String> = emptyList(),
        val status: PlanStatus = PlanStatus.DRAFT,
        val createdAt: Long = System.currentTimeMillis(),
    )

    // ==================== Legacy backward-compatible model ====================

    /** Legacy ExecutionPlan retained for existing callers (PlanValidator, ExecutionPlanStore, etc.). */
    @Serializable
    data class ExecutionPlan(
        val planVersion: Int,
        val title: String,
        val steps: List<String>,
        val estimatedDurationMinutes: Int,
        val requiredCapabilities: List<String>,
        val autoExecutable: Boolean,
        val contextRefs: Map<String, String> = emptyMap(),
    ) {
        /** Convert this legacy plan to a StructuredPlan (lossy — provenance not preserved). */
        fun toStructuredPlan(
            planId: String,
            researchSessionId: String,
            objective: String,
        ): StructuredPlan {
            val planSteps = steps.mapIndexed { index, step ->
                PlanStep(
                    stepId = "legacy-step-$index",
                    phaseId = "legacy-phase-1",
                    stepNumber = index + 1,
                    description = step,
                    expectedOutcome = "Step completed",
                    requiredCapabilities = requiredCapabilities,
                    classification = StepClassification.ASSUMPTION,
                    riskLevel = RiskLevel.MEDIUM,
                    reversibility = Reversibility.PARTIALLY,
                    isInert = true,
                )
            }
            val phase = PlanPhase(
                phaseId = "legacy-phase-1",
                phaseName = "Execution Steps",
                phaseNumber = 1,
                description = title,
                steps = planSteps,
                estimatedDurationMinutes = estimatedDurationMinutes,
            )
            return StructuredPlan(
                planId = planId,
                researchSessionId = researchSessionId,
                objective = objective,
                phases = listOf(phase),
                steps = planSteps,
                requiredCapabilities = requiredCapabilities,
                status = PlanStatus.NEEDS_REVIEW,
                sourceReferences = contextRefs.values.toList(),
            )
        }
    }

    // ==================== Plan building ====================

    /**
     * Build a structured execution plan from a ResearchResult.
     *
     * If the research is incomplete (failed turns, unresolved questions,
     * missing sources), the plan is marked DRAFT with uncertainties visible.
     * Follow-up questions through the Perplexity bridge are suggested when
     * research gaps materially affect the plan.
     */
    fun buildPlan(
        researchResult: ResearchCoordinator.ResearchResult,
        agentCapabilities: List<String> = emptyList(),
    ): StructuredPlan {
        val planId = "plan-${UUID.randomUUID().toString()}"
        val sessionId = researchResult.taskId

        // Determine if research is sufficient for a complete plan
        val researchComplete = researchResult.status == ResearchCoordinator.SessionStatus.COMPLETED
            && researchResult.failedTurns == 0
            && researchResult.turns.any { it.status == ResearchCoordinator.TurnStatus.COMPLETED }

        val planStatus = if (researchComplete) PlanStatus.NEEDS_REVIEW else PlanStatus.DRAFT

        // Build phases from research turns
        val phases = buildPhases(researchResult)
        val flatSteps = phases.flatMap { it.steps }

        // Determine required capabilities from step content
        val requiredCaps = determineRequiredCapabilities(flatSteps, agentCapabilities)

        // Collect assumptions and uncertainties
        val allUncertainties = mutableListOf<String>()
        allUncertainties.addAll(researchResult.uncertainties)
        if (!researchComplete) {
            allUncertainties.add("Research session not complete — plan is based on partial findings")
        }
        if (researchResult.failedTurns > 0) {
            allUncertainties.add("${researchResult.failedTurns} research turn(s) failed or timed out")
        }

        val sourceUrls = researchResult.sourceUrls

        val plan = StructuredPlan(
            planId = planId,
            researchSessionId = sessionId,
            objective = researchResult.objective,
            phases = phases,
            steps = flatSteps,
            requiredCapabilities = requiredCaps,
            assumptions = extractAssumptions(researchResult),
            uncertainties = allUncertainties.distinct(),
            unresolvedQuestions = extractUnresolvedQuestions(researchResult),
            riskLevel = calculateOverallRisk(flatSteps),
            verificationCriteria = buildVerificationCriteria(flatSteps),
            sourceReferences = sourceUrls.distinct(),
            status = planStatus,
        )

        return plan
    }

    /**
     * Build plan phases from completed research turns.
     * Each completed turn becomes a phase; its response content becomes steps.
     */
    private fun buildPhases(
        researchResult: ResearchCoordinator.ResearchResult,
    ): List<PlanPhase> {
        val phases = mutableListOf<PlanPhase>()
        var phaseNumber = 0

        for (turn in researchResult.turns.filter { it.status == ResearchCoordinator.TurnStatus.COMPLETED }) {
            phaseNumber++
            val steps = extractStepsFromResponse(
                turn.response ?: "",
                turn.turnNumber,
                phaseNumber,
                researchResult.sourceUrls,
            )
            val phase = PlanPhase(
                phaseId = "phase-$phaseNumber",
                phaseName = "Research Turn ${turn.turnNumber}",
                phaseNumber = phaseNumber,
                description = turn.question,
                steps = steps,
                estimatedDurationMinutes = steps.size * 5, // rough estimate
            )
            phases.add(phase)
        }

        // If no completed turns, create a single empty phase so the plan isn't empty
        if (phases.isEmpty()) {
            phases.add(
                PlanPhase(
                    phaseId = "phase-1",
                    phaseName = "Research Findings",
                    phaseNumber = 1,
                    description = researchResult.objective,
                    steps = emptyList(),
                    estimatedDurationMinutes = 0,
                ),
            )
        }

        return phases
    }

    /**
     * Extract steps from a research response.
     * Heuristic: split into actionable items based on sentences/bullets.
     * All steps are marked inert — never executable.
     */
    private fun extractStepsFromResponse(
        response: String,
        turnNumber: Int,
        phaseNumber: Int,
        sourceUrls: List<String>,
    ): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        var stepNumber = 0

        // Split response into sentences or bullet items
        val items = response.split(Regex("[\\n•\\-]"))
            .map { it.trim() }
            .filter { it.length > 10 }

        if (items.isEmpty()) {
            // Single step from the whole response
            steps.add(
                PlanStep(
                    stepId = "step-turn$turnNumber-1",
                    phaseId = "phase-$phaseNumber",
                    stepNumber = 1,
                    description = response.take(500),
                    expectedOutcome = "Information gathered from Perplexity research",
                    classification = StepClassification.RESEARCH_RECOMMENDATION,
                    riskLevel = RiskLevel.LOW,
                    reversibility = Reversibility.FULLY,
                    verificationCriteria = listOf("Verify against official documentation"),
                    provenance = listOf(
                        PlanProvenance(
                            sourceType = "research_finding",
                            sourceReference = "Turn $turnNumber response",
                            supportingQuote = response.take(200),
                            confidence = ConfidenceLevel.MEDIUM,
                        ),
                    ),
                    isInert = true,
                ),
            )
            return steps
        }

        for ((index, item) in items.withIndex()) {
            stepNumber++
            val classification = classifyStep(item)
            val risk = assessRisk(item)
            val rev = assessReversibility(item)
            val caps = determineCapabilities(item)
            val prov = buildProvenance(item, turnNumber, sourceUrls)

            steps.add(
                PlanStep(
                    stepId = "step-turn$turnNumber-$stepNumber",
                    phaseId = "phase-$phaseNumber",
                    stepNumber = stepNumber,
                    description = item,
                    expectedOutcome = "Step ${stepNumber} of phase $phaseNumber completed",
                    requiredCapabilities = caps,
                    classification = classification,
                    riskLevel = risk,
                    reversibility = rev,
                    verificationCriteria = listOf("Cross-reference with official documentation"),
                    provenance = prov,
                    isInert = true,
                ),
            )
        }

        return steps
    }

    /** Classify a step as documented fact, research recommendation, or assumption. */
    private fun classifyStep(text: String): StepClassification {
        val lower = text.lowercase()
        if (lower.contains("official documentation") || lower.contains("developer.android.com")) {
            return StepClassification.DOCUMENTED_FACT
        }
        if (lower.contains("recommend") || lower.contains("suggest") || lower.contains("should")) {
            return StepClassification.RESEARCH_RECOMMENDATION
        }
        return StepClassification.ASSUMPTION
    }

    /** Assess risk level based on step content. */
    private fun assessRisk(text: String): RiskLevel {
        val lower = text.lowercase()
        if (lower.contains("delete") || lower.contains("rm ") || lower.contains("format") ||
            lower.contains("chmod 777") || lower.contains("root") || lower.contains("system")) {
            return RiskLevel.HIGH
        }
        if (lower.contains("install") || lower.contains("update") || lower.contains("configure")) {
            return RiskLevel.MEDIUM
        }
        return RiskLevel.LOW
    }

    /** Assess reversibility based on step content. */
    private fun assessReversibility(text: String): Reversibility {
        val lower = text.lowercase()
        if (lower.contains("delete") || lower.contains("format") || lower.contains("rm ")) {
            return Reversibility.NOT
        }
        if (lower.contains("install") || lower.contains("update")) {
            return Reversibility.PARTIALLY
        }
        return Reversibility.FULLY
    }

    /** Determine AndroidHarness capabilities referenced by a step. */
    private fun determineCapabilities(text: String): List<String> {
        val lower = text.lowercase()
        val caps = mutableListOf<String>()
        if (lower.contains("shell") || lower.contains("command") || lower.contains("adb")) {
            caps.add("shell")
        }
        if (lower.contains("pm ") || lower.contains("am ") || lower.contains("dumpsys")) {
            caps.add("android")
        }
        if (lower.contains("su") || lower.contains("root")) {
            caps.add("root")
        }
        if (lower.contains("input") || lower.contains("uiautomator") || lower.contains("click")) {
            caps.add("ui")
        }
        if (lower.contains("http") || lower.contains("url") || lower.contains("browser")) {
            caps.add("web")
        }
        return caps.distinct()
    }

    /** Determine required capabilities for all steps. */
    private fun determineRequiredCapabilities(
        steps: List<PlanStep>,
        agentCapabilities: List<String>,
    ): List<String> {
        val allCaps = steps.flatMap { it.requiredCapabilities }.distinct()
        return allCaps.filter { cap -> agentCapabilities.isEmpty() || agentCapabilities.contains(cap) }
    }

    /** Build provenance entries for a step based on its content and sources. */
    private fun buildProvenance(
        text: String,
        turnNumber: Int,
        sourceUrls: List<String>,
    ): List<PlanProvenance> {
        val provenances = mutableListOf<PlanProvenance>()

        // Reference the research turn
        provenances.add(
            PlanProvenance(
                sourceType = "research_finding",
                sourceReference = "Turn $turnNumber",
                supportingQuote = text.take(150),
                confidence = ConfidenceLevel.MEDIUM,
            ),
        )

        // Extract and reference any URLs found in the step text
        val matcher = URL_PATTERN.matcher(text)
        val urls = mutableListOf<String>()
        while (matcher.find()) {
            urls.add(matcher.group())
        }
        urls.forEach { url ->
            provenances.add(
                PlanProvenance(
                    sourceType = "documentation_url",
                    sourceReference = url,
                    supportingQuote = null,
                    confidence = ConfidenceLevel.HIGH,
                ),
            )
        }

        // Reference global source URLs
        sourceUrls.forEach { url ->
            if (!urls.contains(url)) {
                provenances.add(
                    PlanProvenance(
                        sourceType = "documentation_url",
                        sourceReference = url,
                        confidence = ConfidenceLevel.HIGH,
                    ),
                )
            }
        }

        return provenances
    }

    /** Extract assumptions from research result. */
    private fun extractAssumptions(
        researchResult: ResearchCoordinator.ResearchResult,
    ): List<String> {
        val assumptions = mutableListOf<String>()
        if (!researchResult.sourceUrls.isEmpty()) {
            assumptions.add("Perplexity-provided URLs are accessible and current")
        }
        if (researchResult.failedTurns > 0) {
            assumptions.add("Failed turns do not contain critical information needed for this plan")
        }
        assumptions.add("Research responses accurately reflect current Android documentation")
        return assumptions
    }

    /** Extract unresolved questions from research result. */
    private fun extractUnresolvedQuestions(
        researchResult: ResearchCoordinator.ResearchResult,
    ): List<String> {
        val questions = mutableListOf<String>()
        questions.addAll(researchResult.researchQuestions)
        researchResult.turns.filter { it.status != ResearchCoordinator.TurnStatus.COMPLETED }
            .forEach { turn ->
                questions.add("Unanswered: ${turn.question} (${turn.error ?: "no response"})")
            }
        return questions.distinct()
    }

    /** Calculate overall plan risk from step risks. */
    private fun calculateOverallRisk(steps: List<PlanStep>): RiskLevel {
        if (steps.any { it.riskLevel == RiskLevel.HIGH }) return RiskLevel.HIGH
        if (steps.any { it.riskLevel == RiskLevel.MEDIUM }) return RiskLevel.MEDIUM
        return RiskLevel.LOW
    }

    /** Build verification criteria from steps. */
    private fun buildVerificationCriteria(steps: List<PlanStep>): List<String> {
        val criteria = mutableListOf<String>()
        criteria.add("All steps reviewed by a human before any execution")
        criteria.add("Source URLs verified against official documentation")
        criteria.add("No step executed without explicit approval")
        steps.filter { it.riskLevel == RiskLevel.HIGH }
            .forEach { step ->
                criteria.add("High-risk step '${step.stepId}' requires additional review and rollback plan")
            }
        return criteria.distinct()
    }

    /**
     * Convert a StructuredPlan to the legacy ExecutionPlan for existing callers.
     * This is lossy — provenance and risk metadata are not preserved in the legacy format.
     */
    fun toLegacyExecutionPlan(
        structuredPlan: StructuredPlan,
        planVersion: Int = 1,
    ): ExecutionPlan {
        val stepDescriptions = structuredPlan.steps.map { step ->
            val riskTag = when (step.riskLevel) {
                RiskLevel.HIGH -> " [HIGH RISK]"
                RiskLevel.MEDIUM -> " [MEDIUM RISK]"
                else -> ""
            }
            val provTag = if (step.provenance.isNotEmpty()) " (source: ${step.provenance.first().sourceReference})" else ""
            "${step.stepNumber}. ${step.description}$riskTag$provTag"
        }

        return ExecutionPlan(
            planVersion = planVersion,
            title = structuredPlan.objective,
            steps = stepDescriptions,
            estimatedDurationMinutes = structuredPlan.phases.sumOf { it.estimatedDurationMinutes },
            requiredCapabilities = structuredPlan.requiredCapabilities,
            autoExecutable = false, // never auto-executable — always requires human review
            contextRefs = structuredPlan.sourceReferences.mapIndexed { idx, url -> "ref$idx" to url }.toMap(),
        )
    }

    /**
     * Suggest follow-up questions for Perplexity when research gaps materially
     * affect the plan. Returns a list of questions to ask via the bridge.
     */
    fun suggestFollowUpQuestions(
        researchResult: ResearchCoordinator.ResearchResult,
        structuredPlan: StructuredPlan,
    ): List<String> {
        val questions = mutableListOf<String>()

        if (researchResult.failedTurns > 0) {
            questions.add("Could you please provide information about the topics that failed in previous turns?")
        }

        if (structuredPlan.uncertainties.isNotEmpty()) {
            questions.add("Could you clarify the following uncertainties: ${structuredPlan.uncertainties.take(3).joinToString(", ")}?")
        }

        if (structuredPlan.sourceReferences.isEmpty()) {
            questions.add("Could you provide official documentation URLs to support these findings?")
        }

        structuredPlan.unresolvedQuestions.forEach { q ->
            questions.add("Follow-up on: $q")
        }

        return questions.distinct()
    }

    companion object {
        private const val TAG = "CollaborativePlanBuilder"
    }

    // ==================== Legacy backward-compatible API ====================

    /**
     * Legacy API retained for existing callers (AndroidHarnessExecutionController,
     * ExecutionPlanStore, PlanValidator).
     *
     * Builds a legacy ExecutionPlan from a ResearchResult. Delegates to the
     * new structured builder and converts back — provenance and risk metadata
     * are preserved where possible in step descriptions.
     */
    fun buildExecutionPlan(
        researchResult: ResearchCoordinator.ResearchResult,
        contextStore: ConversationContextStore,
        agentCapabilities: List<String>,
    ): ExecutionPlan {
        val structuredPlan = buildPlan(researchResult, agentCapabilities)
        return toLegacyExecutionPlan(structuredPlan, planVersion = 1)
    }

}
