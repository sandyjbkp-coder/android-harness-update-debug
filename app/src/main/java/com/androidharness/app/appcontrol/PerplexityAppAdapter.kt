package com.androidharness.app.appcontrol

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.androidharness.app.agent.RootRunner
import com.androidharness.app.data.env.ExecutionTier
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.appcontrol.PerplexityUiMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.UUID

/**
 * PerplexityAppAdapter - Phase 2: Multi-turn Perplexity Conversation Bridge
 *
 * UI automation bridge between AndroidHarness and the installed Perplexity Android app.
 * All Android device-side shell commands execute through RootRunner using "su -c".
 *
 * Interaction selectors are resource-ID based, extracted from fresh uiautomator dumps.
 * No hardcoded tap coordinates. No fixed sleeps for response completion.
 *
 * Verified selectors (from Phase 1.2 reconnaissance):
 *   Composer: resource-id "input-search" (android.widget.EditText)
 *   Send button: resource-id "send-button" (child TextView "Send"), visible only when composer is non-empty
 *
 * Package name: ai.perplexity.app.android (discovered at runtime via pm, never assumed).
 */
class PerplexityAppAdapter(
    private val context: Context,
    private val rootRunner: RootRunner = RootRunner(context),
) {
    private val TAG = "PerplexityAppAdapter"

    /** Perplexity package name, discovered at runtime via pm list packages. */
    private var perplexityPackage: String? = null

    /** Resolved launcher activity component (class name), discovered at runtime. */
    private var resolvedLauncherActivity: String? = null

    /** Current active conversation/thread id for multi-turn continuity. */
    private var activeConversationId: String? = null

    /** Messages sent in this session. */
    private val messagesSent = mutableListOf<SentMessage>()

    data class SentMessage(
        val id: String = UUID.randomUUID().toString(),
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val response: String? = null,
        val success: Boolean = false,
    )

    /** Parsed reference to a UI node from an uiautomator dump. */
    data class UiNode(
        val className: String,
        val resourceId: String?,
        val contentDesc: String?,
        val text: String?,
        val bounds: String?,
        val clickable: Boolean,
        val focusable: Boolean,
        val enabled: Boolean,
    ) {
        /** Center point [x, y] of the bounds, or null if bounds unavailable. */
        fun center(): IntArray? {
            bounds?.let { b ->
                val nums = b.removeSurrounding("[").removeSurrounding("]").split("][")
                if (nums.size == 2) {
                    val tl = nums[0].split(",").map { it.toInt() }
                    val br = nums[1].split(",").map { it.toInt() }
                    return intArrayOf((tl[0] + br[0]) / 2, (tl[1] + br[1]) / 2)
                }
            }
            return null
        }
    }

    // ==================== Package discovery & lifecycle ====================

    /** Discover the Perplexity package name at runtime using pm list packages. */
    suspend fun discoverPackage(): String? = withContext(Dispatchers.IO) {
        if (perplexityPackage != null) return@withContext perplexityPackage

        val known = PerplexityUiMap.PACKAGE
        val installed = rootRunner.runCommand("pm list packages", ExecutionTier.SYSTEM_ROOT, 8)
        if (installed.ok && installed.output.contains(known)) {
            perplexityPackage = known
            return@withContext known
        }

        val found = if (installed.ok) {
            installed.output.lineSequence()
                .map { it.removePrefix("package:").trim() }
                .firstOrNull { it.contains("perplexity", ignoreCase = true) }
        } else null
        perplexityPackage = found
        found
    }

    /**
     * Resolve launcher activity at runtime to avoid hardcoded component mismatches.
     * Uses cmd package resolve-activity, then dumpsys MAIN/LAUNCHER, then UiMap fallback.
     */
    private suspend fun resolveLauncherActivity(pkg: String): String = withContext(Dispatchers.IO) {
        resolvedLauncherActivity?.let { return@withContext it }

        val brief = rootRunner.runCommand(
            "cmd package resolve-activity --brief $pkg",
            ExecutionTier.SYSTEM_ROOT, 8
        )
        if (brief.ok) {
            val line = brief.output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.contains("/") && it.contains(pkg) }
            if (!line.isNullOrBlank()) {
                val activity = line.substringAfter("/", "").trim()
                if (activity.isNotBlank()) {
                    val resolved = if (activity.startsWith(".")) "$pkg$activity" else activity
                    resolvedLauncherActivity = resolved
                    Log.d(TAG, "Resolved launcher via cmd: $resolved")
                    return@withContext resolved
                }
            }
        }

        val dump = rootRunner.runCommand(
            "dumpsys package $pkg | grep -B1 -A10 'android.intent.action.MAIN'",
            ExecutionTier.SYSTEM_ROOT, 8
        )
        if (dump.ok) {
            val regex = Regex(Regex.escape(pkg) + "/([\\w.$]+)")
            val match = regex.findAll(dump.output).map { it.groupValues[1] }.firstOrNull { act ->
                act.contains("Main", ignoreCase = true) || act.contains("Home", ignoreCase = true)
            } ?: regex.find(dump.output)?.groupValues?.getOrNull(1)
            if (!match.isNullOrBlank()) {
                val resolved = if (match.startsWith(".")) "$pkg$match" else match
                resolvedLauncherActivity = resolved
                Log.d(TAG, "Resolved launcher via dumpsys: $resolved")
                return@withContext resolved
            }
        }

        val fallback = PerplexityUiMap.LAUNCHER_ACTIVITY
        resolvedLauncherActivity = fallback
        fallback
    }

    /** Ensure Perplexity app is open and in foreground. */
    suspend fun ensureAppOpen(): Boolean = withContext(Dispatchers.IO) {
        val pkg = discoverPackage() ?: return@withContext false

        if (isAppRunning()) return@withContext true

        val activity = resolveLauncherActivity(pkg)
        val launchCmd = "am start -W -n $pkg/$activity"
        val launchResult = rootRunner.runCommand(launchCmd, ExecutionTier.SYSTEM_ROOT, 12)
        val okLaunch = launchResult.ok && !launchResult.output.contains("Error type", ignoreCase = true) &&
            !launchResult.output.contains("does not exist", ignoreCase = true)

        if (!okLaunch) {
            Log.w(TAG, "Primary launch failed: ${launchResult.output}")
            rootRunner.runCommand(
                "monkey -p $pkg -c android.intent.category.LAUNCHER 1",
                ExecutionTier.SYSTEM_ROOT, 12
            )
        }

        repeat(8) {
            if (isAppRunning()) return@withContext true
            delay(800)
        }
        isAppRunning()
    }

    /** Check if Perplexity app is currently in foreground/resumed. */
    suspend fun isAppRunning(): Boolean = withContext(Dispatchers.IO) {
        try {
            val pkg = perplexityPackage ?: PerplexityUiMap.PACKAGE
            val activities = rootRunner.runCommand(
                "dumpsys activity activities | grep -E \"mResumedActivity|topResumedActivity|ResumedActivity\"",
                tier = ExecutionTier.SYSTEM_ROOT,
                timeoutSeconds = 5,
            )
            activities.ok && activities.output.contains(pkg)
        } catch (e: Exception) {
            false
        }
    }

    /** Close the Perplexity app. */
    suspend fun closeApp(): Boolean = withContext(Dispatchers.IO) {
        val pkg = perplexityPackage ?: PerplexityUiMap.PACKAGE
        val killResult = rootRunner.runCommand(
            command = "am kill-all $pkg",
            tier = ExecutionTier.SYSTEM_ROOT,
            timeoutSeconds = 10,
        )
        killResult.ok
    }

    /** Get the discovered package name. */
    fun getPackageName(): String? = perplexityPackage

    // ==================== UI hierarchy ====================

    private val uiDumpPath = "/sdcard/perp_ui_dump.xml"

    /** Run uiautomator dump and parse the hierarchy into UiNode list. */
    private suspend fun dumpUi(): List<UiNode> = withContext(Dispatchers.IO) {
        val dumpResult = rootRunner.runCommand("uiautomator dump $uiDumpPath", ExecutionTier.SYSTEM_ROOT, 10)
        if (!dumpResult.ok) {
            Log.w(TAG, "uiautomator dump failed: ${dumpResult.output}")
            emptyList()
        } else {
            val xml = readDumpFile()
            parseUiXml(xml)
        }
    }

    /** Read the dumped XML file from device storage. */
    private suspend fun readDumpFile(): String = withContext(Dispatchers.IO) {
        val catResult = rootRunner.runCommand("cat $uiDumpPath", ExecutionTier.SYSTEM_ROOT, 5)
        if (catResult.ok) catResult.output else ""
    }

    /** Parse uiautomator XML into a flat list of UiNode. */
    private fun parseUiXml(xml: String): List<UiNode> {
        val nodes = mutableListOf<UiNode>()
        if (xml.isBlank()) return nodes

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "node") {
                    nodes.add(UiNode(
                        className = parser.getAttributeValue(null, "class") ?: "",
                        resourceId = parser.getAttributeValue(null, "resource-id"),
                        contentDesc = parser.getAttributeValue(null, "content-desc"),
                        text = parser.getAttributeValue(null, "text"),
                        bounds = parser.getAttributeValue(null, "bounds"),
                        clickable = parser.getAttributeValue(null, "clickable") == "true",
                        focusable = parser.getAttributeValue(null, "focusable") == "true",
                        enabled = parser.getAttributeValue(null, "enabled") == "true",
                    ))
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse UI XML: ${e.message}")
        }
        return nodes
    }

    /** Find the first node matching resource-id. */
    private fun findByResourceId(nodes: List<UiNode>, resourceId: String): UiNode? =
        nodes.firstOrNull { it.resourceId == resourceId }

    // ==================== Interaction ====================

    /** Find and focus the composer (input-search). Returns true if focused. */
    private suspend fun focusComposer(): Boolean = withContext(Dispatchers.IO) {
        repeat(4) { _ ->
            val nodes = dumpUi()
            val composer = findByResourceId(nodes, "input-search") ?: run { delay(800); return@repeat }
            val center = composer.center() ?: run { delay(800); return@repeat }
            rootRunner.runCommand("input tap ${center[0]} ${center[1]}", ExecutionTier.SYSTEM_ROOT, 5)
            delay(700)
            // second tap to stabilize focus / dismiss transient suggestions
            rootRunner.runCommand("input tap ${center[0]} ${center[1]}", ExecutionTier.SYSTEM_ROOT, 5)
            delay(500)
            return@withContext true
        }
        false
    }

    /** Type text into the focused composer (best-effort clear then inject). */
    private suspend fun typeText(text: String): Boolean = withContext(Dispatchers.IO) {
        // best-effort clear/focus stability
        rootRunner.runCommand("input keyevent KEYCODE_MOVE_END", ExecutionTier.SYSTEM_ROOT, 3)
        rootRunner.runCommand("input keyevent KEYCODE_CTRL_A", ExecutionTier.SYSTEM_ROOT, 3)
        rootRunner.runCommand("input keyevent KEYCODE_DEL", ExecutionTier.SYSTEM_ROOT, 3)

        val escaped = escapeForInputText(text)
        val encoded = escaped.replace(" ", "%s")
        val result = rootRunner.runCommand("input text \"$encoded\"", ExecutionTier.SYSTEM_ROOT, 15)
        if (!result.ok) {
            Log.w(TAG, "input text failed: ${result.output}")
            return@withContext false
        }
        delay(1200)
        true
    }

    /** Wait for send-button to appear and be enabled after text entry. */
    private suspend fun waitForSendButton(timeoutSeconds: Int = 8): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L)
        while (System.currentTimeMillis() < deadline) {
            val nodes = dumpUi()
            val sendBtn = findByResourceId(nodes, "send-button")
            if (sendBtn != null && sendBtn.clickable && sendBtn.enabled) {
                return@withContext true
            }
            delay(700)
        }
        false
    }

    /** Tap the send button. Returns true if tapped. */
    private suspend fun tapSendButton(): Boolean = withContext(Dispatchers.IO) {
        val nodes = dumpUi()
        val sendBtn = findByResourceId(nodes, "send-button") ?: dumpUi().firstOrNull { it.contentDesc == "Send" && it.clickable } ?: return@withContext false
        val center = sendBtn.center() ?: return@withContext false
        val result = rootRunner.runCommand("input tap ${center[0]} ${center[1]}", ExecutionTier.SYSTEM_ROOT, 5)
        delay(500)
        result.ok
    }

    /** Capture all visible text nodes from the UI hierarchy as a set. */
    private suspend fun captureResponseText(): Set<String> = withContext(Dispatchers.IO) {
        val nodes = dumpUi()
        nodes.mapNotNull { it.text?.trim() }.filter { it.isNotBlank() }.toSet()
    }

    /** Extract likely assistant response text, excluding prompt and chrome. */
    private suspend fun extractAssistantResponse(sentPrompt: String): String? = withContext(Dispatchers.IO) {
        val nodes = dumpUi()
        val chrome = setOf(
            "Ask anything…", "Ask anything...", "Home", "Discover", "Spaces", "Library",
            "Voice mode", "Search", "Model selector", "Incognito mode", "Voice input",
            "Voice to voice", "Send", "More options", "New thread", "History", "Share",
            "Copy", "Rewrite", "Sources", "Related"
        )
        val promptNorm = sentPrompt.trim()
        val items = nodes.mapNotNull { n ->
            val t = n.text?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (n.resourceId?.contains("input-search") == true) return@mapNotNull null
            val top = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(n.bounds ?: "")
                ?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            t to top
        }
        val filtered = items.asSequence()
            .filter { (t, _) -> t != promptNorm }
            .filter { (t, _) -> t !in chrome }
            .filter { (_, top) -> top >= 420 }
            .sortedBy { it.second }
            .map { it.first }
            .distinct()
            .toList()
        val joined = filtered.joinToString("\n").trim()
        joined.ifBlank { null }
    }

    /**
     * Send a message to Perplexity and capture the response.
     * Runtime launcher resolution, focus/keyboard stabilization, and response stabilization included.
     */
    suspend fun sendMessage(text: String): ToolResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Sending message: $text")

        if (!ensureAppOpen()) {
            return@withContext ToolResult(false, "Failed to open Perplexity app (package/launcher not resolved)")
        }
        delay(1200)

        val baseline = captureResponseText()

        if (!focusComposer()) {
            return@withContext ToolResult(false, "Composer input-search not found or not focusable")
        }

        if (!typeText(text)) {
            return@withContext ToolResult(false, "Failed to type text into composer")
        }

        val hasSend = waitForSendButton(timeoutSeconds = 8)
        val submitted = if (hasSend) tapSendButton() else {
            rootRunner.runCommand("input keyevent KEYCODE_ENTER", ExecutionTier.SYSTEM_ROOT, 3).ok
        }
        if (!submitted) {
            rootRunner.runCommand("input keyevent KEYCODE_ENTER", ExecutionTier.SYSTEM_ROOT, 3)
        }

        val response = waitForResponseStable(
            baseline = baseline,
            sentPrompt = text,
            timeoutSeconds = 50,
            pollIntervalMs = 1500,
        )

        val success = !response.isNullOrBlank() && response.trim() != text.trim()
        val msg = SentMessage(content = text, response = response, success = success)
        messagesSent.add(msg)
        if (success) activeConversationId = msg.id

        Log.d(TAG, "sendMessage success=$success responseLen=${response?.length ?: 0}")
        return@withContext ToolResult(ok = success, output = response ?: "No response captured")
    }

    /**
     * Poll UI hierarchy until new response text appears and stabilizes.
     * Completion condition: same candidate observed in consecutive polls.
     */
    private suspend fun waitForResponseStable(
        baseline: Set<String>,
        sentPrompt: String,
        timeoutSeconds: Int,
        pollIntervalMs: Long,
    ): String? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L)
        var previousCandidate: String? = null
        var stableCount = 0
        val promptNorm = sentPrompt.trim()

        while (System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)
            val extracted = extractAssistantResponse(sentPrompt)
            val current = captureResponseText()
            val delta = (current - baseline).filter { it.isNotBlank() && it.trim() != promptNorm }
            val candidate = when {
                !extracted.isNullOrBlank() && extracted.trim() != promptNorm -> extracted.trim()
                delta.isNotEmpty() -> delta.sorted().joinToString("\n").trim()
                else -> null
            } ?: continue

            if (candidate == promptNorm) continue

            if (candidate == previousCandidate) {
                stableCount++
                if (stableCount >= 2) return@withContext candidate
            } else {
                previousCandidate = candidate
                stableCount = 0
            }
        }
        previousCandidate
    }

    /** Read the latest captured response. */
    fun readLatestResponse(): String? = messagesSent.lastOrNull()?.response

    /** Get conversation history. */
    fun getConversationHistory(): List<SentMessage> = messagesSent.toList()

    /** Get active conversation id (multi-turn context). */
    fun getActiveConversationId(): String? = activeConversationId

    // ==================== Text escaping ====================

    /** Escape text for safe use in input text command. */
    private fun escapeForInputText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("&", "\\&")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace(";", "\\;")
            .replace("|", "\\|")
            .replace("*", "\\*")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("\n", " ")
    }

    companion object {
        const val COMPOSER_RESOURCE_ID = "input-search"
        const val SEND_BUTTON_RESOURCE_ID = "send-button"
        const val RESPONSE_MIN_Y = 1933
    }
}
