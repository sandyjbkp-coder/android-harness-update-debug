package com.androidharness.app.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class NetworkSpeedTool : Tool {
    override val name = "network_speed"
    override val description = "Network speed/latency probe (stub - not fully implemented)."
    override val parametersSchema: JsonObject = buildJsonObject { put("type", "object") }
    override val isReadOnly = true
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult =
        ToolResult(false, "network_speed tool is not implemented in this recovery build.")
}
