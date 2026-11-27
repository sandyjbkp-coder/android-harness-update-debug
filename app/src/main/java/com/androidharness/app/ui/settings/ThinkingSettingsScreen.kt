package com.androidharness.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.ReasoningEffortsRepository
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ThinkingSettingsScreen(
    currentLevel: String = "medium",
    modelId: String = "gemini-3.8-flash",
    onSelectLevel: (String) -> Unit = {},
    onClose: () -> Unit
) {
    var selected by remember { mutableStateOf(currentLevel) }
    var efforts by remember { mutableStateOf(listOf("Disabled", "Low", "Medium", "High", "X‑High")) }

    // Load the dynamic efforts list
    LaunchedEffect(modelId) {
        ReasoningEffortsRepository.efforts.collectLatest { list ->
            if (list.isNotEmpty()) {
                efforts = list.map { effort ->
                    when (effort) {
                        "none" -> "Disabled"
                        "xhigh" -> "X‑High"
                        else -> effort.replaceFirstChar { it.uppercase() }
                    }
                }
            }
        }
        ReasoningEffortsRepository.refresh(modelId)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Thinking level") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Select how deeply the model deliberates before taking actions:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                efforts.forEach { label ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected == label) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = label }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == label,
                                onClick = { selected = label }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected == label) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSelectLevel(selected)
                onClose()
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Cancel") }
        }
    )
}
