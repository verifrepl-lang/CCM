package com.crabcode.ide.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crabcode.ide.ui.CrabViewModel
import java.text.DateFormat
import java.util.Date

/** Session history: previous chats across projects, offline-readable. */
@Composable
fun SessionListScreen(vm: CrabViewModel, onOpenSession: () -> Unit) {
    val context = remember { if (vm.hasContext()) vm.context() else null }
    if (context == null) { EmptyHintBox("Требуется вход"); return }

    val sessions by context.store.db.sessionDao().observeAll()
        .collectAsState(initial = emptyList())
    val projects by context.store.db.projectDao().observeAll()
        .collectAsState(initial = emptyList())
    val projectName = remember(projects) { projects.associate { it.id to it.name } }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("История сессий", style = MaterialTheme.typography.titleLarge)
        if (sessions.isEmpty()) {
            EmptyHintBox("Сессий пока нет")
        } else {
            LazyColumn(Modifier.padding(top = 8.dp)) {
                items(sessions, key = { it.id }) { s ->
                    Card(
                        onClick = {
                            projects.firstOrNull { it.id == s.projectId }?.let { vm.selectProject(it) }
                            vm.selectSession(s)
                            onOpenSession()
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                s.title,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "${projectName[s.projectId] ?: "проект"} · режим ${s.mode} · ${DateFormat.getDateTimeInstance().format(Date(s.updatedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
