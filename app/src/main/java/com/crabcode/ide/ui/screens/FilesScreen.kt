package com.crabcode.ide.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crabcode.ide.R
import com.crabcode.ide.ui.CrabViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** File tree of the active project with language icons from the original assets. */
@Composable
fun FilesScreen(vm: CrabViewModel, expanded: Boolean, onOpenFile: () -> Unit) {
    val project by vm.selectedProject.collectAsState()
    val context = remember { if (vm.hasContext()) vm.context() else null }
    if (project == null || context == null) {
        EmptyHintBox("Выберите проект в разделе «Проекты»")
        return
    }
    val root = remember(project?.id) { context.space.project(project!!.id) }
    var refresh by remember { mutableStateOf(0) }
    val entries = remember(root, refresh) { listDir(root) }
    var openDirs by remember(project?.id) { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    var creating by remember { mutableStateOf<String?>(null) }   // "file" | "dir"
    var createName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(project!!.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { searching = !searching }) {
                Icon(painterResource(R.drawable.ic_action_search), "Поиск")
            }
            IconButton(onClick = { creating = "file" }) {
                Icon(painterResource(R.drawable.ic_action_new_file), "Новый файл")
            }
            IconButton(onClick = { creating = "dir" }) {
                Icon(painterResource(R.drawable.ic_action_new_folder), "Новый каталог")
            }
            IconButton(onClick = { refresh++ }) {
                Icon(painterResource(R.drawable.ic_action_refresh), "Обновить")
            }
        }

        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск по именам и содержимому…")
                }
            )
            val results = remember(query, refresh) {
                if (query.length < 2) emptyList() else searchFiles(root, query)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(results) { hit ->
                    Row(
                        Modifier.fillMaxWidth().padding(4.dp).clickable {
                            vm.openFile(hit.file)
                            onOpenFile()
                        }
                    ) {
                        LanguageIcon(hit.file)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(hit.file.relativeTo(root).path, style = MaterialTheme.typography.bodyMedium)
                            if (hit.line != null) {
                                Text(hit.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries) { entry ->
                    FileRow(
                        entry, root, openDirs,
                        onToggle = { path -> openDirs = if (path in openDirs) openDirs - path else openDirs + path },
                        onOpen = { file -> vm.openFile(file); onOpenFile() },
                        onRename = { renaming = it },
                        onDelete = { deleting = it }
                    )
                }
            }
        }
    }

    creating?.let { kind ->
        AlertDialog(
            onDismissRequest = { creating = null; createName = "" },
            title = { Text(if (kind == "file") "Новый файл" else "Новый каталог") },
            text = { OutlinedTextField(createName, { createName = it }, label = { Text("Имя") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (createName.isNotBlank()) {
                        val target = File(root, createName)
                        if (kind == "dir") target.mkdirs() else {
                            target.parentFile?.mkdirs(); target.writeText("")
                        }
                        refresh++
                    }
                    creating = null; createName = ""
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { creating = null; createName = "" }) { Text("Отмена") } }
        )
    }

    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Переименовать") },
            text = { OutlinedTextField(target.name, { createName = it }, label = { Text("Новое имя") }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            target.renameTo(File(target.parentFile, createName))
                        }
                    }
                    renaming = null; refresh++
                }) { Text("Переименовать") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } }
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить ${if (target.isDirectory) "каталог" else "файл"}?") },
            text = { Text(target.relativeTo(root).path) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { target.deleteRecursively() }
                    }
                    deleting = null; refresh++
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }
}

private data class TreeEntry(val file: File, val depth: Int)

private fun listDir(root: File): List<TreeEntry> {
    val out = mutableListOf<TreeEntry>()
    fun walk(dir: File, depth: Int, rel: String) {
        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
        for (f in files) {
            if (f.name == ".git" && depth == 0) continue
            val childRel = if (rel.isEmpty()) f.name else "$rel/${f.name}"
            out += TreeEntry(f, depth)
            if (f.isDirectory && depth < 3) walk(f, depth + 1, childRel)
        }
    }
    walk(root, 0, "")
    return out
}

private data class SearchHit(val file: File, val line: String?)

private fun searchFiles(root: File, query: String): List<SearchHit> {
    val q = query.lowercase()
    val out = mutableListOf<SearchHit>()
    root.walkTopDown().filter { it.isFile && it.length() < 500_000 }.forEach { f ->
        if (f.name.lowercase().contains(q)) {
            out += SearchHit(f, null)
            if (out.size > 200) return out.take(200)
        } else runCatching {
            f.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.lowercase().contains(q)) {
                        out += SearchHit(f, line.trim().take(120))
                        if (out.size > 200) return
                    }
                }
            }
        }
    }
    return out.take(200)
}

@Composable
private fun FileRow(
    entry: TreeEntry,
    root: File,
    openDirs: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (File) -> Unit,
    onRename: (File) -> Unit,
    onDelete: (File) -> Unit
) {
    val f = entry.file
    val rel = f.relativeTo(root).path
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = (entry.depth * 16).dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        if (f.isDirectory) {
            Icon(
                painterResource(
                    if (rel in openDirs) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
                ),
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LanguageIcon(f)
        Text(
            f.name,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .clickable { if (f.isDirectory) onToggle(rel) else onOpen(f) },
            style = MaterialTheme.typography.bodyMedium
        )
        IconButton(onClick = { onRename(f) }, modifier = Modifier.size(32.dp)) {
            Icon(painterResource(R.drawable.ic_action_rename), "Переименовать", modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = { onDelete(f) }, modifier = Modifier.size(32.dp)) {
            Icon(painterResource(R.drawable.ic_action_delete), "Удалить", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
    if (f.isDirectory && rel in openDirs) {
        // children are re-computed in parent's remember cycle; handled by refresh key
    }
}

/** Original CrabCode language SVG icons rendered through coil-svg. */
@Composable
fun LanguageIcon(file: File) {
    val path = if (file.isDirectory) {
        com.crabcode.ide.files.FileIcons.forFolder(file.name) ?: com.crabcode.ide.files.FileIcons.genericFolder()
    } else {
        com.crabcode.ide.files.FileIcons.forFile(file.name) ?: "img/text.svg"
    }
    AsyncImage(
        model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data("file:///android_asset/$path")
            .build(),
        contentDescription = null,
        modifier = Modifier.size(18.dp)
    )
}
