package com.androidharness.app.agent

import android.util.Log
import com.androidharness.app.agent.CollaborativePlanBuilder.ExecutionPlan
import com.androidharness.app.agent.CollaborativePlanBuilder.PlanStep
import com.androidharness.app.agent.CollaborativePlanBuilder.StructuredPlan
import com.androidharness.app.agent.CollaborativePlanBuilder.PlanStatus
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Phase 5 — Plan Validator.
 *
 * Validates every generated plan before it can become approval-ready.
 * Validation is mandatory: a plan must pass (or pass with warnings) before
 * AndroidHarness presents it for execution approval.
 *
 * All findings are structured: severity, affected step ID, reason, and
 * recommended resolution. Perplexity output is treated as untrusted —
 * provenance and source references are verified, not assumed.
 */
class PlanValidator(
    private val agentCapabilities: List<String> = listOf("linux", "android", "shell", "ui", "web", "root"),
) {
    private val TAG = "PlanValidator"

    // ==================== Result schema ====================

    /** Severity of a validation finding. */
    enum class FindingSeverity { INFO, WARNING, ERROR, CRITICAL }

    /** Category of a validation finding. */
    enum class FindingCategory {
        STRUCTURE, ORDERING, DEPENDENCY, CAPABILITY, PROVENANCE,
        RISK, VERIFICATION, COMPLETENESS, INSTRUCTION, DUPLICATE, CONFLICT, IMPOSSIBLE,
    }

    /** A single structured validation finding. */
    @Serializable
    data class ValidationFinding(
        val id: String = UUID.randomUUID().toString(),
        val severity: FindingSeverity,
        val category: FindingCategory,
        val stepId: String?,           // null = plan-level finding
        val stepNumber: Int? = null,
        val reason: String,
        val recommendedResolution: String,
    )

    /** Structured validation result. */
    @Serializable
    data class PlanValidationResult(
        val planId: String,
        val status: ValidationStatus,
        val findings: List<ValidationFinding> = emptyList(),
        val errors: List<ValidationFinding> = emptyList(),
        val warnings: List<ValidationFinding> = emptyList(),
        val totalSteps: Int = 0,
        val validatedSteps: Int = 0,
        val capabilityCoverage: Double = 0.0,
        val provenanceCoverage: Double = 0.0,
        val validatedAt: Long = System.currentTimeMillis(),
    ) {
        val isApproved: Boolean get() = status == ValidationStatus.VALID || status == ValidationStatus.VALID_WITH_WARNINGS
    }

    enum class ValidationStatus { VALID, VALID_WITH_WARNINGS, REJECTED }

    // ==================== Validation entry point ====================

    /**
     * Validate a StructuredPlan before it can become approval-ready.
     *
     * Returns REJECTED if any CRITICAL/ERROR findings exist that block approval.
     * Returns VALID_WITH_WARNINGS if only non-blocking issues exist.
     * Returns VALID if the plan is clean.
     */
    fun validatePlan(plan: StructuredPlan): PlanValidationResult {
        Log.d(TAG, "Validating plan ${plan.planId} (${plan.steps.size} steps, ${plan.phases.size} phases)")

        val findings = mutableListOf<ValidationFinding>()

        // 1. Plan structure and required fields
        validateStructure(plan, findings)

        // 2. Step ordering and dependency integrity
        validateOrderingAndDependencies(plan, findings)

        // 3. Missing or circular dependencies
        validateCircularDependencies(plan, findings)

        // 4. Required AndroidHarness capabilities
        validateCapabilities(plan, findings)

        // 5. Research provenance and source references
        validateProvenance(plan, findings)

        // 6. Risk classification and reversibility
        validateRiskAndReversibility(plan, findings)

        // 7. Expected outcomes and verification criteria
        validateOutcomesAndVerification(plan, findings)

        // 8. Incomplete research, assumptions, unresolved uncertainties
        validateCompleteness(plan, findings)

        // 9. Unsupported or contradictory instructions
        validateInstructions(plan, findings)

        // 10. Duplicate, conflicting, or impossible steps
        validateDuplicatesAndConflicts(plan, findings)

        // Determine status
        val criticalFindings = findings.filter { it.severity == FindingSeverity.CRITICAL }
        val errorFindings = findings.filter { it.severity == FindingSeverity.ERROR }
        val warningFindings = findings.filter { it.severity == FindingSeverity.WARNING }

        val status = when {
            criticalFindings.isNotEmpty() || errorFindings.isNotEmpty() -> ValidationStatus.REJECTED
            warningFindings.isNotEmpty() -> ValidationStatus.VALID_WITH_WARNINGS
            else -> ValidationStatus.VALID
        }

        val errors = findings.filter { it.severity == FindingSeverity.ERROR || it.severity == FindingSeverity.CRITICAL }
        val warnings = findings.filter { it.severity == FindingSeverity.WARNING }

        val totalSteps = plan.steps.size
        val validatedSteps = plan.steps.count { it.description.isNotBlank() }
        val stepsWithProvenance = plan.steps.count { it.provenance.isNotEmpty() }
        val capabilityCoverage = if (totalSteps > 0) validatedSteps.toDouble() / totalSteps else 1.0
        val provenanceCoverage = if (totalSteps > 0) stepsWithProvenance.toDouble() / totalSteps else 1.0

        return PlanValidationResult(
            planId = plan.planId,
            status = status,
            findings = findings,
            errors = errors,
            warnings = warnings,
            totalSteps = totalSteps,
            validatedSteps = validatedSteps,
            capabilityCoverage = capabilityCoverage,
            provenanceCoverage = provenanceCoverage,
        )
    }

    // ==================== Individual validation checks ====================

    private fun validateStructure(plan: StructuredPlan, findings: MutableList<ValidationFinding>) {
        if (plan.planId.isBlank()) {
            findings.add(ValidationFinding(
                severity = FindingSeverity.CRITICAL,
                category = FindingCategory.STRUCTURE,
                stepId = null,
                reason = "Plan has no planId",
                recommendedResolution = "Generate a unique plan ID before validation",
            ))
        }
        if (plan.objective.isBlank()) {
            findings.add(ValidationFinding(
                severity = FindingSeverity.CRITICAL,
                category = FindingCategory.STRUCTURE,
                stepId = null,
                reason = "Plan has no objective",
                recommendedResolution = "Provide the original research objective",
            ))
        }
        if (plan.phases.isEmpty() && plan.steps.isEmpty()) {
            findings.add(ValidationFinding(
                severity = FindingSeverity.ERROR,
                category = FindingCategory.STRUCTURE,
                stepId = null,
                reason = "Plan contains no phases or steps",
                recommendedResolution = "Add at least one execution phase with steps",
            ))
        }
        if (plan.status == PlanStatus.DRAFT) {
            findings.add(ValidationFinding(
                severity = FindingSeverity.WARNING,
                category = FindingCategory.COMPLETENESS,
                stepId = null,
                reason = "Plan is in DRAFT status — research may be incomplete",
                recommendedResolution = "Complete research or explicitly approve the draft plan",
            ))
        }
    }

    private fun validateOrderingAndDependencies(plan: StructuredPlan, findings: MutableList<ValidationFinding>) {
        val stepIds = plan.steps.map { it.stepId }.toSet()
        val stepMap = plan.steps.associateBy { it.stepId }

        plan.steps.forEach { step ->
            // Step numbers should be positive
            if (step.stepNumber <= 0) {
                findings.add(ValidationFinding(
                    severity = FindingSeverity.ERROR,
                    category = FindingCategory.ORDERING,
                    stepId = step.stepId,
                    stepNumber = step.stepNumber,
                    reason = "Step has invalid step number ${step.stepNumber}",
                    recommendedResolution = "Assign a positive, sequential step number",
                ))
            }

            // Dependencies must reference existing steps
            step.dependencies.forEach { depId ->
                if (depId !in stepIds) {
                    findings.add(ValidationFinding(
                        severity = FindingSeverity.ERROR,
                        category = FindingCategory.DEPENDENCY,
                        stepId = step.stepId,
                        stepNumber = step.stepNumber,
                        reason = "Step depends on unknown step '$depId'",
                        recommendedResolution = "Remove the dependency or add the missing step",
                    ))
                } else {
                    // Dependency should come before this step (lower step number in same phase, or earlier phase)
                    val dep = stepMap[depId]!!
                    if (dep.phaseId == step.phaseId && dep.stepNumber >= step.stepNumber) {
                        findings.add(ValidationFinding(
                            severity = FindingSeverity.ERROR,
                            category = FindingCategory.ORDERING,
                            stepId = step.stepId,
                            stepNumber = step.stepNumber,
                            reason = "Step depends on step '$depId' that is not ordered before it",
                            recommendedResolution = "Reorder steps so the dependency precedes this step",
                        ))
                    }
                }
            }

            // Empty description
            if (step.description.isBlank()) {
                findings.add(ValidationFinding(
                    severity = FindingSeverity.ERROR,
                    category = FindingCategory.STRUCTURE,
                    stepId = step.stepId,
                    stepNumber = step.stepNumber,
                    reason = "Step has no description",
                    recommendedResolution = "Provide a descriptive step description",
                ))
            }
        }
    }

    private fun validateCircularDependencies(plan: StructuredPlan, findings: MutableList<ValidationFinding>) {
        val stepMap = plan.steps.associateBy { it.stepId }
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()

        fun dfs(stepId: String, path: MutableList<String>) {
            if (stepId in inStack) {
                // Found a cycle
                val cycleStart = path.indexOf(stepId)
                val cycle = path.subList(cycleStart, path.size) + stepId
                findings.add(ValidationFinding(
                    severity = FindingSeverity.CRITICAL,
                    category = FindingCategory.DEPENDENCY,
                    stepId = stepId,
                    reason = "Circular dependency detected: ${cycle.joinToString(" -> ")}",
                    recommendedResolution = "Remove one dependency in the cycle to break the loop",
                ))
                return
            }
            if (stepId in visited) return

            visited.add(stepId)
            inStack.add(stepId)
            path.add(stepId)

            val step = stepMap[stepId] ?: return
            step.dependencies.forEach { dfs(it, path) }

            path.removeAt(path.size - 1)
            inStack.remove(stepId)
        }

        plan.steps.forEach { dfs(it.stepId, mutableListOf()) }
    }

    private fun validateCapabilities(plan: StructuredPlan, findings: MutableList<ValidationFinding>) {
        val availableCaps = agentCapabilities.toSet()

        plan.steps.forEach { step ->
            step.requiredCapabilities.forEach { cap ->
                if (cap !in availableCaps) {
                    findings.add(ValidationFinding(
                        severity = FindingSeverity.ERROR,
                        category = FindingCategory.CAPABILITY,
                        stepId = step.stepId,
                        stepNumber = step.stepNumber,
                        reason = "Step requires unavailable capability '$cap'",
                        recommendedResolution = "Remove the capability requirement or add the capability to AndroidHarness",
                    ))
                }
            }
        }

        // Plan-level capability coverage
        val allRequired = plan.steps.flatMap { it.requiredCapabilities }.distinct()
        val missing = allRequired.filter { it !in availableCaps }
        if (missing.isNotEmpty()) {
            findings.add(ValidationFinding(
                severity = FindingSeverity.WARNING,
                category = FindingCategory.CAPABILITY,
                stepId = null,
                reason = "Plan requires capabilities not available: ${missing.joinToString(", ")}",
                recommendedResolution = "Install missing capabilities or revise the plan to avoid them",
            ))
        }
    }

    private fun validateProvenance(plan: StructuredPlan, findings: MutableList<ValidationFinding>) {
        val stepsWithoutProvenance = plan.steps.filter { it.provenance.isEmpty() }
        if (stepsWithoutProvenance.isNotEmpty()) {
            stepsWithoutProvenance.forEach { step ->
                findings.add(ValidationFinding(
                    severity = FindingSeverity.WARNING,
                    category = FindingCategory.PROVENANCE,
                    stepId = step.stepId,
                    stepNumber = step.stepNumber,
                    reason = "Step has no research provenance — source cannot be verified",
                    recommendedResolution = "Add a source reference or mark the step as an assumption",
                ))
            }
        }

        // Check for untrusted instruction patterns in step descriptions
        val untrustedPatterns = listOf("rm -rf", "chmod 777", "format", "dd if=", "mkfs", "> /dev/sd")
        plan.steps.forEach { step ->
            untrustedPatterns.forEach { pattern ->
                if (step.description.lowercase().contains(pattern)) {
                    findings.add(ValidationFinding(
                        severity = FindingSeverity.CRITICAL,
                        category = FindingCategory.INSTRUCTION,
                        stepId = step.stepId,
                        stepNumber = step.stepNumber,
                        reason = "Step contains potentially destructive instruction pattern: '$pattern'",
                        recommendedResolution = "Remove or neutralize the destructive instruction — steps must remain inert text",
                    ))
                }
            }
        }
    }

    private fun validateRiskAndReversibility(plan: StructuredPlan, findings: MutableList<ValidationFinding>) {
        val highRiskSteps = plan.steps.filter { it.riskLevel == CollaborativePlanBuilder.RiskLevel.HIGH }
        if (highRiskSteps.isNotEmpty()) {
            highRiskSteps.forEach { step ->
                val reversible = step.reversibility == CollaborativePlanBuilder.Reversibility.FULLY
                findings.add(ValidationFinding(
                    severity = if (reversible) FindingSeverity.WARNING else FindingSeverity.ERROR,
                    category = FindingCategory.RISK,
                    stepId = step.stepId,
                    stepNumber = step.stepNumber,
                    reason = "High-risk step '${step.stepId}' with reversibility=${step.reversibility}",
                    recommendedResolution = if (reversible) {
                        "Ensure a verified rollback procedure exists before approval"
                    } else {
                        "Revise the step to be fully reversible or reduce risk"
                    },
                ))
            }
        }
    }

    private fun validateOutcomesAndVerification(plan: StructuredPlan, findings: MutableList<ValidationFinding>) {
        plan.steps.forEach { step ->
            if (step.expectedOutcome.isBlank()) {
                findings.add(ValidationFinding(
                    severity = FindingSeverity.WARNING,
                    category = FindingCategory.VERIFICATION,
                    stepId = step.stepId,
                    stepNumber = step.stepNumber,
                    reason = "Step has no expected outcome defined",
                    recommendedResolution = "Define what successful completion looks like",
                ))
            }
            if (step.verificationCriteria.isEmpty()) {
                findings.add(ValidationFinding(
                    severity = FindingSeverity.WARNING,
                    category = FindingCategory.VERIFICATION,
                    stepId = step.stepId,
                    stepNumber = step.stepNumber,
                    reason = "Step has no verification criteria",
                    recommendedResolution = "Add criteria to verify step completion",
                ))
            }
        }
    }

    private fun validateCompleteness(plan: StructuredPlan, findings: MutableList<ValidationFinding>) {
        if (plan.uncertainties.isNotEmpty()) {
            findings.add(ValidationFinding(
                severity = FindingSeverity.WARNING,
                category = FindingCategory.COMPLETENESS,
                stepId = null,
                reason = "Plan has ${plan.uncertainties.size} unresolved uncertainty(ies)",
                recommendedResolution = "Resolve uncertainties through follow-up research or document them as accepted risks",
            ))
        }
        if (plan.unresolvedQuestions.isNotEmpty()) {
            findings.add(ValidationFinding(
                severity = FindingSeverity.WARNING,
                category = FindingCategory.COMPLETENESS,
                stepId = null,
                reason = "Plan has ${plan.unresolvedQuestions.size} unresolved question(s)",
                recommendedResolution = "Answer questions via Perplexity follow-up or mark as out of scope",
            ))
        }
        if (plan.assumptions.isNotEmpty()) {
            findings.add(ValidationFinding(
                severity = FindingSeverity.INFO,
                category = FindingCategory.COMPLETENESS,
                stepId = null,
                reason = "Plan has ${plan.assumptions.size} documented assumption(s)",
                recommendedResolution = "Verify assumptions against official documentation",
            ))
        }
    }

    private fun validateInstructions(plan: StructuredPlan, findings: MutableList<ValidationFinding>) {
        // Detect executable command patterns that should remain inert
        val commandPatterns = listOf("su -c", "pm ", "am ", "dumpsys ", "input ", "chmod ", "chown ", "mount ")
        plan.steps.forEach { step ->
            commandPatterns.forEach { pattern ->
                if (step.description.contains(pattern, ignoreCase = true)) {
                    findings.add(ValidationFinding(
                        severity = FindingSeverity.WARNING,
                        category = FindingCategory.INSTRUCTION,
                        stepId = step.stepId,
                        stepNumber = step.stepNumber,
                        reason = "Step resembles an executable command: '$pattern'",
                        recommendedResolution = "Rewrite as descriptive text only — do not generate executable commands",
                    ))
                }
            }
        }

        // Check for contradictory instructions within the plan
        val negativeTerms = listOf("do not", "never", "avoid", "don't")
        val positiveTerms = listOf("must", "always", "ensure", "required")
        plan.steps.forEach { step ->
            val lower = step.description.lowercase()
            val hasNegative = negativeTerms.any { lower.contains(it) }
            val hasPositive = positiveTerms.any { lower.contains(it) }
            if (hasNegative && hasPositive) {
                findings.add(ValidationFinding(
                    severity = FindingSeverity.WARNING,
                    category = FindingCategory.CONFLICT,
                    stepId = step.stepId,
                    stepNumber = step.stepNumber,
                    reason = "Step contains contradictory positive and negative instructions",
                    recommendedResolution = "Clarify whether the action is required or prohibited",
                ))
            }
        }
    }

    private fun validateDuplicatesAndConflicts(plan: StructuredPlan, findings: MutableList<ValidationFinding>) {
        // Duplicate descriptions
        val descToSteps = plan.steps.groupBy { it.description.lowercase() }
        descToSteps.filter { it.value.size > 1 }.forEach { (desc, steps) ->
            steps.forEach { step ->
                findings.add(ValidationFinding(
                    severity = FindingSeverity.WARNING,
                    category = FindingCategory.DUPLICATE,
                    stepId = step.stepId,
                    stepNumber = step.stepNumber,
                    reason = "Duplicate step description: '$desc'",
                    recommendedResolution = "Remove the duplicate step or differentiate the descriptions",
                ))
            }
        }

        // Impossible steps (very long descriptions that look like pasted content)
        plan.steps.forEach { step ->
            if (step.description.length > 2000) {
                findings.add(ValidationFinding(
                    severity = FindingSeverity.WARNING,
                    category = FindingCategory.IMPOSSIBLE,
                    stepId = step.stepId,
                    stepNumber = step.stepNumber,
                    reason = "Step description is unusually long (${step.description.length} chars) — may be pasted content",
                    recommendedResolution = "Summarize the step into a concise description",
                ))
            }
        }
    }

    companion object {
        private const val TAG_COMPANION = "PlanValidator"
    }
}
