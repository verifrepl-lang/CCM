package com.crabcode.ide.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crabcode.ide.core.update.ResumableDownloader
import com.crabcode.ide.linux.EnvironmentManager
import com.crabcode.ide.linux.EnvironmentService
import com.crabcode.ide.ui.CrabViewModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Linux environment manager: install, status, disk, logs, repair. */
@Composable
fun EnvScreen(vm: CrabViewModel) {
    val context = remember { if (vm.hasContext()) vm.context() else null }
    if (context == null) { EmptyHintBox("Требуется вход"); return }

    val state by context.environment.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { context.environment.installIfNeeded() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Linux-окружение", style = MaterialTheme.typography.titleLarge)
        Text(
            "Debian 12 (bookworm) + PRoot. Это НЕ root-доступ: PRoot эмулирует " +
                "административные привилегии в пользовательском пространстве.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    when (val s = state) {
                        is EnvironmentManager.State.Ready -> "Готово · rootfs ${s.rootfsVersion}"
                        is EnvironmentManager.State.NotInstalled -> "Не установлено"
                        is EnvironmentManager.State.Downloading -> "Загрузка…"
                        is EnvironmentManager.State.Verifying -> "Проверка SHA-256…"
                        is EnvironmentManager.State.Extracting -> "Распаковка… (${s.entries} файлов)"
                        is EnvironmentManager.State.Configuring -> "Настройка: ${s.step}"
                        is EnvironmentManager.State.Failed -> "Ошибка"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state is EnvironmentManager.State.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                when (val s = state) {
                    is EnvironmentManager.State.Downloading -> LinearProgressIndicator(
                        progress = { if (s.total > 0) s.downloaded.toFloat() / s.total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                    is EnvironmentManager.State.Failed -> {
                        Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Unit
                }
                Text(
                    "Свободно: ${ResumableDownloader.freeInternalBytes(context.context) / (1024 * 1024)} МБ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(Modifier.padding(vertical = 4.dp)) {
            Button(onClick = {
                EnvironmentService.startInstall(context.context, force = false)
            }) { Text("Установить / проверить") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = {
                EnvironmentService.startInstall(context.context, force = true)
            }) { Text("Переустановить") }
        }
        TextButton(onClick = {
            // repair keeps projects untouched; only the rootfs is re-fetched
            scope.launch { context.environment.installIfNeeded(forceReinstall = true) }
        }) { Text("Восстановить окружение (проекты не затрагиваются)") }

        Card(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Журнал установки", style = MaterialTheme.typography.labelMedium)
                val log = remember(state) {
                    runCatching {
                        context.environment.installLog().readText().lines().takeLast(40)
                    }.getOrDefault(emptyList())
                }
                Text(
                    log.joinToString("\n").ifEmpty { "пусто" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    "Последнее изменение: ${DateFormat.getDateTimeInstance().format(Date(System.currentTimeMillis()))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Доступные инструменты окружения", style = MaterialTheme.typography.labelMedium)
                Text(
                    "python3 · pip · node · npm · git · bash · apt (Debian 12).\n" +
                        "Установка пакетов: apt update && apt install <пакет> — внутри терминала.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
