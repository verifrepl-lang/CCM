package com.crabcode.ide.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.crabcode.ide.R
import com.crabcode.ide.ui.CrabViewModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Projects: create / import (SAF) / open / rename / delete-with-confirmation. */
@Composable
fun ProjectsScreen(vm: CrabViewModel, onOpenProject: () -> Unit) {
    val context = vm.context()
    val scope = rememberCoroutineScope()
    val projects by context.store.db.projectDao().observeAll()
        .collectAsState(initial = emptyList())
    val selected by vm.selectedProject.collectAsState()

    var dialogOpen by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<com.crabcode.ide.data.ProjectEntity?>(null) }
    var deleting by remember { mutableStateOf<com.crabcode.ide.data.ProjectEntity?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }

    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                val name = queryDisplayName(vm, it) ?: "import"
                vm.context().let { c ->
                    val repo = com.crabcode.ide.projects.ProjectRepository(
                        vm.getApplication(), c.space, c.store.db.projectDao()
                    )
                    repo.importFromUri(name, it)
                        .onSuccess { importResult = null; vm.selectProject(it); onOpenProject() }
                        .onFailure { e -> importResult = e.message }
                }
            }
        }
    }
    val importTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            scope.launch {
                val name = queryDisplayName(vm, it) ?: "project"
                vm.context().let { c ->
                    val repo = com.crabcode.ide.projects.ProjectRepository(
                        vm.getApplication(), c.space, c.store.db.projectDao()
                    )
                    repo.importFromTree(name, it)
                        .onSuccess { vm.selectProject(it); onOpenProject() }
                        .onFailure { e -> importResult = e.message }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Проекты", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { dialogOpen = true }) { Text("+ Новый") }
            IconButton(onClick = { importZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                Icon(painterResource(R.drawable.ic_action_import), "Импорт ZIP")
            }
            IconButton(onClick = { importTree.launch(null) }) {
                Icon(painterResource(R.drawable.ic_action_folder), "Импорт каталога")
            }
        }

        importResult?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Нет проектов.\nСоздайте новый или импортируйте ZIP / каталог.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.id }) { p ->
                    Card(
                        onClick = { vm.selectProject(p); onOpenProject() },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected?.id == p.id) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_nav_projects), null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Изменён: ${DateFormat.getDateInstance().format(Date(p.updatedAt))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { renaming = p; newName = p.name }) {
                                Icon(painterResource(R.drawable.ic_action_rename), "Переименовать")
                            }
                            IconButton(onClick = { deleting = p }) {
                                Icon(painterResource(R.drawable.ic_action_delete), "Удалить", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Новый проект") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Название") })
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (newName.isNotBlank()) {
                            val created = com.crabcode.ide.projects.ProjectRepository(
                                vm.getApplication(), context.space, context.store.db.projectDao()
                            ).create(newName)
                            vm.selectProject(created)
                            onOpenProject()
                        }
                    }
                    dialogOpen = false; newName = ""
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { dialogOpen = false; newName = "" }) { Text("Отмена") } }
        )
    }

    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null; newName = "" },
            title = { Text("Переименование проекта") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Название") })
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        com.crabcode.ide.projects.ProjectRepository(
                            vm.getApplication(), context.space, context.store.db.projectDao()
                        ).rename(target, newName)
                    }
                    renaming = null; newName = ""
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renaming = null; newName = "" }) { Text("Отмена") } }
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить проект?") },
            text = { Text("«${target.name}» и все его файлы будут удалены безвозвратно. Чаты останутся в истории.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        com.crabcode.ide.projects.ProjectRepository(
                            vm.getApplication(), context.space, context.store.db.projectDao()
                        ).delete(target, confirmed = true)
                        if (vm.selectedProject.value?.id == target.id) vm.selectProject(null)
                    }
                    deleting = null
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }
}

/** SAF display-name query helper. */
fun queryDisplayName(vm: CrabViewModel, uri: android.net.Uri): String? = runCatching {
    val app = vm.getApplication<com.crabcode.ide.CrabApp>()
    app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
}.getOrNull()
