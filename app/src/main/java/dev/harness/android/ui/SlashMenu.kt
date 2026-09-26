package dev.harness.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.harness.core.SlashCommand

@Composable
fun SlashMenu(commands: List<SlashCommand>, query: String, loading: Boolean, error: String?, retry: () -> Unit, select: (SlashCommand) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        Text("命令", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) { Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error); TextButton(onClick = retry) { Text("重新加载命令") } }
        val matches = commands.filter { it.name.contains(query, true) || it.description.contains(query, true) }
        matches.forEach { command ->
            Row(Modifier.fillMaxWidth().clickable { select(command) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Terminal, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("/${command.name}", style = MaterialTheme.typography.bodyMedium)
                    Text(command.description.ifBlank { command.hint }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (command.hint.isNotBlank()) Text(command.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (matches.isEmpty() && !loading) Text("没有匹配的命令", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
    }
}
