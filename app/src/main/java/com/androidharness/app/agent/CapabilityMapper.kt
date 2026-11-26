package com.androidharness.app.agent

import android.content.Context
import com.androidharness.app.data.env.RootManager
import com.androidharness.app.data.env.RootState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps agent capabilities to specific tasks and execution contexts.
 */
class CapabilityMapper(
    private val context: Context
) {
    private val capabilities = ConcurrentHashMap<String, AgentCapability>()
    private val capabilityMapping = ConcurrentHashMap<String, String>()

    suspend fun initializeCapabilities() = withContext(Dispatchers.IO) {
        val rootManager = RootManager(context)
        val rootAvailable = rootManager.state.value == RootState.GRANTED
        val linuxDeployed = try { File("/data/local/tmp/androidharness/com.androidharness.app.debug/linux/bin/bash").exists() } catch (_: Exception) { false }
        val shellAvailable = linuxDeployed || File(context.filesDir, "linux/bin/bash").exists()
        val uiAvailable = isUiAvailable()
        val webAvailable = isWebAvailable()

        val coreCapabilities = listOf(
            AgentCapability("linux", "Linux shell access", shellAvailable, listOf("shell", "bash", "git", "python3", "node", "npm")),
            AgentCapability("android", "Android system access", rootAvailable, listOf("pm", "am", "settings", "dumpsys")),
            AgentCapability("shell", "Basic shell access", shellAvailable, listOf("sh", "su", "chmod", "ls", "cat")),
            AgentCapability("ui", "UI interaction", uiAvailable, listOf("input", "uiautomator", "screencap")),
            AgentCapability("web", "Web access", webAvailable, listOf("browser", "web_search", "web_fetch")),
            AgentCapability("root", "Root access", rootAvailable, listOf("su", "mount", "setenforce")),
        )
        coreCapabilities.forEach { capabilities[it.name] = it }
        mapStandardCapabilities()
    }

    fun mapTaskToCapabilities(taskType: String): CapabilityMapResult {
        val required = getRequiredCapabilities(taskType)
        val available = required.filter { capabilities[it]?.isAvailable == true }
        val missing = required.filter { capabilities[it]?.isAvailable != true }
        return CapabilityMapResult(
            taskType = taskType,
            requiredCapabilities = required,
            availableCapabilities = available,
            missingCapabilities = missing,
            canExecute = missing.isEmpty(),
            suggestedAlternatives = suggestAlternatives(taskType, missing)
        )
    }

    fun getRequiredCapabilities(taskType: String): List<String> = capabilityMapping[taskType]?.let { listOf(it) } ?: when (taskType.lowercase()) {
        "system_info", "device_info" -> listOf("android", "shell")
        "package_management" -> listOf("android", "shell", "root")
        "file_operations" -> listOf("shell", "linux")
        "network_research" -> listOf("web", "shell")
        "ui_automation" -> listOf("ui", "android")
        "build_operations" -> listOf("linux", "shell")
        "system_configuration" -> listOf("root", "android")
        "perplexity_research" -> listOf("ui", "web")
        else -> listOf("shell")
    }

    fun hasCapability(name: String): Boolean = capabilities[name]?.isAvailable == true
    fun getCapability(name: String): AgentCapability? = capabilities[name]
    fun getAllCapabilities(): List<AgentCapability> = capabilities.values.toList()

    fun updateCapability(name: String, isAvailable: Boolean) {
        capabilities[name]?.let { capabilities[name] = it.copy(isAvailable = isAvailable) }
    }

    private fun mapStandardCapabilities() {
        capabilityMapping["system_info"] = "android"
        capabilityMapping["device_info"] = "android"
        capabilityMapping["package_management"] = "android"
        capabilityMapping["file_operations"] = "shell"
        capabilityMapping["network_research"] = "web"
        capabilityMapping["ui_automation"] = "ui"
        capabilityMapping["build_operations"] = "linux"
        capabilityMapping["system_configuration"] = "root"
        capabilityMapping["perplexity_research"] = "ui"
    }

    private fun suggestAlternatives(taskType: String, missing: List<String>): List<String> {
        val alternatives = mutableListOf<String>()
        if ("root" in missing) {
            alternatives.add("Use non-root alternatives where possible")
            alternatives.add("Request user approval for root operations")
        }
        if ("ui" in missing) alternatives.add("Use shell-based alternatives")
        if ("web" in missing) alternatives.add("Use local information sources")
        return alternatives
    }

    private fun isUiAvailable(): Boolean = try {
        val uiAutomator = File("/system/bin/uiautomator")
        uiAutomator.exists() && uiAutomator.canExecute()
    } catch (_: Exception) { false }

    private fun isWebAvailable(): Boolean = true

    fun getCapabilitiesSummary(): Map<String, Any> = mapOf(
        "totalCapabilities" to capabilities.size,
        "availableCapabilities" to capabilities.values.count { it.isAvailable },
        "unavailableCapabilities" to capabilities.values.count { !it.isAvailable },
        "capabilityNames" to capabilities.keys.toList(),
        "rootAvailable" to hasCapability("root"),
        "linuxAvailable" to hasCapability("linux"),
        "androidAvailable" to hasCapability("android")
    )
}

@Serializable
data class AgentCapability(
    val name: String,
    val description: String,
    var isAvailable: Boolean,
    val tools: List<String> = emptyList()
)

@Serializable
data class CapabilityMapResult(
    val taskType: String,
    val requiredCapabilities: List<String>,
    val availableCapabilities: List<String>,
    val missingCapabilities: List<String>,
    val canExecute: Boolean,
    val suggestedAlternatives: List<String> = emptyList()
)
