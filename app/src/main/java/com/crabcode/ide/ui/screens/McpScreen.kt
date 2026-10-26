package com.crabcode.ide.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crabcode.ide.ai.mcp.McpServerConfig
import com.crabcode.ide.ai.mcp.McpStatus
import com.crabcode.ide.ui.CrabViewModel
import kotlinx.coroutines.launch

/** MCP servers: add/remove, transport, tool list, status, reconnect. */
@Composable
fun McpScreen(vm: CrabViewModel) {
    val context = remember { if (vm.hasContext()) vm.context() else null }
    if (context == null) { EmptyHintBox("Требуется вход"); return }

    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(context.mcp.loadConfig()) }
    val states by context.mcp.states.collectAsState()
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { context.mcp.connectAll() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MCP-серверы", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { adding = true }) { Text("+ Добавить") }
        }
        Text(
            "stdio-серверы запускаются в Linux-окружении (PRoot); HTTP — напрямую.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(Modifier.padding(top = 8.dp)) {
            items(config.servers, key = { it.name }) { server ->
                val state = states[server.name]
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (state?.status) {
                            McpStatus.CONNECTED -> MaterialTheme.colorScheme.surfaceVariant
                            McpStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(server.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(
                                when (state?.status) {
                                    McpStatus.CONNECTED -> "подключён"
                                    McpStatus.CONNECTING -> "подключение…"
                                    McpStatus.ERROR -> "ошибка"
                                    else -> "отключён"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state?.status == McpStatus.ERROR) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            server.transport + " · " + (server.command ?: server.url ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        state?.lastError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!state?.tools.isNullOrEmpty()) {
                            Text(
                                "Инструменты: ${state!!.tools.joinToString(", ") { it.name }}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row {
                            TextButton(onClick = { scope.launch { context.mcp.connect(server) } }) { Text("Подключить") }
                            TextButton(onClick = {
                                scope.launch {
                                    context.mcp.disconnect(server.name)
                                    config = config.copy(servers = config.servers - server)
                                    context.mcp.saveConfig(config)
                                }
                            }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var transport by remember { mutableStateOf("stdio") }
        var command by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Новый MCP-сервер") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Имя") })
                    Row {
                        listOf("stdio", "http").forEach { t ->
                            androidx.compose.material3.FilterChip(
                                selected = transport == t,
                                onClick = { transport = t },
                                label = { Text(t) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    if (transport == "stdio") {
                        OutlinedTextField(command, { command = it }, label = { Text("Команда в Linux-окружении (например: npx -y @modelcontextprotocol/server-filesystem /workspace)") })
                    } else {
                        OutlinedTextField(url, { url = it }, label = { Text("https://… (Streamable HTTP)") })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        val server = McpServerConfig(
                            name = name.trim(), transport = transport,
                            command = command.ifBlank { null }, url = url.ifBlank { null }
                        )
                        config = config.copy(servers = config.servers + server)
                        context.mcp.saveConfig(config)
                        scope.launch { context.mcp.connect(server) }
                    }
                    adding = false
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Отмена") } }
        )
    }
}
