package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ChatBackupItem(
    val sessionId: String,
    val title: String,
    val messageCount: Int,
    val alreadyOnDevice: Boolean = false
)

@Composable
fun BackupRestoreScreen(
    chats: List<ChatBackupItem>,
    onExport: (selected: List<String>) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    exportProgress: Float = 0f,
    isExporting: Boolean = false
) {
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val allSelected = chats.isNotEmpty() && chats.all { selected[it.sessionId] == true }

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
                    "Backup & restore",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {
            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = {
                        val ids = selected.filter { it.value }.keys.toList()
                        if (ids.isNotEmpty()) onExport(ids)
                    },
                    enabled = selected.any { it.value } && !isExporting
                ) {
                    Icon(Icons.Outlined.Archive, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Export selected")
                }
                OutlinedButton(onClick = onImport, enabled = !isExporting) {
                    Icon(Icons.Outlined.Unarchive, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Import")
                }
            }

            if (isExporting) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { exportProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Exporting... ${(exportProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Select all
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { checked ->
                        chats.forEach { selected[it.sessionId] = checked }
                    }
                )
                Text("Select all (${chats.size} chats)", style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Chat list
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(chats, key = { it.sessionId }) { chat ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = selected[chat.sessionId] == true,
                            onCheckedChange = { selected[chat.sessionId] = it }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                chat.title.ifBlank { "Untitled chat" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                            Text(
                                "${chat.messageCount} messages",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (chat.alreadyOnDevice) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("On device") }
                            )
                        }
                    }
                }
            }
        }
    }
}
