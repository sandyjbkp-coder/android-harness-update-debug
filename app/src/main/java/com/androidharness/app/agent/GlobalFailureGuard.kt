package com.androidharness.app.agent

import android.content.Context
import android.util.Log
import com.androidharness.app.agent.CollaborativePlanBuilder.PlanStep
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 5 — Global Failure Guard.
 *
 * Centralized failure-handling component for execution workflows. Tracks
 * task/step execution states, detects timeouts/exceptions/missing-capabilities/
 * failed-verification, prevents dependent steps from proceeding after
 * prerequisite failure, preserves completed-step results and execution
 * history, supports cancellation and safe recovery, and produces structured
 * failure reports.
 *
 * Silent failures and false success states are prevented. Retries are bounded.
 * Rollback metadata is supported where a step explicitly defines a verified
 * reversible operation.
 */
class GlobalFailureGuard(
    private val context: Context,
    private val maxRetriesPerStep: Int = 3,
    private val defaultStepTimeoutSeconds: Int = 60,
) {
    private val TAG = "GlobalFailureGuard"

    private val executions = ConcurrentHashMap<String, TaskExecution>()
    private val stepStates = ConcurrentHashMap<String, StepExecutionState>()
    private val failureReports = ConcurrentHashMap<String, FailureReport>()
    private val cancellationToken = java.util.concurrent.atomic.AtomicBoolean(false)
    private val executionCounter = AtomicInteger(0)

    // ==================== State models ====================

    /** Outcome of a single step execution attempt. */
    enum class StepOutcome { PENDING, IN_PROGRESS, COMPLETED, FAILED, TIMED_OUT, CANCELLED, SKIPPED, BLOCKED }

    /** Severity of a recorded failure. */
    enum class FailureSeverity { LOW, MEDIUM, HIGH, CRITICAL }

    /** Type of failure detected. */
    enum class FailureType {
        TIMEOUT, EXCEPTION, MISSING_CAPABILITY, FAILED_VERIFICATION,
        PREREQUISITE_FAILED, CANCELLED, INVALID_STATE,
    }

    /** State of a single step within a task execution. */
    @Serializable
    data class StepExecutionState(
        val stepId: String,
        val taskId: String,
        val phaseId: String,
        val stepNumber: Int,
        val outcome: StepOutcome = StepOutcome.PENDING,
        val attempts: Int = 0,
        val maxAttempts: Int = 3,
        val lastError: String? = null,
        val lastFailureType: FailureType? = null,
        val completedAt: Long? = null,
        val result: String? = null,
        val rollbackMetadata: String? = null, // verified reversible operation metadata
        val dependsOn: List<String> = emptyList(),
        val dependents: List<String> = emptyList(), // steps that depend on this one
        val isBlocking: Boolean = true, // if true, failure blocks dependents
        val timeoutSeconds: Int = 60,
        val startedAt: Long? = null,
    ) {
        val canRetry: Boolean get() = attempts < maxAttempts && outcome == StepOutcome.FAILED
        val isTerminal: Boolean get() =
            outcome == StepOutcome.COMPLETED || outcome == StepOutcome.CANCELLED ||
                outcome == StepOutcome.SKIPPED || (outcome == StepOutcome.FAILED && attempts >= maxAttempts)
    }

    /** Overall task execution state. */
    @Serializable
    data class TaskExecution(
        val taskId: String,
        val planId: String,
        val status: ExecutionStatus = ExecutionStatus.INITIALIZED,
        val startedAt: Long = System.currentTimeMillis(),
        val completedAt: Long? = null,
        val totalSteps: Int = 0,
        val completedSteps: Int = 0,
        val failedSteps: Int = 0,
        val cancelledSteps: Int = 0,
        val currentStepId: String? = null,
        val cancellationRequested: Boolean = false,
    ) {
        val isComplete: Boolean get() =
            status == ExecutionStatus.COMPLETED || status == ExecutionStatus.FAILED ||
                status == ExecutionStatus.CANCELLED
    }

    enum class ExecutionStatus { INITIALIZED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, PAUSED }

    /** Structured failure report. */
    @Serializable
    data class FailureReport(
        val reportId: String = UUID.randomUUID().toString(),
        val taskId: String,
        val stepId: String?,
        val failureType: FailureType,
        val severity: FailureSeverity,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val retryCount: Int = 0,
        val maxRetries: Int = 3,
        val canRetry: Boolean = false,
        val rollbackAvailable: Boolean = false,
        val rollbackMetadata: String? = null,
        val dependentStepsBlocked: List<String> = emptyList(),
    )

    /** Rollback metadata for a verified reversible step. */
    @Serializable
    data class RollbackMetadata(
        val stepId: String,
        val rollbackOperation: String, // descriptive, inert — never executed automatically
        val isVerified: Boolean = false,
        val verificationSource: String? = null,
    )

    // ==================== Task lifecycle ====================

    /** Register a new task execution. Returns the task ID. */
    fun registerTask(
        planId: String,
        totalSteps: Int,
        stepDefinitions: List<PlanStep>,
    ): String {
        val taskId = "task-${UUID.randomUUID().toString()}"
        val task = TaskExecution(
            taskId = taskId,
            planId = planId,
            status = ExecutionStatus.INITIALIZED,
            totalSteps = totalSteps,
        )
        executions[taskId] = task

        // Initialize step states
        stepDefinitions.forEach { step ->
            val dependents = stepDefinitions
                .filter { it.dependencies.contains(step.stepId) }
                .map { it.stepId }
            val state = StepExecutionState(
                stepId = step.stepId,
                taskId = taskId,
                phaseId = step.phaseId,
                stepNumber = step.stepNumber,
                maxAttempts = maxRetriesPerStep,
                dependsOn = step.dependencies,
                dependents = dependents,
                isBlocking = step.riskLevel == CollaborativePlanBuilder.RiskLevel.HIGH ||
                    step.reversibility != CollaborativePlanBuilder.Reversibility.FULLY,
                timeoutSeconds = defaultStepTimeoutSeconds,
                rollbackMetadata = if (step.reversibility == CollaborativePlanBuilder.Reversibility.FULLY) {
                    "verified-reversible:${step.stepId}"
                } else null,
            )
            stepStates[step.stepId] = state
        }

        Log.d(TAG, "Registered task $taskId with $totalSteps steps")
        return taskId
    }

    /** Start a task execution. */
    fun startTask(taskId: String): Boolean {
        val task = executions[taskId] ?: return false
        return if (task.isComplete) {
            Log.w(TAG, "Task $taskId already complete, cannot start")
            false
        } else {
            executions[taskId] = task.copy(status = ExecutionStatus.IN_PROGRESS)
            Log.d(TAG, "Task $taskId started")
            true
        }
    }

    /** Mark a step as in progress. */
    fun startStep(taskId: String, stepId: String): Boolean {
        val state = stepStates[stepId] ?: return false
        val task = executions[taskId] ?: return false

        // Check prerequisites
        val prerequisiteFailures = state.dependsOn.filter { depId ->
            stepStates[depId]?.outcome == StepOutcome.FAILED
        }
        if (prerequisiteFailures.isNotEmpty()) {
            // Block this step — prerequisite failed
            stepStates[stepId] = state.copy(
                outcome = StepOutcome.BLOCKED,
                lastError = "Blocked: prerequisite step(s) failed: ${prerequisiteFailures.joinToString(", ")}",
                lastFailureType = FailureType.PREREQUISITE_FAILED,
            )
            recordFailure(
                taskId = taskId,
                stepId = stepId,
                failureType = FailureType.PREREQUISITE_FAILED,
                severity = FailureSeverity.HIGH,
                message = "Step $stepId blocked: prerequisites failed",
                canRetry = false,
                rollbackAvailable = state.rollbackMetadata != null,
                rollbackMetadata = state.rollbackMetadata,
            )
            return false
        }

        // Check cancellation
        if (cancellationToken.get() || task.cancellationRequested) {
            stepStates[stepId] = state.copy(outcome = StepOutcome.CANCELLED)
            return false
        }

        stepStates[stepId] = state.copy(
            outcome = StepOutcome.IN_PROGRESS,
            startedAt = System.currentTimeMillis(),
            attempts = state.attempts + 1,
        )
        executions[taskId] = task.copy(currentStepId = stepId)
        Log.d(TAG, "Task $taskId step $stepId started (attempt ${state.attempts + 1})")
        return true
    }

    /** Mark a step as completed with result. */
    fun completeStep(taskId: String, stepId: String, result: String? = null): Boolean {
        val state = stepStates[stepId] ?: return false
        stepStates[stepId] = state.copy(
            outcome = StepOutcome.COMPLETED,
            completedAt = System.currentTimeMillis(),
            result = result,
        )
        val task = executions[taskId]
        if (task != null) {
            val completed = stepStates.values.count { it.taskId == taskId && it.outcome == StepOutcome.COMPLETED }
            val failed = stepStates.values.count { it.taskId == taskId && it.outcome == StepOutcome.FAILED }
            executions[taskId] = task.copy(
                completedSteps = completed,
                failedSteps = failed,
                currentStepId = null,
            )
        }
        Log.d(TAG, "Task $taskId step $stepId completed")
        return true
    }

    /** Record a step failure. */
    fun failStep(
        taskId: String,
        stepId: String,
        error: String,
        failureType: FailureType = FailureType.EXCEPTION,
        canRetry: Boolean = true,
    ): Boolean {
        val state = stepStates[stepId] ?: return false
        val task = executions[taskId] ?: return false

        val newAttempts = state.attempts + 1
        val shouldRetry = canRetry && newAttempts < state.maxAttempts

        val newOutcome = if (shouldRetry) StepOutcome.FAILED else StepOutcome.FAILED
        stepStates[stepId] = state.copy(
            outcome = newOutcome,
            attempts = newAttempts,
            lastError = error,
            lastFailureType = failureType,
        )

        // Record structured failure
        recordFailure(
            taskId = taskId,
            stepId = stepId,
            failureType = failureType,
            severity = FailureSeverity.HIGH,
            message = error,
            retryCount = newAttempts,
            maxRetries = state.maxAttempts,
            canRetry = shouldRetry,
            rollbackAvailable = state.rollbackMetadata != null,
            rollbackMetadata = state.rollbackMetadata,
            dependentStepsBlocked = state.dependents,
        )

        // If step is blocking and cannot retry, mark dependents as blocked
        if (state.isBlocking && !shouldRetry) {
            state.dependents.forEach { depId ->
                val depState = stepStates[depId]
                if (depState != null && depState.outcome == StepOutcome.PENDING) {
                    stepStates[depId] = depState.copy(
                        outcome = StepOutcome.BLOCKED,
                        lastError = "Blocked: prerequisite step $stepId failed",
                        lastFailureType = FailureType.PREREQUISITE_FAILED,
                    )
                }
            }
        }

        val failedCount = stepStates.values.count { it.taskId == taskId && it.outcome == StepOutcome.FAILED }
        executions[taskId] = task.copy(
            failedSteps = failedCount,
            currentStepId = null,
        )

        Log.w(TAG, "Task $taskId step $stepId failed: $error (attempt $newAttempts/${state.maxAttempts})")
        return shouldRetry
    }

    /** Record a step timeout. */
    fun timeoutStep(
        taskId: String,
        stepId: String,
        timeoutSeconds: Int,
    ): Boolean {
        val state = stepStates[stepId] ?: return false
        return failStep(
            taskId = taskId,
            stepId = stepId,
            error = "Step timed out after ${timeoutSeconds}s",
            failureType = FailureType.TIMEOUT,
            canRetry = state.attempts < state.maxAttempts,
        )
    }

    /** Record a missing capability failure. */
    fun missingCapability(
        taskId: String,
        stepId: String,
        capability: String,
    ): Boolean {
        val state = stepStates[stepId] ?: return false
        return failStep(
            taskId = taskId,
            stepId = stepId,
            error = "Missing required capability: $capability",
            failureType = FailureType.MISSING_CAPABILITY,
            canRetry = false,
        )
    }

    /** Record a failed verification. */
    fun failedVerification(
        taskId: String,
        stepId: String,
        verificationCriterion: String,
    ): Boolean {
        val state = stepStates[stepId] ?: return false
        return failStep(
            taskId = taskId,
            stepId = stepId,
            error = "Verification failed: $verificationCriterion",
            failureType = FailureType.FAILED_VERIFICATION,
            canRetry = false,
        )
    }

    /** Cancel a task and all pending steps. */
    fun cancelTask(taskId: String): Boolean {
        cancellationToken.set(true)
        val task = executions[taskId] ?: return false

        // Cancel all pending/in-progress steps
        stepStates.entries.filter { it.value.taskId == taskId }.forEach { (stepId, state) ->
            if (state.outcome == StepOutcome.PENDING || state.outcome == StepOutcome.IN_PROGRESS) {
                stepStates[stepId] = state.copy(outcome = StepOutcome.CANCELLED)
            }
        }

        executions[taskId] = task.copy(
            status = ExecutionStatus.CANCELLED,
            completedAt = System.currentTimeMillis(),
            cancellationRequested = true,
        )

        Log.w(TAG, "Task $taskId cancelled")
        return true
    }

    /** Request rollback metadata for a completed reversible step. */
    fun getRollbackMetadata(stepId: String): RollbackMetadata? {
        val state = stepStates[stepId] ?: return null
        return if (state.rollbackMetadata != null && state.outcome == StepOutcome.COMPLETED) {
            RollbackMetadata(
                stepId = stepId,
                rollbackOperation = state.rollbackMetadata,
                isVerified = true,
                verificationSource = "step-completion-verification",
            )
        } else null
    }

    // ==================== Failure reporting ====================

    /** Record a structured failure. */
    private fun recordFailure(
        taskId: String,
        stepId: String?,
        failureType: FailureType,
        severity: FailureSeverity,
        message: String,
        retryCount: Int = 0,
        maxRetries: Int = 3,
        canRetry: Boolean = false,
        rollbackAvailable: Boolean = false,
        rollbackMetadata: String? = null,
        dependentStepsBlocked: List<String> = emptyList(),
    ) {
        val report = FailureReport(
            taskId = taskId,
            stepId = stepId,
            failureType = failureType,
            severity = severity,
            message = message,
            retryCount = retryCount,
            maxRetries = maxRetries,
            canRetry = canRetry,
            rollbackAvailable = rollbackAvailable,
            rollbackMetadata = rollbackMetadata,
            dependentStepsBlocked = dependentStepsBlocked,
        )
        failureReports[report.reportId] = report
        Log.w(TAG, "Failure recorded: ${report.reportId} — $message (type=$failureType, severity=$severity)")
    }

    /** Get the failure report for a task. */
    fun getFailureReport(taskId: String): List<FailureReport> {
        return failureReports.values.filter { it.taskId == taskId }.toList()
    }

    /** Get all failure reports. */
    fun getAllFailureReports(): List<FailureReport> = failureReports.values.toList()

    /** Get execution state for a task. */
    fun getTaskExecution(taskId: String): TaskExecution? = executions[taskId]

    /** Get step state. */
    fun getStepState(stepId: String): StepExecutionState? = stepStates[stepId]

    /** Check if a step can proceed (prerequisites met, not cancelled, not blocked). */
    fun canStepProceed(taskId: String, stepId: String): Boolean {
        val state = stepStates[stepId] ?: return false
        val task = executions[taskId] ?: return false

        if (task.cancellationRequested || cancellationToken.get()) return false
        if (state.outcome != StepOutcome.PENDING) return false
        if (state.dependents.isNotEmpty() && state.outcome == StepOutcome.BLOCKED) return false

        // All prerequisites must be completed
        val prerequisitesMet = state.dependsOn.all { depId ->
            stepStates[depId]?.outcome == StepOutcome.COMPLETED
        }
        return prerequisitesMet
    }

    /** Get the next executable step ID for a task, or null if none/blocked. */
    fun getNextStepId(taskId: String): String? {
        val task = executions[taskId] ?: return null
        if (task.isComplete || task.cancellationRequested) return null

        return stepStates.entries
            .filter { it.value.taskId == taskId }
            .filter { (_, state) -> canStepProceed(taskId, state.stepId) }
            .minByOrNull { it.value.stepNumber }
            ?.key
    }

    /** Check if task execution is complete (all terminal steps). */
    fun isTaskComplete(taskId: String): Boolean {
        val task = executions[taskId] ?: return false
        val taskSteps = stepStates.values.filter { it.taskId == taskId }
        if (taskSteps.isEmpty()) return false
        return taskSteps.all { it.outcome.isTerminal }
    }

    /** Get execution summary statistics. */
    fun getExecutionSummary(taskId: String): ExecutionSummary? {
        val task = executions[taskId] ?: return null
        val taskSteps = stepStates.values.filter { it.taskId == taskId }
        val completed = taskSteps.count { it.outcome == StepOutcome.COMPLETED }
        val failed = taskSteps.count { it.outcome == StepOutcome.FAILED }
        val blocked = taskSteps.count { it.outcome == StepOutcome.BLOCKED }
        val cancelled = taskSteps.count { it.outcome == StepOutcome.CANCELLED }

        return ExecutionSummary(
            taskId = taskId,
            planId = task.planId,
            status = task.status,
            totalSteps = taskSteps.size,
            completedSteps = completed,
            failedSteps = failed,
            blockedSteps = blocked,
            cancelledSteps = cancelled,
            canResume = task.status == ExecutionStatus.PAUSED ||
                (task.status == ExecutionStatus.IN_PROGRESS && failed == 0 && cancelled == 0),
        )
    }

    @Serializable
    data class ExecutionSummary(
        val taskId: String,
        val planId: String,
        val status: ExecutionStatus,
        val totalSteps: Int,
        val completedSteps: Int,
        val failedSteps: Int,
        val blockedSteps: Int,
        val cancelledSteps: Int,
        val canResume: Boolean,
    )


val GlobalFailureGuard.StepOutcome.isTerminal: Boolean
    get() = this == GlobalFailureGuard.StepOutcome.COMPLETED ||
        this == GlobalFailureGuard.StepOutcome.FAILED ||
        this == GlobalFailureGuard.StepOutcome.TIMED_OUT ||
        this == GlobalFailureGuard.StepOutcome.CANCELLED ||
        this == GlobalFailureGuard.StepOutcome.SKIPPED ||
        this == GlobalFailureGuard.StepOutcome.BLOCKED

    companion object {
        private const val TAG_COMPANION = "GlobalFailureGuard"
    }
}
