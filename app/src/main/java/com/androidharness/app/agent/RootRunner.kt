package com.androidharness.app.agent

import android.content.Context
import com.androidharness.app.data.env.ExecutionTier
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.RootManager
import com.androidharness.app.data.env.RootState
import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Execution runner for Android root operations via RootManager.
 */
class RootRunner(
    private val context: Context,
    private val rootManager: RootManager = RootManager(context),
    private val linuxEnv: LinuxEnvironmentManager? = null,
) {
    companion object {
        const val SU = "/system/bin/su"
    }

    suspend fun runCommand(
        command: String,
        tier: ExecutionTier = ExecutionTier.SYSTEM_ROOT,
        timeoutSeconds: Int = 30,
    ): ToolResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds.coerceAtLeast(1) * 1_000L
        try {
            when (tier) {
                ExecutionTier.SYSTEM_ROOT -> {
                    val result = rootManager.runSystem(
                        script = command,
                        timeoutMs = timeoutMs.toInt(),
                        maxBytes = 1024 * 1024,
                    )
                    toToolResult(result, tier, startMs)
                }
                ExecutionTier.LINUX_ROOT -> {
                    val prefix = runCatching { LinuxEnvironmentManager.deployedBaseFor(context.packageName) }.getOrDefault("/data/local/tmp/androidharness/linux")
                    val result = rootManager.runLinux(
                        script = command,
                        prefix = prefix,
                        timeoutMs = timeoutMs.toInt(),
                        maxBytes = 1024 * 1024,
                    )
                    toToolResult(result, tier, startMs)
                }
                else -> ToolResult(ok = false, output = "Tier ${tier.name} not supported by RootRunner. Use SYSTEM_ROOT or LINUX_ROOT only.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(ok = false, output = "Root execution failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun verifyRootAccess(): Boolean = withContext(Dispatchers.IO) {
        rootManager.state.value == RootState.GRANTED
    }

    fun isRootGranted(): Boolean = rootManager.state.value == RootState.GRANTED

    fun getAvailableTiers(): List<ExecutionTier> =
        if (isRootGranted()) listOf(ExecutionTier.SYSTEM_ROOT, ExecutionTier.LINUX_ROOT) else listOf(ExecutionTier.TOYBOX)

    fun selectTier(command: String): ExecutionTier {
        val lowerCmd = command.lowercase()
        return when {
            lowerCmd.contains("pm ") || lowerCmd.contains("am ") || lowerCmd.contains("cmd ") ||
                lowerCmd.contains("dumpsys") || lowerCmd.contains("settings") ||
                lowerCmd.contains("logcat") || lowerCmd.contains("input ") ||
                lowerCmd.contains("uiautomator") || lowerCmd.contains("/system/bin/") ->
                ExecutionTier.SYSTEM_ROOT
            lowerCmd.contains("bash") || lowerCmd.contains("python") || lowerCmd.contains("git") ||
                lowerCmd.contains("node") || lowerCmd.contains("npm") || lowerCmd.contains("gradle") ||
                lowerCmd.contains("aapt2") ->
                ExecutionTier.LINUX_ROOT
            else -> if (isRootGranted()) ExecutionTier.SYSTEM_ROOT else ExecutionTier.TOYBOX
        }
    }

    private fun toToolResult(
        result: com.androidharness.app.data.env.RootResult,
        tier: ExecutionTier,
        startMs: Long,
    ): ToolResult {
        val durationSeconds = (System.currentTimeMillis() - startMs) / 1000
        val ok = !result.timedOut && result.exitCode == 0
        val stdout = result.output.trimEnd()
        val stderr = result.stderr.trimEnd()
        val output = buildString {
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("[stderr]\n").append(stderr)
            }
            if (isEmpty()) append("(no output)")
            append("\n[meta] tier=").append(tier.name)
                .append(" exitCode=").append(result.exitCode)
                .append(" timedOut=").append(result.timedOut)
                .append(" duration=").append(durationSeconds).append("s")
        }
        return ToolResult(ok = ok, output = output)
    }
}
