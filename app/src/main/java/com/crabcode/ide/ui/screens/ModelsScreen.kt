package com.crabcode.ide.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crabcode.ide.ai.providers.ProviderCatalog
import com.crabcode.ide.ai.providers.ProviderConfig
import com.crabcode.ide.ui.CrabViewModel
import kotlinx.coroutines.launch

/** Model providers: catalog of the original IDE, API keys per user, active model. */
@Composable
fun ModelsScreen(vm: CrabViewModel) {
    val context = remember { if (vm.hasContext()) vm.context() else null }
    if (context == null) { EmptyHintBox("Требуется вход"); return }

    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf(context.providers.load()) }
    val active = remember(providers) { context.providers.active() }

    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var keyValue by remember { mutableStateOf("") }
    var showAddCustom by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Модели и провайдеры", style = MaterialTheme.typography.titleLarge)
        Text(
            "Ключи хранятся только на устройстве в зашифрованном хранилище вашего аккаунта.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { showAddCustom = true }) { Text("+ Свой OpenAI-совместимый провайдер") }

        LazyColumn {
            items(providers, key = { it.id }) { p ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (active.providerId == p.id) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(
                                p.api, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (active.providerId == p.id) {
                            Text(
                                "Активный провайдер",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(Modifier.padding(top = 8.dp)) {
                            p.models.take(4).forEach { m ->
                                FilterChip(
                                    selected = active.providerId == p.id && active.modelId == m.id,
                                    onClick = {
                                        context.providers.setActive(p.id, m.id)
                                        providers = context.providers.load()
                                    },
                                    label = { Text(m.id, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                        }
                        Row {
                            TextButton(onClick = { editing = p; keyValue = context.providers.apiKey(p.id) ?: "" }) {
                                Text(if (context.providers.apiKey(p.id).isNullOrEmpty()) "Добавить ключ" else "Изменить ключ")
                            }
                            TextButton(onClick = {
                                context.providers.setApiKey(p.id, null)
                                providers = context.providers.load()
                            }) { Text("Удалить ключ") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { p ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("API-ключ: ${p.name}") },
            text = {
                Column {
                    OutlinedTextField(keyValue, { keyValue = it }, label = { Text("sk-… / ключ провайдера") })
                    Text(
                        "Провайдер: ${p.baseUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.providers.setApiKey(p.id, keyValue.ifBlank { null })
                    providers = context.providers.load()
                    editing = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } }
        )
    }

    if (showAddCustom) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var model by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddCustom = false },
            title = { Text("Свой провайдер (OpenAI-совместимый)") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Название") })
                    OutlinedTextField(url, { url = it }, label = { Text("Base URL (https://…/v1)") })
                    OutlinedTextField(model, { model = it }, label = { Text("ID модели") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (url.isNotBlank() && model.isNotBlank()) {
                        val id = "custom_${System.currentTimeMillis()}"
                        providers = providers + ProviderConfig(
                            id = id, catalogId = ProviderCatalog.CUSTOM_TEMPLATE,
                            name = name.ifBlank { "Свой провайдер" }, api = "openai",
                            baseUrl = url.trimEnd('/'),
                            models = listOf(com.crabcode.ide.ai.providers.StoredModel(model))
                        )
                        context.providers.save(providers)
                    }
                    showAddCustom = false
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showAddCustom = false }) { Text("Отмена") } }
        )
    }
}
