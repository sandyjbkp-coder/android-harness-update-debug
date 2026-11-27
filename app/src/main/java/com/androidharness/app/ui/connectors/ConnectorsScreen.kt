package com.androidharness.app.ui.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidharness.app.ui.modals.CapabilityDialog
import kotlinx.coroutines.*
import java.net.URL

data class ConnectorItem(
    val id: String,
    val name: String,
    val provider: String,
    val description: String,
    val icon: ImageVector,
    val isConnected: Boolean,
    val capabilities: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
    onBack: () -> Unit
) {
    val connectors = remember {
        listOf(
            ConnectorItem(
                id = "gmail",
                name = "Gmail",
                provider = "Google Workspace",
                description = "Send, read, and manage inbox emails and threads with draft synchronization.",
                icon = Icons.Outlined.Email,
                isConnected = true,
                capabilities = listOf(
                    "Search and query inbox with Gmail operators (e.g., from:, has:attachment)",
                    "Read full email threads and parse structured attachments",
                    "Send new emails and reply to active conversations",
                    "Manage labels, archive emails, and organize message categories",
                    "Draft email responses for human review before sending"
                )
            ),
            ConnectorItem(
                id = "chrome",
                name = "Google Chrome",
                provider = "Browser Automation",
                description = "Automate web browsing, remote DOM inspection, and session debugging.",
                icon = Icons.Outlined.Language,
                isConnected = true,
                capabilities = listOf(
                    "Navigate to web pages and monitor page load events",
                    "Extract structured HTML, DOM nodes, and text content",
                    "Inject and execute JavaScript expressions inside active tabs",
                    "Capture off-screen full-page screenshots and PDF reports",
                    "Automate form fill, click interactions, and cookie state management"
                )
            ),
            ConnectorItem(
                id = "meta_ads",
                name = "Meta Ads",
                provider = "Meta Business",
                description = "Manage marketing campaigns, ad sets, and analytics telemetry across Facebook & Instagram.",
                icon = Icons.Outlined.Campaign,
                isConnected = false,
                capabilities = listOf(
                    "Fetch active campaign metrics (CTR, CPC, Impressions, Reach)",
                    "Analyze ad set performance and automated budget tracking",
                    "Create and draft new conversion ad creatives",
                    "A/B testing anomaly detection and ROAS telemetry",
                    "Generate marketing performance summaries for team review"
                )
            ),
            ConnectorItem(
                id = "facebook",
                name = "Facebook Pages & Graph",
                provider = "Meta Platforms",
                description = "Access Graph API, manage Facebook business pages, comments, and publish updates.",
                icon = Icons.Outlined.Share,
                isConnected = false,
                capabilities = listOf(
                    "Read page posts, comments, and community engagement",
                    "Publish page updates, photos, and automated announcements",
                    "Moderate comments and draft contextual AI replies",
                    "Fetch page demographic insights and audience reach stats",
                    "Webhook notification integration for real-time events"
                )
            ),
            ConnectorItem(
                id = "sheets",
                name = "Google Sheets",
                provider = "Google Workspace",
                description = "Bi-directional spreadsheet manipulation, cell formula calculations, and tabular analytics.",
                icon = Icons.Outlined.TableChart,
                isConnected = true,
                capabilities = listOf(
                    "Read cell ranges, worksheets, and named tabular ranges",
                    "Append new rows, logs, and telemetry data automatically",
                    "Update formulas, format styles, and create pivot structures",
                    "Export sheets as CSV, XLSX, or formatted markdown tables",
                    "Validate and clean tabular records with schema consistency checks"
                )
            ),
            ConnectorItem(
                id = "drive",
                name = "Google Drive",
                provider = "Google Cloud",
                description = "Cloud storage file synchronization, folder hierarchy organization, and sharing permissions.",
                icon = Icons.Outlined.CloudQueue,
                isConnected = true,
                capabilities = listOf(
                    "Search and locate files across shared drives and folders",
                    "Upload local project artifacts, reports, and code backups",
                    "Download remote assets and synchronize project folders",
                    "Manage file sharing permissions and generate public/private links",
                    "Monitor storage quota and folder revision changes"
                )
            )
        )
    }

    var activeConnectorForDialog by remember { mutableStateOf<ConnectorItem?>(null) }
    var fbInfo by remember { mutableStateOf("") }

    // Fetch Facebook info when the Facebook connector is selected
    LaunchedEffect(activeConnectorForDialog) {
        if (activeConnectorForDialog?.id == "facebook") {
            try {
                val json = withContext(Dispatchers.IO) {
                    URL("https://graph.facebook.com/v21.0/me?fields=id,name&access_token=[redacted]").readText()
                }
                fbInfo = json
            } catch (e: Exception) {
                fbInfo = "Error: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Connectors & Integrations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("External tools and services hub", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    color = Color(0xFF0D1117),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF21262D), RoundedCornerShape(16.dp))
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFF00E5A3).copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Color(0xFF00E5A3))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Active Connectors Hub", style = MaterialTheme.typography.titleSmall, color = Color.White)
                            Text("Tap any connector to view and inspect its live agent capabilities.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8B949E))
                        }
                    }
                }
            }

            items(connectors, key = { it.id }) { connector ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            activeConnectorForDialog = connector
                        }
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(connector.icon, contentDescription = connector.name, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(connector.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(connector.provider, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AssistChip(
                                onClick = { activeConnectorForDialog = connector },
                                label = {
                                    Text(
                                        if (connector.isConnected) "Active" else "Ready",
                                        fontSize = 11.sp,
                                        color = if (connector.isConnected) Color(0xFF00E5A3) else MaterialTheme.colorScheme.primary
                                    )
                                },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                if (connector.isConnected) Color(0xFF00E5A3) else MaterialTheme.colorScheme.primary,
                                                CircleShape
                                            )
                                    )
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            connector.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${connector.capabilities.size} Capabilities available",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "View capabilities →",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal switch capabilities dialog - rendered within Composable context
    if (activeConnectorForDialog != null) {
        CapabilityDialog(
            show = true,
            title = "${activeConnectorForDialog!!.name} Capabilities",
            capabilities = activeConnectorForDialog!!.capabilities,
            onDismiss = { activeConnectorForDialog = null },
            onConfirm = { activeConnectorForDialog = null }
        )
        // If the Facebook connector is selected, also display fetched user info
        if (activeConnectorForDialog!!.id == "facebook" && fbInfo.isNotBlank()) {
            AlertDialog(
                onDismissRequest = { /* keep dialog open until OK */ },
                title = { Text(text = "Facebook User Info") },
                text = { Text(text = fbInfo) },
                confirmButton = {
                    TextButton(onClick = { /* dismiss by clearing dialog */ }) { Text("OK") }
                }
            )
        }
    }
}
