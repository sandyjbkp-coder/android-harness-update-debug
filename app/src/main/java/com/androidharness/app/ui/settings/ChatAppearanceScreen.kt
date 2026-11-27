package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

enum class ChatSpacing(val label: String, val verticalPadding: Int) {
    COMPACT("Compact", 2),
    DEFAULT("Default", 6),
    COMFORTABLE("Comfortable", 12)
}

@Composable
fun ChatAppearanceScreen(
    initialSpacing: ChatSpacing = ChatSpacing.DEFAULT,
    initialLineSpacing: Float = 1.4f,
    initialSideMargin: Int = 12,
    onSave: (ChatSpacing, Float, Int) -> Unit,
    onBack: () -> Unit
) {
    var spacing by remember { mutableStateOf(initialSpacing) }
    var lineSpacing by remember { mutableFloatStateOf(initialLineSpacing) }
    var sideMargin by remember { mutableIntStateOf(initialSideMargin) }

    Scaffold(
        topBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(
                    "Chat layout",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                TextButton(onClick = { onSave(spacing, lineSpacing, sideMargin) }) {
                    Text("Save")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // --- Spacing ---
            Text("Spacing", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ChatSpacing.entries.forEachIndexed { index, s ->
                    SegmentedButton(
                        selected = spacing == s,
                        onClick = { spacing = s },
                        shape = SegmentedButtonDefaults.itemShape(index, ChatSpacing.entries.size)
                    ) { Text(s.label) }
                }
            }

            // --- Line spacing ---
            Text("Line spacing: ${"%.1f".format(lineSpacing)}×", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = lineSpacing,
                onValueChange = { lineSpacing = (it * 10).roundToInt() / 10f },
                valueRange = 1.2f..2.0f,
                steps = 7
            )

            // --- Side margins ---
            Text("Side margins: ${sideMargin} dp", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = sideMargin.toFloat(),
                onValueChange = { sideMargin = it.roundToInt() },
                valueRange = 4f..40f,
                steps = 8
            )

            // --- Live preview ---
            Text("Preview", style = MaterialTheme.typography.labelLarge)
            Surface(
                tonalElevation = 1.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = sideMargin.dp,
                        vertical = spacing.verticalPadding.dp
                    )
                ) {
                    // User bubble
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(bottom = spacing.verticalPadding.dp)
                    ) {
                        Text(
                            "How do I fix this bug?",
                            modifier = Modifier.padding(12.dp),
                            lineHeight = (14 * lineSpacing).sp,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // Assistant bubble
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            "I found the issue in line 42. The variable was not initialized before use. Here is the fix...",
                            modifier = Modifier.padding(12.dp),
                            lineHeight = (14 * lineSpacing).sp,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
