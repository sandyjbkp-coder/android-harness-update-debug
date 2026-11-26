package com.androidharness.app.agent

import com.androidharness.app.tools.ToolResult
import kotlinx.serialization.Serializable

/**
 * Verifies that executed operations achieved their intended outcomes.
 *
 * This component validates that root commands, installations, and other operations
 * actually succeeded and are appropriate for the context.
 */
class ResultVerifier {

    /**
     * Verifies a ToolResult to check if the execution was successful and appropriate.
     *
     * @param result The ToolResult to verify
     * @return Verification report with success status and recommendations
     */
    fun verifyExecutionResult(result: ToolResult): VerificationReport {
        val output = result.output
        val stderr = extractSection(output, "[stderr]")
        val exitCode = extractMetaValue(output, "exitCode") ?: if (result.ok) "0" else "FAIL"
        val tier = extractMetaValue(output, "tier").orEmpty()
        val rootUsedAppropriately = tier == "SYSTEM_ROOT" || tier == "LINUX_ROOT"
        val verificationStatus = if (result.ok) "PASS" else "FAIL"

        return VerificationReport(
            success = result.ok,
            exitCode = exitCode,
            output = output,
            stderr = stderr,
            rootUsedAppropriately = rootUsedAppropriately,
            verificationStatus = verificationStatus,
            timestamp = System.currentTimeMillis(),
            recommendedAction = determineRecommendedAction(result.ok, output, stderr),
        )
    }

    /**
     * Determines the appropriate action based on verification outcome.
     */
    private fun determineRecommendedAction(ok: Boolean, output: String, stderr: String): String {
        val combined = (stderr.ifBlank { output }).lowercase()
        return when {
            ok && stderr.isBlank() -> "Operation completed successfully, no further action needed"
            ok && stderr.isNotBlank() ->
                "Operation completed with warnings. Review stderr: ${stderr.take(200)}"
            !ok && ("permission" in combined || "denied" in combined) ->
                "Permission denied. Verify KernelSU policies and grant access if needed."
            !ok && ("timeout" in combined || "timed out" in combined) ->
                "Operation timed out. Consider breaking into smaller steps or checking device state."
            !ok -> "Operation failed. Review command output for details, then retry with corrections."
            else -> "Review output for details."
        }
    }

    private fun extractMetaValue(output: String, key: String): String? {
        val marker = "$key="
        val idx = output.lastIndexOf(marker)
        if (idx < 0) return null
        val start = idx + marker.length
        val end = output.indexOfAny(charArrayOf(' ', '\n', '\r', '\t'), start)
        val value = if (end >= 0) output.substring(start, end) else output.substring(start)
        return value.trim().takeIf { it.isNotEmpty() }
    }

    private fun extractSection(output: String, header: String): String {
        val idx = output.indexOf(header)
        if (idx < 0) return ""
        val afterHeader = output.indexOf('\n', idx)
        if (afterHeader < 0 || afterHeader + 1 >= output.length) return ""
        val metaIdx = output.indexOf("\n[meta] ", afterHeader + 1)
        val section = if (metaIdx >= 0) output.substring(afterHeader + 1, metaIdx) else output.substring(afterHeader + 1)
        return section.trimEnd()
    }
}

/**
 * Represents the verification outcome of a command execution.
 */
@Serializable
data class VerificationReport(
    val success: Boolean,
    val exitCode: String,
    val output: String = "",
    val stderr: String = "",
    val rootUsedAppropriately: Boolean,
    val verificationStatus: String,
    val timestamp: Long,
    val recommendedAction: String,
)

@Serializable
data class ComplexityLevel(
    val level: String,
    val description: String,
    val severity: String = "INFO",
)
