package com.androidharness.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AMOLED Floating Dock Bar.
 *
 * Sits at the bottom of the screen in pure AMOLED black with cyan and emerald accents,
 * offering quick one-tap access to Chat, Connectors, Files, Terminal, and Settings.
 */
@Composable
fun AmoledDockBar(
    currentRoute: String?,
    onNavigateChat: () -> Unit,
    onNavigateConnectors: () -> Unit,
    onNavigateFiles: () -> Unit,
    onNavigateTerminal: () -> Unit,
    onNavigateSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xFF000000), // Pure AMOLED black
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 8.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .border(1.dp, Color(0xFF21262D), RoundedCornerShape(28.dp))
                .height(60.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockItem(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    label = "Chat",
                    selected = currentRoute?.startsWith("chat") == true,
                    onClick = onNavigateChat
                )
                DockItem(
                    icon = Icons.Outlined.Extension,
                    label = "Connectors",
                    selected = currentRoute == "connectors",
                    badgeCount = 6,
                    onClick = onNavigateConnectors
                )
                DockItem(
                    icon = Icons.Outlined.Folder,
                    label = "Files",
                    selected = currentRoute == "files",
                    onClick = onNavigateFiles
                )
                DockItem(
                    icon = Icons.Outlined.Terminal,
                    label = "Terminal",
                    selected = currentRoute == "terminal",
                    onClick = onNavigateTerminal
                )
                DockItem(
                    icon = Icons.Outlined.Settings,
                    label = "Settings",
                    selected = currentRoute == "settings",
                    onClick = onNavigateSettings
                )
            }
        }
    }
}

@Composable
private fun DockItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int? = null
) {
    val activeColor = Color(0xFF00E5A3) // Emerald accent
    val inactiveColor = Color(0xFF8B949E) // Muted silver

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) activeColor else inactiveColor,
                    modifier = Modifier.size(22.dp)
                )
                if (badgeCount != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-4).dp)
                            .background(Color(0xFF38BDF8), CircleShape)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = badgeCount.toString(),
                            color = Color.Black,
                            fontSize = 9.sp,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = if (selected) activeColor else inactiveColor,
                fontSize = 10.sp,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
