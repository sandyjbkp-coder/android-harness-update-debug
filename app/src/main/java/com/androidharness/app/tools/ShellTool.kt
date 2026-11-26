package com.androidharness.app.tools

import com.androidharness.app.data.env.ExecutionTier
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.RootManager
import com.androidharness.app.data.env.RootState
import com.androidharness.app.data.env.ShellTierRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private const val MAX_OUTPUT_CHARS = 100_000

/**
 * Tracks failed commands to prevent blind retries. Thread-safe map of command signatures
 * to their failure records. A command signature is the first two tokens of the command.
 */
object FailureTracker {
    private val failures = mutableMapOf<String, FailureRecord>()
    
    data class FailureRecord(
        val command: String,
        val exitCode: Int,
        val error: String,
        val cause: String,
        val timestamp: Long
    )
    
    /**
     * Records a failed command so it can be blocked on retry.
     */
    fun recordFailure(command: String, exitCode: Int, error: String, cause: String) {
        val signature = command.trim().split(Regex("\\s+")).take(2).joinToString(" ")
        failures[signature] = FailureRecord(command, exitCode, error, cause, System.currentTimeMillis())
    }
    
    /**
     * Checks if a command has previously failed and should be blocked.
     * Returns the failure record if found, null otherwise.
     */
    fun getPreviousFailure(command: String): FailureRecord? {
        val signature = command.trim().split(Regex("\\s+")).take(2).joinToString(" ")
        return failures[signature]
    }
    
    /**
     * Clears a failure record after a verified correction has been applied.
     */
    fun clearFailure(command: String) {
        val signature = command.trim().split(Regex("\\s+")).take(2).joinToString(" ")
        failures.remove(signature)
    }
    
    /**
     * Checks if a command requires root and has the "su -c" prefix.
     */
    fun requiresRootAndMissingSu(command: String, rootManager: RootManager?): Boolean {
        if (rootManager == null || rootManager.state.value != RootState.GRANTED) return false
        // Commands that target system paths or use system binaries need root
        val needsRoot = command.contains("/system/bin/") || 
                       command.contains("/system/") ||
                       command.contains("/data/local/tmp/") ||
                       command.contains("pm ") || command.contains("am ") ||
                       command.contains("cmd ") || command.contains("settings ") ||
                       command.contains("dumpsys ") || command.contains("input ")
        if (!needsRoot) return false
        // Check if it already has su -c
        return !command.contains("su -c") && !command.contains("su -c ")
    }
}

class ShellTool(
    private val linuxEnv: LinuxEnvironmentManager,
    private val router: ShellTierRouter,
) : Tool {
    override val name = "shell"
    override val description =
        "Run a shell command on the device. Uses SSH for an SSH workspace; " +
        "otherwise runs with the best available native environment: " +
        "a full Linux userspace (bash, git, python, node…) as the app when installed, " +
        "root privileges (plus the same toolchain) when root is granted and " +
        "the target folder needs it, otherwise toybox sh. If the active workspace is a picked " +
        "folder (SAF), the command runs in the app's shell workspace and a note is added. " +
        "Returns the exit code plus captured stdout and stderr as separate sections; output " +
        "written before a timeout is preserved."
    override val parametersSchema = Schema.obj(
        mapOf(
            "command" to Schema.string("The shell command to run."),
            "cmd" to Schema.string("Compatibility alias for command. Prefer command."),
            "cwd" to Schema.string(
                "Working directory relative to the workspace root (default: the root). " +
                    "Use it instead of 'cd dir && …' chains.",
            ),
            "timeout_seconds" to Schema.integer("Kill the command after this many seconds. Defaults to 120, max 600."),
        ),
        required = emptyList(),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val rawCommand =
                args["command"]?.jsonPrimitive?.content
                    ?: args["cmd"]?.jsonPrimitive?.content
                    ?: throw ToolFailure("Missing required argument: command (or cmd)")
            val remote = ctx.workspace as? com.androidharness.app.workspace.SshFs
            val root = remote?.root?.let { File(it) } ?: ctx.workspace.shellRoot
                ?: throw ToolFailure(
                    "This workspace has no real filesystem path, so the shell cannot run here. " +
                        "Switch to a device folder or the app workspace (Settings → Workspace).",
                )
            val cwd =
                if (remote != null) File(remote.commandPath(args["cwd"]?.jsonPrimitive?.content ?: "."))
                else if (ctx.sandboxOff) resolveCwdUnchecked(args, root)
                else resolveCwd(args, root)

            // Full access skips the denylist re-check; the engine already
            // gated this call against the same policy.
            val deny = if (ctx.sandboxOff) null else ShellPolicy.denyReason(rawCommand, root, cwd)
            if (deny != null) {
                return@withContext ToolResult(false, deny)
            }
            
            // Pre-execution check: reject root commands missing "su -c"
            // This requires access to RootManager, which we get from the router
            val rootManager = (router as? ShellTierRouter)?.root
            if (FailureTracker.requiresRootAndMissingSu(rawCommand, rootManager)) {
                return@withContext ToolResult(false, 
                    "Blocked: root-required command must use 'su -c' prefix. Use: su -c 'COMMAND'")
            }
            
            // Pre-execution check: block duplicate failed commands
            val previousFailure = FailureTracker.getPreviousFailure(rawCommand)
            if (previousFailure != null) {
                return@withContext ToolResult(false,
                    "Blocked: this command previously failed (exit=${previousFailure.exitCode}, cause=${previousFailure.cause}). " +
                    "Apply the verified correction before retrying.")
            }

            val timeoutSec = (args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 120)
                .coerceIn(1, 600)

            // Auto-append --no-bin-links for npm install on shared storage (symlinks unsupported).
            val (command, notes) = if (remote != null) rawCommand to null else run {
                val (afterNpm, npmNote) = NpmOnSharedStorage.prepare(rawCommand, cwd)
                // Bug 4 fix: retarget workspace tar extractions to the
                // exec-capable scratch dir so symlinks/exec bits survive.
                val (afterTar, tarNote) = ExecScratchRouting.prepare(afterNpm, cwd)
                afterTar to listOfNotNull(npmNote, tarNote).joinToString("\n").ifBlank { null }
            }

            val res = if (remote != null) remote.run(command, cwd.path, timeoutSec * 1000, MAX_OUTPUT_CHARS * 2)
                else router.run(command, cwd, timeoutSec * 1000, MAX_OUTPUT_CHARS * 2)

            val isSymlink = rawCommand.contains("ln ") && (rawCommand.contains("-s") || rawCommand.contains("--symbolic"))
            val hasSymlinkError = isSymlink && (res.exitCode != 0 || res.rawOutput.contains("Permission denied", true) || res.rawStderr.contains("Permission denied", true) || res.rawOutput.contains("Operation not permitted", true) || res.rawStderr.contains("Operation not permitted", true))

            if (isSymlink && remote == null) {
                // Check if ln left a stale empty regular file at destination
                val tokens = ShellPolicy.extractTokens(rawCommand)
                val lastToken = tokens.lastOrNull()?.trim()
                if (!lastToken.isNullOrEmpty() && !lastToken.startsWith("-")) {
                    val destFile = if (lastToken.startsWith("/")) File(lastToken) else File(cwd, lastToken)
                    if (destFile.exists() && destFile.isFile && destFile.length() == 0L && hasSymlinkError) {
                        destFile.delete()
                    }
                }
            }

            val sb = StringBuilder()
            notes?.let { sb.append(it).append('\n') }
            res.note?.let { sb.append(it).append('\n') }
            if (hasSymlinkError) {
                sb.append("[note: symlink creation failed: symlink not supported/allowed on this filesystem]\n")
            }
            if (res.tier == ExecutionTier.TOYBOX && linuxEnv.bashExecutable() != null) {
                sb.append("[note: fell back to toybox sh, launching the Linux bash failed on this device]\n")
            }
            if (res.tier == ExecutionTier.SYSTEM_ROOT) {
                sb.append("[note: ran as root with Android system binaries]\n")
            }
            if (res.tier == ExecutionTier.LINUX_ROOT) {
                sb.append("[note: ran as root with the full Linux toolchain]\n")
            }
            if (res.timedOut) sb.append(if (res.tier == ExecutionTier.TERMUX_SSH)
                "[SSH channel closed after ${timeoutSec}s timeout; inspect Termux before retrying modifying commands]\n"
                else "[killed after ${timeoutSec}s timeout; output below is what was written before the kill]\n")
            sb.append("exit code: ").append(if (res.timedOut) "killed (timeout)" else if (hasSymlinkError && res.exitCode == 0) 1 else res.exitCode).append('\n')
            val out = res.rawOutput.trimEnd()
            val err = res.rawStderr.trimEnd()
            if (out.isNotEmpty()) sb.append("stdout (JSON string): ").append(kotlinx.serialization.json.JsonPrimitive(out.truncated())).append('\n')
            if (err.isNotEmpty()) sb.append("stderr (JSON string): ").append(kotlinx.serialization.json.JsonPrimitive(err.truncated())).append('\n')
            if (out.isEmpty() && err.isEmpty()) sb.append("(no output)")
            ToolResult(ok = !res.timedOut && res.exitCode == 0 && !hasSymlinkError, output = sb.toString().trimEnd())
        }

    /** Resolves the optional cwd argument inside the workspace root. */
    private fun resolveCwd(args: JsonObject, root: File): File {
        val rel = args["cwd"]?.jsonPrimitive?.content?.trim()
            ?.removePrefix("./")?.trimEnd('/')
            ?: return root

        if (rel.isEmpty()) return root

        val canonicalRoot = root.canonicalFile

        // Allow absolute cwd only when it still resolves inside the active workspace.
        val dir =
            if (rel.startsWith('/')) File(rel)
            else File(canonicalRoot, rel)

        val canonical = dir.canonicalFile

        val rootPath = canonicalRoot.path.trimEnd('/')
        val targetPath = canonical.path

        val insideWorkspace =
            targetPath == rootPath ||
                targetPath.startsWith("$rootPath/")

        if (!insideWorkspace) {
            throw ToolFailure("cwd is outside the workspace and was blocked: $rel")
        }

        if (!canonical.exists()) {
            throw ToolFailure("cwd does not exist: $rel")
        }

        if (!canonical.isDirectory) {
            throw ToolFailure("cwd is not a directory: $rel")
        }

        return canonical
    }

    /** Full access variant: any absolute or escaping cwd resolves unchecked. */
    private fun resolveCwdUnchecked(args: JsonObject, root: File): File {
        val rel = args["cwd"]?.jsonPrimitive?.content?.trim()
            ?.removePrefix("./")?.trimEnd('/')
            ?: return root
        if (rel.isEmpty()) return root
        // Absolute paths resolve directly; relative paths resolve against workspace root.
        val dir = if (rel.startsWith('/')) File(rel) else File(root, rel)
        val canonical = dir.canonicalFile
        if (!canonical.exists()) throw ToolFailure("cwd does not exist: $rel")
        if (!canonical.isDirectory) throw ToolFailure("cwd is not a directory: $rel")
        return canonical
    }

    private fun String.truncated(): String =
        if (length <= MAX_OUTPUT_CHARS) this
        else substring(0, MAX_OUTPUT_CHARS) + "\n[truncated]"
}
