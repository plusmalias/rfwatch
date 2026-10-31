package com.rfwatch.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rfwatch.app.ui.theme.OkGreen

@Composable
fun MoreScreen(
    modifier: Modifier = Modifier,
    onCell: () -> Unit,
    onRoom: () -> Unit,
    onLog: () -> Unit,
    onSettings: () -> Unit,
    /** Extra protective tools: (title, description, open). */
    tools: List<Triple<String, String, () -> Unit>> = emptyList(),
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("TOOLS", style = MaterialTheme.typography.labelSmall, color = OkGreen)
        Text("Field utilities", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Cellular checks, room sweep, history and settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        tools.forEach { (title, description, open) -> MoreAction(title, description, Icons.Filled.Search, open) }
        MoreAction("Cellular checks", "Review cell changes and heuristic alerts.", Icons.Filled.CellTower, onCell)
        MoreAction("Room sweep", "Review nearby Wi-Fi devices and findings.", Icons.Filled.Search, onRoom)
        MoreAction("Event log", "Browse saved observations and alerts.", Icons.Filled.List, onLog)
        MoreAction("Settings", "Retention, app lock and local data controls.", Icons.Filled.Settings, onSettings)
    }
}

@Composable
private fun MoreAction(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
