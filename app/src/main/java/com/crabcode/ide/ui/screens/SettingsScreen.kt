package com.crabcode.ide.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.crabcode.ide.BuildConfig
import com.crabcode.ide.ui.CrabViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings: theme, text scale, sounds/notifications, instructions files, account. */
@Composable
fun SettingsScreen(vm: CrabViewModel, onUpdate: () -> Unit) {
    val context = remember { if (vm.hasContext()) vm.context() else null }
    if (context == null) { EmptyHintBox("Требуется вход"); return }

    val account by vm.account.collectAsState()
    val theme by vm.theme.collectAsState()
    val fontScale by vm.settings.fontScale.collectAsState(initial = 1f)
    val soundDone by vm.settings.soundAgentDone.collectAsState(initial = true)
    val soundApproval by vm.settings.soundAgentApproval.collectAsState(initial = true)
    val soundError by vm.settings.soundError.collectAsState(initial = true)
    val notifyDone by vm.settings.notifyAgentDone.collectAsState(initial = true)
    val notifyApproval by vm.settings.notifyApproval.collectAsState(initial = true)
    val notifyError by vm.settings.notifyError.collectAsState(initial = true)
    val autoCheck by vm.settings.otaAutoCheck.collectAsState(initial = true)

    val scope = rememberCoroutineScope()
    val project by vm.selectedProject.collectAsState()

    SectionCard("Внешний вид") {
        Row {
            listOf("crab-dark" to "Тёмная", "crab-light" to "Светлая", "system" to "Системная").forEach { (id, label) ->
                TextButton(onClick = { scope.launch { context.settings.setTheme(id) } }) {
                    Text(if (theme == id) "● $label" else label)
                }
            }
        }
        Text("Масштаб текста: ${"%.0f".format(fontScale * 100)}%", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = fontScale,
            onValueChange = { scope.launch { context.settings.setFontScale(it) } },
            valueRange = 0.8f..1.6f
        )
    }

    SectionCard("Уведомления и звуки") {
        ToggleRow("Звук: задача выполнена", soundDone) { scope.launch { context.settings.setSoundAgentDone(it) } }
        ToggleRow("Звук: ожидание подтверждения", soundApproval) { scope.launch { context.settings.setSoundAgentApproval(it) } }
        ToggleRow("Звук: ошибка", soundError) { scope.launch { context.settings.setSoundError(it) } }
        ToggleRow("Уведомление: задача выполнена", notifyDone) { scope.launch { context.settings.setNotifyAgentDone(it) } }
        ToggleRow("Уведомление: подтверждение", notifyApproval) { scope.launch { context.settings.setNotifyApproval(it) } }
        ToggleRow("Уведомление: ошибки", notifyError) { scope.launch { context.settings.setNotifyError(it) } }
    }

    SectionCard("Файлы инструкций для ИИ") {
        Text(
            "Формат оригинала: .crab/CRAB.md и .crab/steering/*.md. Подключите " +
                "README.md, AGENTS.md или другой файл — он будет добавлен в .crab/steering/ " +
                "и использоваться агентом как инструкция.",
            style = MaterialTheme.typography.bodySmall
        )
        if (project == null) {
            Text("Выберите проект, чтобы управлять его файлами инструкций.", style = MaterialTheme.typography.bodySmall)
        } else {
            val root = remember(project?.id) { context.space.project(project!!.id) }
            val steering = remember(project?.id) {
                File(root, ".crab/steering").listFiles()?.map { it.name } ?: emptyList()
            }
            val crabMd = remember(project?.id) { File(root, ".crab/CRAB.md").exists() }

            if (crabMd) Text("● .crab/CRAB.md подключён", style = MaterialTheme.typography.bodySmall)
            steering.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("● .crab/steering/$name", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        scope.launch { withContext(Dispatchers.IO) { File(File(root, ".crab/steering"), name).delete() } }
                    }) { Text("Отключить") }
                }
            }

            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val name = runCatching {
                                context.context.contentResolver.openInputStream(uri)?.use { }
                                queryDisplayName(vm, it) ?: "instructions.md"
                            }.getOrDefault("instructions.md")
                            val dir = File(root, ".crab/steering")
                            dir.mkdirs()
                            val dest = File(dir, name)
                            context.context.contentResolver.openInputStream(uri)?.use { input ->
                                dest.outputStream().use { input.copyTo(it) }
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) {
                Text("Добавить файл инструкций")
            }
        }
    }

    SectionCard("Обновления") {
        ToggleRow("Проверять обновления автоматически", autoCheck) {
            scope.launch { context.settings.setOtaAutoCheck(it) }
        }
        TextButton(onClick = onUpdate) { Text("Проверить сейчас · текущая версия ${BuildConfig.VERSION_NAME}") }
    }

    SectionCard("Аккаунт") {
        Text("${account?.name ?: ""} (${account?.email ?: ""})", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Данные изолированы по аккаунту: проекты, чаты, терминал, ключи и MCP-конфигурации разделены.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { vm.signOut() }) { Text("Выйти из аккаунта") }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.padding(4.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}
