package com.androidharness.app.tools

import com.androidharness.app.agent.TaskOrchestrator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Invokes the TaskOrchestrator six-phase autonomous workflow for complex tasks
 * that require research, planning, validation, approval, and controlled execution.
 *
 * This tool is intentionally gated: it should be used for tasks that benefit from
 * the full orchestrated lifecycle, not for ordinary chat responses or simple edits.
 */
class OrchestratorTaskTool(
    private val orchestrator: TaskOrchestrator,
) : Tool {
    override val name = "autonomous_task"
    override val description =
        "Execute a complex task through AndroidHarness's six-phase autonomous workflow: " +
        "task understanding, Perplexity research (untrusted), collaborative planning, " +
        "plan validation, explicit approval gate for state-changing actions, controlled execution, " +
        "and result verification. Use for multi-step tasks requiring research/planning/execution " +
        "coordination. Do NOT use for simple questions, ordinary chat, or single-file edits."
    override val parametersSchema = Schema.obj(
        mapOf(
            "task" to Schema.string(
                "The user task to execute autonomously. Be specific and include all relevant context."
            ),
            "max_research_turns" to Schema.integer(
                "Maximum Perplexity research turns (default 10, max 20)."
            ),
        ),
        required = listOf("task"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val task = args["task"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult(false, "autonomous_task requires a 'task' parameter.")
        val maxTurns = args["max_research_turns"]?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull()?.coerceIn(1, 20) ?: 10

        return try {
            val report = orchestrator.executeTask(
                userTask = task,
                autoApproveReadOnly = true,
                maxResearchTurns = maxTurns,
            )
            val summary = buildString {
                appendLine("TaskOrchestrator completed.")
                appendLine("taskId: ${report.taskId}")
                appendLine("phase: ${report.phase}")
                appendLine("approval: ${report.approval}")
                appendLine("verification: ${report.verification ?: "n/a"}")
                report.plan?.let { p ->
                    appendLine("plan: id=${p.planId} status=${p.status} steps=${p.steps} risk=${p.riskLevel}")
                }
                report.validation?.let { v ->
                    appendLine("validation: status=${v.status} errors=${v.errors} warnings=${v.warnings}")
                }
                report.execution?.let { e ->
                    appendLine("execution: id=${e.executionId} status=${e.status} completed=${e.completed}/${e.totalSteps} failed=${e.failed} blocked=${e.blocked} cancelled=${e.cancelled} rootUsed=${e.rootUsed}")
                }
                if (report.unresolvedIssues.isNotEmpty()) {
                    appendLine("unresolvedIssues:")
                    report.unresolvedIssues.forEach { appendLine("- $it") }
                }
                appendLine("completedAt: ${report.completedAt}")
            }
            val ok = report.phase == com.androidharness.app.agent.TaskOrchestrator.TaskPhase.COMPLETED
            ToolResult(ok, summary.trimEnd())
        } catch (e: Exception) {
            ToolResult(false, "TaskOrchestrator failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
