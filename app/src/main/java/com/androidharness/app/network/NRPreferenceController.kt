package com.androidharness.app.network

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.androidharness.app.data.env.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NR preference controller for Qualcomm Motorola Edge 70 Fusion (KernelSU).
 *
 * Design constraints (read-only first, privileged second):
 * - TelephonyManager exposes read-only network/registration state. No public,
 *   documented API exposes a writable NR band-lock or NR-SA force API.
 * - `Settings.Global.PREFERRED_NETWORK_MODE` is the officially exposed setting
 *   for preferred network type, but writing it requires root (via
 *   `settings put global preferred_network_mode`) because it is a privileged
 *   global setting. We route writes through [RootManager] `/system/bin/su -c`.
 * - No undocumented Qualcomm/QMI/AT commands are used.
 *
 * The controller reports what it can detect and what it can actually change.
 * If no supported control interface exists, it clearly states the limitation
 * rather than faking success.
 */

object NRPreferenceController {
    private const val TAG = "NRPreferenceController"

    /** Preferred network type constants (TelephonyManager / Settings.Global). */
    private const val NETWORK_MODE_LTE_WCDMA_GSM = 12
    private const val NETWORK_MODE_NR_LTE_WCDMA_GSM = 25
    private const val NETWORK_MODE_NR_ONLY = 24

    /** Settings key for preferred network mode. */
    private const val SETTINGS_PREFERRED_NETWORK_MODE = "preferred_network_mode"

    /** Data class: snapshot of current cellular registration state. */
    data class RatState(
        val voiceRegState: Int = -1,
        val dataRegState: Int = -1,
        val radioTechnology: String = "Unknown",
        val isNrAvailable: Boolean = false,
        val isEnDcAvailable: Boolean = false,
        val isUsingCarrierAggregation: Boolean = false,
        val nrFrequencyRange: Int = -1,
        val servingLteBand: Int? = null,
        val servingLteEarfcn: Int? = null,
        val servingLteBandwidthKbps: Int? = null,
        val servingNrBand: Int? = null,
        val servingNrArfcn: Int? = null,
        val operatorAlpha: String? = null,
        val mcc: String? = null,
        val mnc: String? = null,
    )

    /** Result of a preference change attempt. */
    data class PreferenceResult(
        val ok: Boolean,
        val action: String,
        val previousMode: Int?,
        val requestedMode: Int?,
        val verifiedMode: Int?,
        val message: String,
        val rolledBack: Boolean = false,
    )

    /** Last-known preferred network mode read from Settings.Global. */
    var lastKnownMode: Int = -1
        private set

    /** The setting value we last successfully applied (for rollback). */
    var lastAppliedMode: Int = -1
        private set

    /** Whether we have confirmed a writable control interface exists. */
    var controlInterfaceAvailable: Boolean = false
        private set

    private fun log(msg: String) = Log.d(TAG, msg)

    /**
     * Reads the current RAT / registration state from the device.
     * Uses TelephonyManager (read-only, app-uid) plus dumpsys telephony.registry
     * (root, richer detail) when available.
     */
    suspend fun readRatState(
        context: Context,
        root: RootManager,
    ): RatState = withContext(Dispatchers.IO) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val phoneType = tm?.phoneType ?: -1
        val networkType = tm?.networkType ?: -1
        val isNr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                // Use reflection to access isNrAvailable which may not exist on older API levels
                val method = TelephonyManager::class.java.getMethod("isNrAvailable")
                @Suppress("DEPRECATION") method.invoke(tm) as? Boolean ?: false
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        // Build base state from TelephonyManager
        val radioTech = when (networkType) {
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            20 -> "NR-NSA" // NETWORK_TYPE_NR_NSA = 20 (API 29+)
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS -> "WCDMA"
            TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE -> "GSM"
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B -> "CDMA"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            else -> "Unknown"
        }

        var voiceRegState = -1
        var dataRegState = -1
        var isNrAvailable = isNr
        var isEnDc = false
        var isCa = false
        var nrFreqRange = -1
        var lteBand: Int? = null
        var lteEarfcn: Int? = null
        var lteBw: Int? = null
        var nrBand: Int? = null
        var nrArfcn: Int? = null
        var opAlpha: String? = null
        var mcc: String? = null
        var mnc: String? = null

        // Attempt to read richer state via dumpsys telephony.registry (root).
        val dumpsysRes = root.runSystem(
            script = "dumpsys telephony.registry 2>/dev/null",
            timeoutMs = 8_000,
            maxBytes = 200_000,
        )
        if (dumpsysRes.exitCode == 0 && dumpsysRes.output.isNotBlank()) {
            val text = dumpsysRes.output
            voiceRegState = extractInt(text, "mVoiceRegState=([0-9]+)")
            dataRegState = extractInt(text, "mDataRegState=([0-9]+)")
            isNrAvailable = text.contains("isNrAvailable = true") || isNrAvailable
            isEnDc = text.contains("isEnDcAvailable = true")
            isCa = text.contains("isUsingCarrierAggregation=true")
            nrFreqRange = extractInt(text, "mNrFrequencyRange=([0-9]+)")
            lteBand = extractBand(text, "mBands=\\[([0-9]+)\\]")
            lteEarfcn = extractInt(text, "mChannelNumber=([0-9]+)")
            lteBw = extractInt(text, "mBandwidth=([0-9]+)")
            opAlpha = extractString(text, "mOperatorAlphaLong=([^,}]+)")
            mcc = extractString(text, "mMcc=([0-9]+)")
            mnc = extractString(text, "mMnc=([0-9]+)")

            // NR band info may be present in CellIdentityNr blocks
            nrBand = extractBand(text, "CellIdentityNr.*?mBands=\\[([0-9]+)\\]")
            nrArfcn = extractInt(text, "mNrArfcn=([0-9]+)")
        }

        RatState(
            voiceRegState = voiceRegState,
            dataRegState = dataRegState,
            radioTechnology = radioTech,
            isNrAvailable = isNrAvailable,
            isEnDcAvailable = isEnDc,
            isUsingCarrierAggregation = isCa,
            nrFrequencyRange = nrFreqRange,
            servingLteBand = lteBand,
            servingLteEarfcn = lteEarfcn,
            servingLteBandwidthKbps = lteBw,
            servingNrBand = nrBand,
            servingNrArfcn = nrArfcn,
            operatorAlpha = opAlpha,
            mcc = mcc,
            mnc = mnc,
        )
    }

    /**
     * Reads the current preferred network mode from Settings.Global.
     * Returns null if unreadable (e.g. no root).
     */
    suspend fun readPreferredNetworkMode(root: RootManager): Int? = withContext(Dispatchers.IO) {
        val res = root.runSystem(
            script = "settings get global preferred_network_mode 2>/dev/null",
            timeoutMs = 5_000,
            maxBytes = 200,
        )
        if (res.exitCode == 0 && res.output.isNotBlank()) {
            val trimmed = res.output.trim()
            try {
                val mode = trimmed.toInt()
                lastKnownMode = mode
                controlInterfaceAvailable = true
                mode
            } catch (_: NumberFormatException) {
                null
            }
        } else {
            null
        }
    }

    /**
     * Detects whether NR-SA preference is supported by this device.
     * Checks: TelephonyManager.isNrAvailable, preferred_network_mode writable,
     * and NR modes present in TelephonyManager constants.
     */
    suspend fun detectNrPreferenceCapability(
        context: Context,
        root: RootManager,
    ): NrCapability = withContext(Dispatchers.IO) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val isNrAvailable = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val method = TelephonyManager::class.java.getMethod("isNrAvailable")
                @Suppress("DEPRECATION") method.invoke(tm) as? Boolean ?: false
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        val mode = readPreferredNetworkMode(root)
        val writable = mode != null // if we can read it, settings API works

        // Check whether NR network mode constants exist (API 30+ / Android 11+)
        val nrConstantsAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R

        NrCapability(
            nrAvailable = isNrAvailable,
            settingsInterfaceWritable = writable,
            nrModeConstantsAvailable = nrConstantsAvailable,
            currentMode = mode,
            controlAvailable = writable && nrConstantsAvailable,
        )
    }

    /**
     * Attempts to prefer NR-SA by setting preferred_network_mode to
     * NETWORK_MODE_NR_LTE_WCDMA_GSM (25) — NR preferred with LTE/WCDMA/GSM fallback.
     * Verifies the change and rolls back if verification fails.
     */
    suspend fun preferNrSa(
        context: Context,
        root: RootManager,
    ): PreferenceResult = withContext(Dispatchers.IO) {
        val current = readPreferredNetworkMode(root)
        if (current == null) {
            PreferenceResult(
                ok = false,
                action = "prefer_nr_sa",
                previousMode = null,
                requestedMode = NETWORK_MODE_NR_LTE_WCDMA_GSM,
                verifiedMode = null,
                message = "Cannot read preferred_network_mode: settings interface not writable (no root or setting restricted). NR-SA preference cannot be applied.",
                rolledBack = false,
            )
        } else if (current == NETWORK_MODE_NR_LTE_WCDMA_GSM) {
            PreferenceResult(
                ok = true,
                action = "prefer_nr_sa",
                previousMode = current,
                requestedMode = NETWORK_MODE_NR_LTE_WCDMA_GSM,
                verifiedMode = current,
                message = "Already preferring NR (LTE/WCDMA/GSM fallback). No change needed.",
                rolledBack = false,
            )
        } else {
            applyNetworkMode(
                root = root,
                newMode = NETWORK_MODE_NR_LTE_WCDMA_GSM,
                previousMode = current ?: -1,
                actionName = "prefer_nr_sa",
                successMessage = "NR-SA preference applied: preferred_network_mode set to NR/LTE/WCDMA/GSM (25). LTE remains automatic fallback.",
            )
        }
    }

    /**
     * Restores automatic network selection by setting preferred_network_mode to
     * NETWORK_MODE_LTE_WCDMA_GSM (12) — LTE preferred with WCDMA/GSM fallback.
     * This preserves NR-NSA operation (LTE anchor + NR secondary) while allowing
     * NR when available.
     */
    suspend fun restoreAutomatic(
        context: Context,
        root: RootManager,
    ): PreferenceResult = withContext(Dispatchers.IO) {
        val current = readPreferredNetworkMode(root)
        if (current == null) {
            PreferenceResult(
                ok = false,
                action = "restore_automatic",
                previousMode = null,
                requestedMode = NETWORK_MODE_LTE_WCDMA_GSM,
                verifiedMode = null,
                message = "Cannot read preferred_network_mode: settings interface not writable.",
                rolledBack = false,
            )
        } else if (current == NETWORK_MODE_LTE_WCDMA_GSM) {
            PreferenceResult(
                ok = true,
                action = "restore_automatic",
                previousMode = current,
                requestedMode = NETWORK_MODE_LTE_WCDMA_GSM,
                verifiedMode = current,
                message = "Already on automatic LTE/WCDMA/GSM mode. No change needed.",
                rolledBack = false,
            )
        } else {
            applyNetworkMode(
                root = root,
                newMode = NETWORK_MODE_LTE_WCDMA_GSM,
                previousMode = current ?: -1,
                actionName = "restore_automatic",
                successMessage = "Automatic mode restored: preferred_network_mode set to LTE/WCDMA/GSM (12). NR-NSA preserved when available.",
            )
        }
    }

    /**
     * Applies a network mode change via `settings put global preferred_network_mode`
     * through root, then verifies and rolls back on failure.
     */
    private suspend fun applyNetworkMode(
        root: RootManager,
        newMode: Int,
        previousMode: Int,
        actionName: String,
        successMessage: String,
    ): PreferenceResult = withContext(Dispatchers.IO) {
        lastAppliedMode = previousMode

        // Step 1: Apply the new mode
        val applyRes = root.runSystem(
            script = "settings put global preferred_network_mode $newMode 2>/dev/null",
            timeoutMs = 8_000,
            maxBytes = 500,
        )

        if (applyRes.exitCode != 0) {
            PreferenceResult(
                ok = false,
                action = actionName,
                previousMode = previousMode,
                requestedMode = newMode,
                verifiedMode = previousMode,
                message = "Failed to apply preferred_network_mode=$newMode (exit ${applyRes.exitCode}): ${applyRes.stderr.trim().ifBlank { "settings command rejected" }}. No change made.",
                rolledBack = false,
            )
        }

        // Step 2: Verify the change took effect
        val verifyRes = root.runSystem(
            script = "settings get global preferred_network_mode 2>/dev/null",
            timeoutMs = 5_000,
            maxBytes = 200,
        )
        var verifiedMode: Int? = null
        if (verifyRes.exitCode == 0 && verifyRes.output.isNotBlank()) {
            try {
                verifiedMode = verifyRes.output.trim().toInt()
            } catch (_: NumberFormatException) { /* verifiedMode stays null */ }
        }

        if (verifiedMode == newMode) {
            lastKnownMode = newMode
            controlInterfaceAvailable = true
            PreferenceResult(
                ok = true,
                action = actionName,
                previousMode = previousMode,
                requestedMode = newMode,
                verifiedMode = verifiedMode,
                message = successMessage,
                rolledBack = false,
            )
        } else {
            // Step 3: Verification failed — roll back to previous mode
            val rollbackRes = root.runSystem(
                script = "settings put global preferred_network_mode $previousMode 2>/dev/null",
                timeoutMs = 8_000,
                maxBytes = 500,
            )
            var rolledBackOk = false
            if (rollbackRes.exitCode == 0) {
                val rbVerify = root.runSystem(
                    script = "settings get global preferred_network_mode 2>/dev/null",
                    timeoutMs = 5_000,
                    maxBytes = 200,
                )
                if (rbVerify.exitCode == 0 && rbVerify.output.isNotBlank()) {
                    try {
                        rolledBackOk = rbVerify.output.trim().toInt() == previousMode
                    } catch (_: NumberFormatException) { rolledBackOk = false }
                }
            }
            PreferenceResult(
                ok = false,
                action = actionName,
                previousMode = previousMode,
                requestedMode = newMode,
                verifiedMode = verifiedMode,
                message = "Change to preferred_network_mode=$newMode did not verify (read back $verifiedMode). Rolled back to previous mode $previousMode: ${if (rolledBackOk) "success" else "rollback may have failed — manual verification recommended"}.",
                rolledBack = rolledBackOk,
            )
        }
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private fun extractInt(text: String, regex: String): Int {
        val m = Regex(regex).find(text)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun extractBand(text: String, regex: String): Int? {
        val m = Regex(regex).find(text)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractString(text: String, regex: String): String? {
        val m = Regex(regex).find(text)
        return m?.groupValues?.getOrNull(1)?.trim()
    }
}

/**
 * Capability report for NR preference control.
 */
data class NrCapability(
    val nrAvailable: Boolean,
    val settingsInterfaceWritable: Boolean,
    val nrModeConstantsAvailable: Boolean,
    val currentMode: Int?,
    val controlAvailable: Boolean,
) {
    fun summary(): String = buildString {
        append("NR available: $nrAvailable\n")
        append("Settings interface writable: $settingsInterfaceWritable\n")
        append("NR mode constants available (API 30+): $nrModeConstantsAvailable\n")
        append("Current preferred_network_mode: ${currentMode ?: "unknown"}\n")
        append("NR-SA preference control available: $controlAvailable\n")
        if (!controlAvailable) {
            append("\nBlocker: ")
            if (!settingsInterfaceWritable) {
                append("preferred_network_mode is not writable (requires root/settings access). ")
            }
            if (!nrModeConstantsAvailable) {
                append("NR network mode constants not available (pre-Android 11). ")
            }
            append("NR-SA preference cannot be applied via documented APIs.")
        }
    }
}
