package com.androidharness.app.appcontrol

import android.content.Context
import com.androidharness.app.agent.RootRunner
import com.androidharness.app.data.env.ExecutionTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Minimal Phase-2 adapter stub for ChatGPT app - compile-safe.
 * Preserves API surface used by tools; runtime behavior requires device testing.
 */
class ChatGptAppAdapter(
    private val context: Context,
    private val rootRunner: RootRunner = RootRunner(context),
) {
    val chatGptPackage: String = "com.openai.chatgpt"

    data class UiNode(
        val text: String = "",
        val contentDesc: String = "",
        val className: String = "",
        val resourceId: String = "",
        val bounds: Rect = Rect(0,0,0,0),
        val clickable: Boolean = false,
        val focusable: Boolean = false,
        val enabled: Boolean = false,
        val scrollable: Boolean = false,
    )

    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun center(): Pair<Int, Int> = ((left + right) / 2) to ((top + bottom) / 2)
    }

    data class CmdOut(val exitCode: Int, val output: String, val stderr: String = "")

    data class ChatGptStatus(
        val packageInstalled: Boolean = false,
        val isForeground: Boolean = false,
        val readyForPrompt: Boolean = false,
        val loginRequired: Boolean = false,
        val networkIssue: Boolean = false,
        val uiDumpAvailable: Boolean = false,
        val details: String = "",
        val resolvedPackage: String? = null,
    )

    data class SendPromptResult(
        val ok: Boolean,
        val responseText: String? = null,
        val details: String = "",
        val evidence: Map<String, String> = emptyMap(),
    )

    suspend fun discoverChatGptPackage(): String? = null
    suspend fun getStatus(): ChatGptStatus = ChatGptStatus(details = "ChatGPT adapter stub: not fully implemented")
    suspend fun openChatGpt(): Boolean = false
    suspend fun openNewChat(): Boolean = false
    suspend fun sendPrompt(prompt: String, waitForResponseMs: Long = 20000): SendPromptResult =
        SendPromptResult(ok = false, details = "ChatGPT adapter stub: not implemented")
    suspend fun readLatestResponse(): String? = null
}
