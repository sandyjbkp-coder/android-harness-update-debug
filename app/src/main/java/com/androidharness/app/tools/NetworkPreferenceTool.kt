package com.androidharness.app.tools

import android.content.Context
import com.androidharness.app.data.env.RootManager
import com.androidharness.app.data.env.ShellTierRouter
import com.androidharness.app.network.NRPreferenceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Network preference control tool for NR-SA / LTE fallback management.
 *
 * Exposes four read-safe / privileged commands through AndroidHarness:
 *   - "network status"
 *   - "network nr preference status"
 *   - "network prefer nr sa"
 *   - "network restore automatic"
 *
 * Root operations route through RootManager (/system/bin/su -c). No undocumented
 * Qualcomm/QMI/AT commands are used. If the control interface is unavailable,
 * the tool reports the limitation rather than faking success.
 */
class NetworkPreferenceTool(
    private val context: Context,
    private val root: RootManager,
    private val router: ShellTierRouter,
) : Tool {

    override val name = "network_preference"
    override val description =
        "Cellular network preference controller for NR-SA / LTE fallback. " +
            "Commands: network status | network nr preference status | " +
            "network prefer nr sa | network restore automatic. " +
            "Root operations route through /system/bin/su -c via RootManager."

    override val parametersSchema = Schema.obj(
        mapOf(
            "command" to Schema.string(
                "One of: 'network status', 'network nr preference status', " +
                    "'network prefer nr sa', 'network restore automatic'.",
            ),
            "confirm" to Schema.boolean(
                "Set true to confirm a radio-mode change after reviewing the proposed change. " +
                    "Required for 'network prefer nr sa' and 'network restore automatic'. Default false.",
            ),
        ),
        required = listOf("command"),
    )

    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val command = args["command"]?.jsonPrimitive?.content?.trim() ?: ""
            val confirm = args["confirm"]?.jsonPrimitive?.booleanOrNull ?: false

            val result = when (command.lowercase()) {
                "network status" -> runNetworkStatus()
                "network nr preference status" -> runNrPreferenceStatus()
                "network prefer nr sa" -> {
                    if (!confirm) {
                        ProposedChangeResult(
                            action = "prefer_nr_sa",
                            proposal =
                                "Will set preferred_network_mode to NR/LTE/WCDMA/GSM (25). " +
                                    "LTE remains automatic fallback. NR-SA will be preferred when " +
                                    "supported by modem and provisioned by carrier. " +
                                    "Re-run with confirm=true to apply.",
                            requiresConfirmation = true,
                        ).toToolResult()
                    } else {
                        runPreferNrSa()
                    }
                }
                "network restore automatic" -> {
                    if (!confirm) {
                        ProposedChangeResult(
                            action = "restore_automatic",
                            proposal =
                                "Will set preferred_network_mode to LTE/WCDMA/GSM (12). " +
                                    "NR-NSA operation preserved (LTE anchor + NR secondary when available). " +
                                    "Re-run with confirm=true to apply.",
                            requiresConfirmation = true,
                        ).toToolResult()
                    } else {
                        runRestoreAutomatic()
                    }
                }
                else -> ToolResult(
                    ok = false,
                    output = "Unknown command: '$command'. " +
                        "Valid commands: network status | network nr preference status | " +
                        "network prefer nr sa | network restore automatic",
                )
            }

            result
        }

    private suspend fun runNetworkStatus(): ToolResult {
        val controller = NRPreferenceController
        val ratState = controller.readRatState(context, root)
        val mode = controller.readPreferredNetworkMode(root)

        val sb = StringBuilder()
        sb.append("=== Network Status ===\n")
        sb.append("Radio technology: ${ratState.radioTechnology}\n")
        sb.append("Voice registration: ${registrationLabel(ratState.voiceRegState)}\n")
        sb.append("Data registration: ${registrationLabel(ratState.dataRegState)}\n")
        sb.append("NR available: ${ratState.isNrAvailable}\n")
        sb.append("EN-DC available: ${ratState.isEnDcAvailable}\n")
        sb.append("Carrier aggregation: ${ratState.isUsingCarrierAggregation}\n")
        sb.append("NR frequency range: ${ratState.nrFrequencyRange}\n")
        if (ratState.servingLteBand != null) {
            sb.append("Serving LTE band: B${ratState.servingLteBand}\n")
            sb.append("Serving LTE EARFCN: ${ratState.servingLteEarfcn ?: "?"}\n")
            sb.append("Serving LTE bandwidth: ${ratState.servingLteBandwidthKbps ?: "?"} kHz\n")
        }
        if (ratState.servingNrBand != null) {
            sb.append("Serving NR band: n${ratState.servingNrBand}\n")
            sb.append("Serving NR-ARFCN: ${ratState.servingNrArfcn ?: "?"}\n")
        }
        sb.append("Operator: ${ratState.operatorAlpha ?: "?"} (MCC ${ratState.mcc ?: "?"} / MNC ${ratState.mnc ?: "?"})\n")
        sb.append("Preferred network mode: ${mode ?: "unreadable"} (${modeLabel(mode)})\n")
        sb.append("\nNOTE: Read-only detection. No modem configuration was changed.\n")

        return ToolResult(ok = true, output = sb.toString())
    }

    private suspend fun runNrPreferenceStatus(): ToolResult {
        val controller = NRPreferenceController
        val cap = controller.detectNrPreferenceCapability(context, root)
        val ratState = controller.readRatState(context, root)

        val sb = StringBuilder()
        sb.append("=== NR Preference Status ===\n")
        sb.append(cap.summary())
        sb.append("\n")
        sb.append("Current RAT: ${ratState.radioTechnology}\n")
        sb.append("Serving LTE band: ${ratState.servingLteBand?.let { "B$it" } ?: "unknown"}\n")
        sb.append("Serving NR band: ${ratState.servingNrBand?.let { "n$it" } ?: "unknown"}\n")
        sb.append("EN-DC state: ${if (ratState.isEnDcAvailable) "Connected" else "Not available"}\n")
        sb.append("\n")

        if (!cap.controlAvailable) {
            sb.append("RESULT: NR-SA preference is NOT available via documented APIs.\n")
            sb.append("The controller provides detection and reporting only.\n")
        } else {
            sb.append("RESULT: NR-SA preference control is available.\n")
            sb.append("Use 'network prefer nr sa' (confirm=true) to apply.\n")
        }

        return ToolResult(ok = true, output = sb.toString())
    }

    private suspend fun runPreferNrSa(): ToolResult {
        val controller = NRPreferenceController
        val cap = controller.detectNrPreferenceCapability(context, root)
        if (!cap.controlAvailable) {
            return ToolResult(
                ok = false,
                output = "NR-SA preference cannot be applied: ${cap.summary().lines().lastOrNull() ?: "control interface unavailable"}\n" +
                    "No radio settings were changed.\n",
            )
        }
        val result = controller.preferNrSa(context, root)
        return ToolResult(
            ok = result.ok,
            output = buildString {
                append("=== NR-SA Preference ===\n")
                append("Action: ${result.action}\n")
                append("Previous mode: ${result.previousMode ?: "unknown"} (${modeLabel(result.previousMode)})\n")
                append("Requested mode: ${result.requestedMode ?: "unknown"} (${modeLabel(result.requestedMode)})\n")
                append("Verified mode: ${result.verifiedMode ?: "unknown"} (${modeLabel(result.verifiedMode)})\n")
                append("Rolled back: ${result.rolledBack}\n")
                append("Message: ${result.message}\n")
            },
        )
    }

    private suspend fun runRestoreAutomatic(): ToolResult {
        val controller = NRPreferenceController
        val cap = controller.detectNrPreferenceCapability(context, root)
        if (!cap.controlAvailable) {
            return ToolResult(
                ok = false,
                output = "Automatic restore cannot be applied: control interface unavailable.\n" +
                    "No radio settings were changed.\n",
            )
        }
        val result = controller.restoreAutomatic(context, root)
        return ToolResult(
            ok = result.ok,
            output = buildString {
                append("=== Restore Automatic ===\n")
                append("Action: ${result.action}\n")
                append("Previous mode: ${result.previousMode ?: "unknown"} (${modeLabel(result.previousMode)})\n")
                append("Requested mode: ${result.requestedMode ?: "unknown"} (${modeLabel(result.requestedMode)})\n")
                append("Verified mode: ${result.verifiedMode ?: "unknown"} (${modeLabel(result.verifiedMode)})\n")
                append("Rolled back: ${result.rolledBack}\n")
                append("Message: ${result.message}\n")
            },
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun registrationLabel(state: Int): String = when (state) {
        0 -> "IN_SERVICE"
        1 -> "NOT_REGISTERED"
        2 -> "SEARCHING"
        3 -> "DENIED"
        4 -> "UNKNOWN"
        else -> "UNKNOWN ($state)"
    }

    private fun modeLabel(mode: Int?): String = when (mode) {
        12 -> "LTE/WCDMA/GSM (auto fallback)"
        24 -> "NR only"
        25 -> "NR/LTE/WCDMA/GSM (NR preferred)"
        null -> "unknown"
        else -> "other ($mode)"
    }

    /** Result for a proposed change awaiting user confirmation. */
    private class ProposedChangeResult(
        val action: String,
        val proposal: String,
        val requiresConfirmation: Boolean,
    ) {
        fun toToolResult(): ToolResult {
            return ToolResult(
                ok = false,
                output = buildString {
                    append("=== Proposed Change (confirmation required) ===\n")
                    append("Action: $action\n")
                    append("Proposal: $proposal\n")
                    append("Status: AWAITING CONFIRMATION — no radio settings changed.\n")
                    append("Re-run with confirm=true to apply.\n")
                },
            )
        }
    }

    override fun isAvailable(ctx: ToolContext): Boolean = true
}
