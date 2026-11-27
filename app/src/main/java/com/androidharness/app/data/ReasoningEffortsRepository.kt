package com.androidharness.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository that manages supported reasoning efforts.
 *
 * Exposes reasoning efforts from the model provider:
 * none, low, medium, high, xhigh.
 */
object ReasoningEffortsRepository {
    private val _efforts = MutableStateFlow<List<String>>(
        listOf("none", "low", "medium", "high", "xhigh")
    )
    val efforts: StateFlow<List<String>> = _efforts

    fun refresh(activeModelId: String) {
        val list = when {
            activeModelId.contains("thinking", ignoreCase = true) ||
            activeModelId.contains("reasoning", ignoreCase = true) ||
            activeModelId.contains("opus", ignoreCase = true) ||
            activeModelId.contains("gemini-3", ignoreCase = true) ||
            activeModelId.contains("r1", ignoreCase = true) -> {
                listOf("none", "low", "medium", "high", "xhigh")
            }
            else -> {
                listOf("none", "low", "medium", "high")
            }
        }
        _efforts.value = list
    }
}
