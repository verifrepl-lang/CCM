package com.crabcode.ide.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crabcode.ide.BuildConfig
import com.crabcode.ide.ui.CrabViewModel

/** OTA: check version against GitHub Releases, show changelog, download APK. */
@Composable
fun UpdateScreen(vm: CrabViewModel) {
    val info by vm.updateInfo.collectAsState()
    val busy by vm.otaBusy.collectAsState()
    var progress by remember { mutableStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Обновление приложения", style = MaterialTheme.typography.titleLarge)
        Text(
            "Источник: GitHub Releases вашего репозитория.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Текущая версия: ${BuildConfig.VERSION_NAME}")
                info?.let { u ->
                    Text("Доступна: ${u.version}", color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Размер APK: ${u.apkSize / (1024 * 1024)} МБ · SHA-256: ${u.sha256.take(16)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Список изменений",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(u.notes.take(2000), style = MaterialTheme.typography.bodySmall)
                    if (progress >= 0) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        )
                        Text("Загрузка: $progress%", style = MaterialTheme.typography.bodySmall)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = { vm.downloadAndInstallApk({ progress = it }, { error = it }) },
                            enabled = !busy && progress < 0
                        ) { Text("Скачать и установить") }
                        Spacer(Modifier.padding(4.dp))
                        OutlinedButton(onClick = { vm.skipUpdate(u.version) }) { Text("Пропустить") }
                    }
                } ?: run {
                    if (busy) Text("Проверка…") else Text("Обновлений нет — установлена последняя версия.")
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Linux-окружение обновляется отдельно", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Раздел «Окружение» → «Переустановить». Пользовательские проекты и " +
                        "конфигурации при обновлении окружения не затрагиваются.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        TextButton(onClick = { vm.checkForUpdates(silent = false) }) { Text("Проверить снова") }
    }
}
