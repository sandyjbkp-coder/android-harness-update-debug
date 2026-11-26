package com.androidharness.app.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChatGptAppTool : Tool {
    override val name = "chatgpt_app"
    override val description = "ChatGPT app automation tool (stub - not fully implemented)."
    override val parametersSchema: JsonObject = buildJsonObject { put("type", "object") }
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult =
        ToolResult(false, "chatgpt_app tool is not implemented in this recovery build.")
}
