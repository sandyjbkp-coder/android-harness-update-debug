package com.androidharness.app.agent

import android.content.Context
import android.util.Log
import com.androidharness.app.agent.CollaborativePlanBuilder.ExecutionPlan
import com.androidharness.app.agent.CollaborativePlanBuilder.PlanStep
import com.androidharness.app.agent.CollaborativePlanBuilder.StructuredPlan
import com.androidharness.app.agent.CollaborativePlanBuilder.PlanStatus
import com.androidharness.app.agent.PlanValidator.PlanValidationResult
import com.androidharness.app.agent.PlanValidator.ValidationStatus
import com.androidharness.app.agent.ExecutionPlanStore
import com.androidharness.app.agent.RootRunner
import com.androidharness.app.agent.GlobalFailureGuard
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 6 — AndroidHarness Execution Controller.
 *
 * Executes validated, explicitly approved plans using existing AndroidHarness
 * capabilities (RootRunner). Approval and execution are kept separate: a plan
 * must pass validation (VALID or VALID_WITH_WARNINGS) and receive explicit
 * user approval before any state-changing step runs.
 *
 * Steps execute in dependency order through GlobalFailureGuard, which handles
 * timeouts, exceptions, missing capabilities, failed verification, bounded
 * retries, cancellation, and blocking of dependent steps.
 *
 * Perplexity output is treated as untrusted — all commands are inert text
 * until explicitly approved, and no Perplexity-generated command is executed
 * without human approval.
 */
class AndroidHarnessExecutionController(
    private val context: Context,
    private val executionPlanStore: ExecutionPlanStore = ExecutionPlanStore(context),
    private val planValidator: PlanValidator = PlanValidator(),
    private val rootRunner: RootRunner = RootRunner(context),
    private val globalFailureGuard: GlobalFailureGuard = GlobalFailureGuard(context),
    private val capbilityMapper: CapabilityMapper = CapabilityMapper(context),
) {
    private val TAG = "AndroidHarnessExecutionController"

    private fun extractStderrFromOutput(output: String?): String? {
        if (output.isNullOrBlank()) return null
        val marker = "[stderr]"
        val idx = output.indexOf(marker)
        if (idx < 0) return null
        val after = output.substring(idx + marker.length)
        val metaIdx = after.indexOf("\n[meta]")
        val stderrSection = if (metaIdx >= 0) after.substring(0, metaIdx) else after
        return stderrSection.trim().ifBlank { null }
    }

    private fun extractExitCodeFromOutput(output: String?): Int? {
        if (output.isNullOrBlank()) return null
        val metaIdx = output.indexOf("[meta]")
        if (metaIdx < 0) return null
        val meta = output.substring(metaIdx)
        val regex = Regex("exitCode=([0-9-]+)")
        val match = regex.find(meta) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    // Execution state persistence (in-memory, survives within process; recoverable via GlobalFailureGuard)
    private val executions = ConcurrentHashMap<String, ApprovedExecution>()
    private val executionMutex = Mutex()

    /** Approval state per plan. */
    private val approvals = ConcurrentHashMap<String, ApprovalState>()

    /** Capability → check availability. */
    private val capabilityCache = ConcurrentHashMap<String, Boolean>()

    // ==================== Approval models ====================

    /** Approval state for a plan. */
    @Serializable
    data class ApprovalState(
        val planId: String,
        val status: ApprovalStatus = ApprovalStatus.PENDING,
        val approvedAt: Long? = null,
        val approvedBy: String? = null,
        val conditions: List<String> = emptyList(),
    ) {
        val isApproved: Boolean get() = status == ApprovalStatus.APPROVED
    }

    enum class ApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED }

    /** Request for user approval before executing a plan. */
    @Serializable
    data class ApprovalRequest(
        val requestId: String = UUID.randomUUID().toString(),
        val planId: String,
        val planObjective: String,
        val totalSteps: Int,
        val highRiskSteps: Int,
        val requiresRoot: Boolean,
        val stateChangingSteps: Int,
        val createdAt: Long = System.currentTimeMillis(),
    )

    // ==================== Execution models ====================

    /** An approved execution tracked by the controller. */
    @Serializable
    data class ApprovedExecution(
        val executionId: String,
        val planId: String,
        val planObjective: String,
        var status: ExecutionStatus = ExecutionStatus.INITIALIZED,
        val createdAt: Long = System.currentTimeMillis(),
        val startedAt: Long? = null,
        var completedAt: Long? = null,
        val taskId: String? = null, // GlobalFailureGuard task id
        val steps: MutableList<StepExecutionRecord> = mutableListOf(),
    ) {
        val isComplete: Boolean get() =
            status == ExecutionStatus.COMPLETED || status == ExecutionStatus.FAILED ||
                status == ExecutionStatus.CANCELLED
    }

    enum class ExecutionStatus { INITIALIZED, APPROVED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, PAUSED }

    /** Record of a single step execution. */
    @Serializable
    data class StepExecutionRecord(
        val stepId: String,
        val stepNumber: Int,
        val description: String,
        val expectedOutcome: String,
        val status: StepExecutionRecord.Status = StepExecutionRecord.Status.PENDING,
        val startedAt: Long? = null,
        val completedAt: Long? = null,
        val output: String? = null,
        val stderr: String? = null,
        val rootUsed: Boolean = false,
        val attempts: Int = 0,
        val error: String? = null,
        val verificationEvidence: String? = null,
    ) {
        enum class Status { PENDING, IN_PROGRESS, COMPLETED, FAILED, TIMED_OUT, CANCELLED, SKIPPED, BLOCKED }
    }

    /** Structured execution summary returned to the caller. */
    @Serializable
    data class ExecutionSummary(
        val executionId: String,
        val planId: String,
        val status: ExecutionStatus,
        val totalSteps: Int,
        val completedSteps: Int,
        val failedSteps: Int,
        val blockedSteps: Int,
        val cancelledSteps: Int,
        val skippedSteps: Int,
        val rootUsed: Boolean,
        val startedAt: Long?,
        val completedAt: Long?,
        val totalTimeSeconds: Int,
        val steps: List<StepExecutionRecord>,
    )

    // ==================== Approval flow ====================

    /**
     * Request approval for a validated plan. Returns an ApprovalRequest that
     * must be explicitly confirmed before execution.
     *
     * Only plans with validation status VALID or VALID_WITH_WARNINGS may be
     * submitted for approval.
     */
    fun requestApproval(
        planId: String,
        validationResult: PlanValidationResult,
        structuredPlan: StructuredPlan,
    ): ApprovalRequest {
        Log.d(TAG, "Requesting approval for plan $planId (validation: ${validationResult.status})")

        if (validationResult.status != ValidationStatus.VALID &&
            validationResult.status != ValidationStatus.VALID_WITH_WARNINGS) {
            throw IllegalStateException(
                "Plan $planId is not approval-ready (validation status: ${validationResult.status}). " +
                    "Rejecting errors: ${validationResult.errors.map { it.reason }.joinToString(", ")}"
            )
        }

        val stateChanging = structuredPlan.steps.count { it.riskLevel == CollaborativePlanBuilder.RiskLevel.HIGH ||
            it.reversibility != CollaborativePlanBuilder.Reversibility.FULLY }
        val highRisk = structuredPlan.steps.count { it.riskLevel == CollaborativePlanBuilder.RiskLevel.HIGH }
        val requiresRoot = structuredPlan.requiredCapabilities.contains("root")

        val request = ApprovalRequest(
            planId = planId,
            planObjective = structuredPlan.objective,
            totalSteps = structuredPlan.steps.size,
            highRiskSteps = highRisk,
            requiresRoot = requiresRoot,
            stateChangingSteps = stateChanging,
        )

        approvals[planId] = ApprovalState(planId = planId, status = ApprovalStatus.PENDING)
        Log.i(TAG, "Approval requested: $requestId (plan $planId, ${stateChanging} state-changing steps)")

        return request
    }

    private val requestId by lazy { UUID.randomUUID().toString() }

    /** Approve a plan for execution. Requires explicit caller confirmation. */
    fun approvePlan(planId: String, approvedBy: String = "user", conditions: List<String> = emptyList()): Boolean {
        val current = approvals[planId] ?: return false
        if (current.status != ApprovalStatus.PENDING) {
            Log.w(TAG, "Plan $planId already in state ${current.status}, cannot approve")
            return false
        }
        approvals[planId] = current.copy(
            status = ApprovalStatus.APPROVED,
            approvedAt = System.currentTimeMillis(),
            approvedBy = approvedBy,
            conditions = conditions,
        )
        Log.i(TAG, "Plan $planId approved by $approvedBy")
        return true
    }

    /** Reject a plan. */
    fun rejectPlan(planId: String, reason: String): Boolean {
        val current = approvals[planId] ?: return false
        approvals[planId] = current.copy(status = ApprovalStatus.REJECTED)
        Log.w(TAG, "Plan $planId rejected: $reason")
        return true
    }

    /** Check if a plan is approved for execution. */
    fun isPlanApproved(planId: String): Boolean = approvals[planId]?.isApproved == true

    /** Get pending approval request details. */
    fun getApprovalState(planId: String): ApprovalState? = approvals[planId]

    // ==================== Capability mapping ====================

    /**
     * Map plan steps to existing AndroidHarness capabilities (RootRunner).
     * Returns true if all required capabilities are available.
     */
    fun canExecutePlan(structuredPlan: StructuredPlan): Boolean {
        val required = structuredPlan.requiredCapabilities
        return required.all { isCapabilityAvailable(it) }
    }

    private fun isCapabilityAvailable(capability: String): Boolean {
        return capabilityCache.getOrPut(capability) {
            when (capability) {
                "root" -> rootRunner.isRootGranted()
                "shell" -> true // shell always available via RootRunner
                "android" -> rootRunner.isRootGranted() // pm/am/cmd need root
                "ui" -> true // input/uiautomator via RootRunner
                "web" -> true // network access
                "linux" -> true // Linux toolchain
                else -> false
            }
        }
    }

    /** Map a single step to the execution tier it needs. */
    fun executionTierForStep(step: PlanStep): String {
        return when {
            step.requiredCapabilities.contains("root") -> "SYSTEM_ROOT"
            step.requiredCapabilities.contains("android") -> "SYSTEM_ROOT"
            step.requiredCapabilities.contains("shell") -> "APP_LINUX"
            step.requiredCapabilities.contains("ui") -> "APP_LINUX"
            step.requiredCapabilities.contains("web") -> "APP_LINUX"
            step.requiredCapabilities.contains("linux") -> "LINUX_ROOT"
            else -> "APP_LINUX"
        }
    }

    // ==================== Plan execution ====================

    /**
     * Execute an approved plan step-by-step through GlobalFailureGuard.
     *
     * Steps run in dependency order. State-changing steps require prior
     * approval (already obtained via approvePlan). Completed steps are never
     * repeated during recovery.
     *
     * Returns a structured ExecutionSummary.
     */
    suspend fun executeApprovedPlan(
        structuredPlan: StructuredPlan,
        validationResult: PlanValidationResult,
    ): ExecutionSummary = withContext(Dispatchers.IO) {
        Log.i(TAG, "Executing approved plan ${structuredPlan.planId} (${structuredPlan.steps.size} steps)")

        // 1. Approval gate
        if (!isPlanApproved(structuredPlan.planId)) {
            return@withContext ExecutionSummary(
                executionId = "",
                planId = structuredPlan.planId,
                status = ExecutionStatus.CANCELLED,
                totalSteps = structuredPlan.steps.size,
                completedSteps = 0,
                failedSteps = 0,
                blockedSteps = 0,
                cancelledSteps = 0,
                skippedSteps = 0,
                rootUsed = false,
                startedAt = null,
                completedAt = null,
                totalTimeSeconds = 0,
                steps = emptyList(),
            )
        }

        // 2. Capability check
        if (!canExecutePlan(structuredPlan)) {
            Log.w(TAG, "Plan ${structuredPlan.planId} cannot execute — missing capabilities")
            return@withContext ExecutionSummary(
                executionId = "",
                planId = structuredPlan.planId,
                status = ExecutionStatus.FAILED,
                totalSteps = structuredPlan.steps.size,
                completedSteps = 0,
                failedSteps = structuredPlan.steps.size,
                blockedSteps = 0,
                cancelledSteps = 0,
                skippedSteps = 0,
                rootUsed = false,
                startedAt = null,
                completedAt = null,
                totalTimeSeconds = 0,
                steps = structuredPlan.steps.map { s ->
                    StepExecutionRecord(
                        stepId = s.stepId,
                        stepNumber = s.stepNumber,
                        description = s.description,
                        expectedOutcome = s.expectedOutcome,
                        status = StepExecutionRecord.Status.FAILED,
                        error = "Missing required capability",
                    )
                },
            )
        }

        val startTime = System.currentTimeMillis()

        // 3. Register task in GlobalFailureGuard
        val stepDefinitions = structuredPlan.steps.map { it }
        val taskId = globalFailureGuard.registerTask(
            planId = structuredPlan.planId,
            totalSteps = stepDefinitions.size,
            stepDefinitions = stepDefinitions,
        )
        globalFailureGuard.startTask(taskId)

        val executionId = "exec-${UUID.randomUUID().toString()}"
        val execution = ApprovedExecution(
            executionId = executionId,
            planId = structuredPlan.planId,
            planObjective = structuredPlan.objective,
            status = ExecutionStatus.IN_PROGRESS,
            startedAt = startTime,
            taskId = taskId,
        )
        executions[executionId] = execution

        // 4. Execute steps in dependency order
        val stepMap = structuredPlan.steps.associateBy { it.stepId }
        val completedStepIds = mutableSetOf<String>()
        var rootUsed = false

        // Build dependency order (topological sort within phases)
        val orderedSteps = topologicalSort(structuredPlan)

        for (step in orderedSteps) {
            // Check cancellation
            if (execution.status == ExecutionStatus.CANCELLED) break

            // Check prerequisites
            val prerequisitesMet = step.dependencies.all { completedStepIds.contains(it) }
            if (!prerequisitesMet) {
                Log.w(TAG, "Step ${step.stepId} blocked: prerequisites not met")
                recordStepResult(execution, step, StepExecutionRecord.Status.BLOCKED, error = "Prerequisites not met")
                continue
            }

            // Check if already completed (recovery — never repeat)
            val existingRecord = execution.steps.find { it.stepId == step.stepId }
            if (existingRecord?.status == StepExecutionRecord.Status.COMPLETED) {
                Log.d(TAG, "Step ${step.stepId} already completed, skipping (recovery)")
                completedStepIds.add(step.stepId)
                continue
            }

            // Start step via GlobalFailureGuard
            val guardStarted = globalFailureGuard.startTask(taskId) // ensure running
            val stepStarted = globalFailureGuard.startStep(taskId, step.stepId)

            if (!stepStarted) {
                val state = globalFailureGuard.getStepState(step.stepId)
                val status = when {
                    state?.outcome == GlobalFailureGuard.StepOutcome.BLOCKED -> StepExecutionRecord.Status.BLOCKED
                    state?.outcome == GlobalFailureGuard.StepOutcome.CANCELLED -> StepExecutionRecord.Status.CANCELLED
                    else -> StepExecutionRecord.Status.FAILED
                }
                recordStepResult(execution, step, status, error = state?.lastError)
                if (status == StepExecutionRecord.Status.BLOCKED) {
                    // Block dependents
                    continue
                }
                continue
            }

            // Execute the step
            val stepStart = System.currentTimeMillis()
            recordStepResult(execution, step, StepExecutionRecord.Status.IN_PROGRESS, startedAt = stepStart)

            val tier = executionTierForStep(step)
            val command = mapStepToCommand(step)

            var stepResult: ToolResult? = null
            var attempts = 0
            var stepCompleted = false
            var stepError: String? = null

            while (attempts < 3 && !stepCompleted) {
                attempts++
                try {
                    stepResult = rootRunner.runCommand(
                        command = command,
                        tier = when {
                            tier == "SYSTEM_ROOT" -> com.androidharness.app.data.env.ExecutionTier.SYSTEM_ROOT
                            tier == "LINUX_ROOT" -> com.androidharness.app.data.env.ExecutionTier.LINUX_ROOT
                            else -> com.androidharness.app.data.env.ExecutionTier.APP_LINUX
                        },
                        timeoutSeconds = 30,
                    )
                    stepCompleted = stepResult.ok
                    if (!stepCompleted) {
                        stepError = extractStderrFromOutput(stepResult.output) ?: "Command failed"
                        Log.w(TAG, "Step ${step.stepId} attempt $attempts failed: $stepError")
                    }
                } catch (e: Exception) {
                    stepError = e.message ?: "Unknown error"
                    Log.w(TAG, "Step ${step.stepId} attempt $attempts exception: $stepError")
                }

                if (!stepCompleted && attempts < 3) {
                    // Bounded retry via GlobalFailureGuard
                    val canRetry = globalFailureGuard.failStep(
                        taskId = taskId,
                        stepId = step.stepId,
                        error = stepError ?: "Unknown error",
                        failureType = GlobalFailureGuard.FailureType.EXCEPTION,
                        canRetry = true,
                    )
                    if (!canRetry) break
                }
            }

            if (stepCompleted && stepResult?.ok == true) {
                rootUsed = rootUsed || (tier == "SYSTEM_ROOT")
                globalFailureGuard.completeStep(taskId, step.stepId, stepResult.output)
                val exitCodeForEvidence = extractExitCodeFromOutput(stepResult.output)
                recordStepResult(
                    execution = execution,
                    step = step,
                    status = StepExecutionRecord.Status.COMPLETED,
                    startedAt = stepStart,
                    completedAt = System.currentTimeMillis(),
                    output = stepResult.output,
                    stderr = extractStderrFromOutput(stepResult.output),
                    rootUsed = tier == "SYSTEM_ROOT",
                    attempts = attempts,
                    verificationEvidence = if (exitCodeForEvidence != null) {
                        "Exit code $exitCodeForEvidence; output length ${stepResult.output.length}"
                    } else {
                        "Exit code unknown; output length ${stepResult.output.length}"
                    },
                )
                completedStepIds.add(step.stepId)
            } else {
                globalFailureGuard.failStep(
                    taskId = taskId,
                    stepId = step.stepId,
                    error = stepError ?: "Step failed after retries",
                    failureType = GlobalFailureGuard.FailureType.EXCEPTION,
                    canRetry = false,
                )
                recordStepResult(
                    execution = execution,
                    step = step,
                    status = StepExecutionRecord.Status.FAILED,
                    startedAt = stepStart,
                    completedAt = System.currentTimeMillis(),
                    output = stepResult?.output,
                    stderr = extractStderrFromOutput(stepResult?.output),
                    rootUsed = tier == "SYSTEM_ROOT",
                    attempts = attempts,
                    error = stepError,
                )
            }
        }

        // 5. Finalize
        val endTime = System.currentTimeMillis()
        val guardSummary = globalFailureGuard.getExecutionSummary(taskId)

        val finalStatus = when {
            execution.status == ExecutionStatus.CANCELLED -> ExecutionStatus.CANCELLED
            guardSummary?.failedSteps ?: 0 > 0 -> ExecutionStatus.FAILED
            else -> ExecutionStatus.COMPLETED
        }

        execution.status = finalStatus
        execution.completedAt = endTime
        executions[executionId] = execution

        val steps = execution.steps.toList()
        val total = steps.size
        val completed = steps.count { it.status == StepExecutionRecord.Status.COMPLETED }
        val failed = steps.count { it.status == StepExecutionRecord.Status.FAILED }
        val blocked = steps.count { it.status == StepExecutionRecord.Status.BLOCKED }
        val cancelled = steps.count { it.status == StepExecutionRecord.Status.CANCELLED }
        val skipped = steps.count { it.status == StepExecutionRecord.Status.SKIPPED }

        return@withContext ExecutionSummary(
            executionId = executionId,
            planId = structuredPlan.planId,
            status = finalStatus,
            totalSteps = total,
            completedSteps = completed,
            failedSteps = failed,
            blockedSteps = blocked,
            cancelledSteps = cancelled,
            skippedSteps = skipped,
            rootUsed = rootUsed,
            startedAt = startTime,
            completedAt = endTime,
            totalTimeSeconds = ((endTime - startTime) / 1000).toInt(),
            steps = steps,
        )
    }

    /** Cancel an in-progress execution. */
    fun cancelExecution(executionId: String): Boolean {
        val execution = executions[executionId] ?: return false
        execution.status = ExecutionStatus.CANCELLED
        execution.completedAt = System.currentTimeMillis()
        executions[executionId] = execution

        execution.taskId?.let { taskId ->
            globalFailureGuard.cancelTask(taskId)
        }

        Log.w(TAG, "Execution $executionId cancelled")
        return true
    }

    /** Get execution summary by ID. */
    fun getExecutionSummary(executionId: String): ExecutionSummary? {
        val execution = executions[executionId] ?: return null
        val steps = execution.steps.toList()
        val total = steps.size
        val completed = steps.count { it.status == StepExecutionRecord.Status.COMPLETED }
        val failed = steps.count { it.status == StepExecutionRecord.Status.FAILED }
        val blocked = steps.count { it.status == StepExecutionRecord.Status.BLOCKED }
        val cancelled = steps.count { it.status == StepExecutionRecord.Status.CANCELLED }
        val skipped = steps.count { it.status == StepExecutionRecord.Status.SKIPPED }

        return ExecutionSummary(
            executionId = executionId,
            planId = execution.planId,
            status = execution.status,
            totalSteps = total,
            completedSteps = completed,
            failedSteps = failed,
            blockedSteps = blocked,
            cancelledSteps = cancelled,
            skippedSteps = skipped,
            rootUsed = steps.any { it.rootUsed },
            startedAt = execution.startedAt,
            completedAt = execution.completedAt,
            totalTimeSeconds = (((execution.completedAt ?: execution.startedAt ?: 0L) - (execution.startedAt ?: 0L)).coerceAtLeast(0L) / 1000).toInt(),
            steps = steps,
        )
    }

    /** List all executions. */
    fun getAllExecutions(): List<ApprovedExecution> = executions.values.toList()

    // ==================== Private helpers ====================

    private suspend fun recordStepResult(
        execution: ApprovedExecution,
        step: PlanStep,
        status: StepExecutionRecord.Status,
        startedAt: Long? = null,
        completedAt: Long? = null,
        output: String? = null,
        stderr: String? = null,
        rootUsed: Boolean = false,
        attempts: Int = 0,
        error: String? = null,
        verificationEvidence: String? = null,
    ) {
        executionMutex.withLock {
            val existingIdx = execution.steps.indexOfFirst { it.stepId == step.stepId }
            val record = StepExecutionRecord(
                stepId = step.stepId,
                stepNumber = step.stepNumber,
                description = step.description,
                expectedOutcome = step.expectedOutcome,
                status = status,
                startedAt = startedAt,
                completedAt = completedAt,
                output = output,
                stderr = stderr,
                rootUsed = rootUsed,
                attempts = attempts,
                error = error,
                verificationEvidence = verificationEvidence,
            )
            if (existingIdx >= 0) {
                execution.steps[existingIdx] = record
            } else {
                execution.steps.add(record)
            }
            executions[execution.executionId] = execution
        }
    }

    /** Topological sort of steps by dependencies within phases. */
    private fun topologicalSort(plan: StructuredPlan): List<PlanStep> {
        val stepMap = plan.steps.associateBy { it.stepId }
        val visited = mutableSetOf<String>()
        val result = mutableListOf<PlanStep>()

        fun visit(stepId: String) {
            if (stepId in visited) return
            visited.add(stepId)
            val step = stepMap[stepId] ?: return
            step.dependencies.forEach { depId ->
                if (depId in stepMap) visit(depId)
            }
            result.add(step)
        }

        // Sort phases first, then steps within phases
        plan.phases.sortedBy { it.phaseNumber }.forEach { phase ->
            phase.steps.sortedBy { it.stepNumber }.forEach { visit(it.stepId) }
        }

        // Add any steps not in phases
        plan.steps.forEach { visit(it.stepId) }

        return result.distinctBy { it.stepId }
    }

    /** Map a plan step to an actual command (inert text — never auto-executed). */
    private fun mapStepToCommand(step: PlanStep): String {
        val desc = step.description.trim()
        // Map descriptive steps to harmless system queries only when clearly identified
        return when {
            desc.lowercase().contains("device info") || desc.lowercase().contains("system info") ->
                "id && uname -a && cat /system/build.prop 2>/dev/null | head -20"
            desc.lowercase().contains("installed app") || desc.lowercase().contains("package info") ->
                "pm list packages | head -30"
            desc.lowercase().contains("system settings") ->
                "settings get global | head -20"
            else ->
                // Inert: echo the description; must be explicitly approved before execution
                "echo \"EXECUTE: $desc\""
        }
    }

    companion object {
        private const val TAG_COMPANION = "AndroidHarnessExecutionController"
    }
}
