package com.crabcode.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.crabcode.ide.R
import com.crabcode.ide.ai.agent.AgentRuntime
import com.crabcode.ide.ai.agent.ApprovalBroker
import com.crabcode.ide.data.MessageEntity
import com.crabcode.ide.data.SessionEntity
import com.crabcode.ide.ui.CrabViewModel
import com.crabcode.ide.ui.theme.CodeStyle
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * AI chat: streaming answers, tool calls & results, plan, status, file
 * changes, approvals and errors — every element is a separate UI item.
 */
@Composable
fun ChatScreen(vm: CrabViewModel, expanded: Boolean, onRequestNotificationPermission: () -> Unit) {
    val project by vm.selectedProject.collectAsState()
    val context = remember { if (vm.hasContext()) vm.context() else null }

    if (project == null || context == null) {
        EmptyHint("Выберите проект в разделе «Проекты»")
        return
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sessions by context.store.db.sessionDao().observeForProject(project!!.id)
        .collectAsState(initial = emptyList())
    val session = remember(sessions) {
        sessions.firstOrNull { it.id == vm.selectedSession.value?.id } ?: sessions.firstOrNull()
    }
    val runtime = remember(session?.id) {
        session?.let { vm.agentRuntimeFor(it) }
    }
    val messagesFlow = remember(session?.id) {
        session?.let { s -> context.store.db.messageDao().observeForSession(s.id) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList<MessageEntity>())
    }
    val messages by messagesFlow.collectAsState(initial = emptyList())
    val status by runtime?.status?.collectAsState() ?: remember {
        androidx.compose.runtime.mutableStateOf(AgentRuntime.Status.IDLE)
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.updatedAt) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LaunchedEffect(status) {
        if (status == AgentRuntime.Status.RUNNING) {
            onRequestNotificationPermission()
            com.crabcode.ide.ai.AgentForegroundService.start(context.context)
        } else {
            com.crabcode.ide.ai.AgentForegroundService.stop(context.context)
        }
    }

    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        // header: session switcher + mode + new chat
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                project!!.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            ModeChip(vm, context, session)
            IconButton(onClick = {
                scope.launch {
                    val s = SessionEntity(
                        id = UUID.randomUUID().toString(),
                        projectId = project!!.id,
                        title = "Новая сессия",
                        mode = (session?.mode ?: "AGENT"),
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    context.store.db.sessionDao().upsert(s)
                    vm.selectSession(s)
                }
            }) {
                Icon(painterResource(R.drawable.ic_action_new_chat), "Новая сессия")
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyHint(
                    if (runtime == null) "Настройте модель в разделе «Модели»" else
                    "Опишите задачу — агент прочитает файлы, предложит изменения и команды."
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages, key = { it.id }) { m -> MessageItem(m) }
                }
            }
        }

        // status strip
        if (status == AgentRuntime.Status.RUNNING || status == AgentRuntime.Status.AWAITING_APPROVAL) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status == AgentRuntime.Status.RUNNING) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                    Text(
                        messages.lastOrNull { it.kind == MessageEntity.KIND_STATUS }?.content ?: "Выполняется…",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Icon(painterResource(R.drawable.ic_status_approval), null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Ожидание подтверждения",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { runtime?.stop() }) { Text("Остановить") }
            }
        }

        // input
        Row(
            modifier = Modifier.fillMaxWidth().imePadding().padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение агенту…") },
                maxLines = 5,
                enabled = status != AgentRuntime.Status.RUNNING
            )
            TextButton(
                onClick = {
                    if (input.isNotBlank()) {
                        runtime?.submitUserMessage(input.trim())
                        input = ""
                    }
                },
                enabled = status != AgentRuntime.Status.RUNNING && input.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Icon(painterResource(R.drawable.ic_action_send), null)
            }
        }
    }
}

@Composable
private fun ModeChip(
    vm: CrabViewModel,
    context: com.crabcode.ide.core.CrabContext,
    session: SessionEntity?
) {
    val modes = listOf("AGENT" to "Agent", "PLAN" to "Plan", "ASK" to "Ask")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        modes.forEach { (id, label) ->
            FilterChip(
                selected = session?.mode == id,
                onClick = {
                    session?.let { s ->
                        val updated = s.copy(mode = id)
                        context.scope.launch {
                            context.store.db.sessionDao().upsert(updated)
                        }
                        vm.selectSession(updated)
                    }
                },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp)
        )
    }
}

// ---------------------------------------------------------------- items

@Composable
fun MessageItem(m: MessageEntity) {
    when (m.kind) {
        MessageEntity.KIND_TEXT -> TextMessage(m)
        MessageEntity.KIND_TOOL_CALL -> ToolCallItem(m)
        MessageEntity.KIND_TOOL_RESULT -> ToolResultItem(m)
        MessageEntity.KIND_PLAN -> PlanItem(m)
        MessageEntity.KIND_STATUS -> Unit
        MessageEntity.KIND_ERROR -> ErrorItem(m)
        MessageEntity.KIND_FILE_CHANGE -> FileChangeItem(m)
        MessageEntity.KIND_APPROVAL_REQUEST -> ApprovalItem(m)
        MessageEntity.KIND_APPROVAL_RESPONSE -> ApprovalResponseItem(m)
    }
}

@Composable
private fun TextMessage(m: MessageEntity) {
    if (m.content.startsWith(AgentRuntime.REASONING_TAG)) {
        ReasoningItem(m.content.removePrefix(AgentRuntime.REASONING_TAG))
        return
    }
    if (m.role == "user") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(
                    m.content,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    } else {
        MarkdownText(m.content)
    }
}

@Composable
private fun ReasoningItem(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "Размышления модели",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ToolCallItem(m: MessageEntity) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (m.toolStatus == "error") MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_tool),
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(18.dp)
            )
            Text(
                "Инструменты: ${m.toolName ?: "?"}",
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.weight(1f))
            if (m.toolStatus == "running") {
                CircularProgressIndicator(modifier = Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ToolResultItem(m: MessageEntity) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "Результат: ${m.toolName ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = if (m.toolStatus == "error") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            CodeBlock(m.content)
        }
    }
}

@Composable
private fun PlanItem(m: MessageEntity) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("План выполнения", style = MaterialTheme.typography.labelMedium)
            PlanLines(m.content)
        }
    }
}

@Composable
private fun PlanLines(json: String) {
    val steps = remember(json) {
        runCatching {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(json)
                .let { (it as? kotlinx.serialization.json.JsonObject)?.get("steps") }
            (arr as? kotlinx.serialization.json.JsonArray)?.map { el ->
                val o = el as kotlinx.serialization.json.JsonObject
                Triple(
                    o["title"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: "",
                    o["status"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: "pending"
                )
            }
        }.getOrNull() ?: emptyList()
    }
    steps.forEach { (title, status) ->
        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val mark = when (status) {
                "done" -> "●"
                "in_progress" -> "◐"
                else -> "○"
            }
            Text(mark, color = MaterialTheme.colorScheme.primary)
            Text(
                title,
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (status == "done") MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ErrorItem(m: MessageEntity) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(10.dp)) {
            Text(
                if (m.toolStatus == "quota") "Квота модели исчерпана" else "Ошибка",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(m.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun FileChangeItem(m: MessageEntity) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_file_change), null, tint = MaterialTheme.colorScheme.primary)
        Text(
            when (m.toolName) {
                "CREATED" -> "Создан: "; "DELETED" -> "Удалён: "; "MOVED" -> "Перемещён: "; else -> "Изменён: "
            } + m.content,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ApprovalItem(m: MessageEntity) {
    val args = remember(m.id) {
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(m.toolArgsJson ?: "{}")
                as kotlinx.serialization.json.JsonObject
        }.getOrNull()
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Требуется подтверждение", style = MaterialTheme.typography.labelMedium)
            Text(m.content, style = MaterialTheme.typography.titleMedium)
            args?.get("detail")?.let {
                Text(
                    (it as kotlinx.serialization.json.JsonPrimitive).content,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            args?.get("command")?.let {
                val cmd = (it as kotlinx.serialization.json.JsonPrimitive).content
                if (cmd.isNotBlank()) CodeBlock(cmd)
            }
            Row {
                TextButton(onClick = { ApprovalBroker.respond(m.id, true) }) { Text("Разрешить") }
                TextButton(onClick = { ApprovalBroker.respond(m.id, false) }) { Text("Отклонить") }
            }
        }
    }
}

@Composable
private fun ApprovalResponseItem(m: MessageEntity) {
    Row {
        Icon(
            painterResource(R.drawable.ic_status_approval), null,
            tint = if (m.content.startsWith("Одобрено")) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(m.content, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------- markdown

@Composable
fun MarkdownText(text: String) {
    val blocks = remember(text) { splitMarkdown(text) }
    Column(Modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block.first) {
                "code" -> CodeBlock(block.second)
                "h" -> Text(
                    block.second.trimStart('#', ' '),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                else -> InlineMarkdown(block.second)
            }
        }
    }
}

private fun splitMarkdown(text: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val lines = text.lines()
    val sb = StringBuilder()
    var inCode = false
    val code = StringBuilder()
    for (line in lines) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out += "code" to code.toString().trimEnd()
                code.clear()
            } else {
                if (sb.isNotBlank()) out += "text" to sb.toString().trimEnd(); sb.clear()
            }
            inCode = !inCode
            continue
        }
        if (inCode) code.appendLine(line)
        else sb.appendLine(line)
    }
    if (sb.isNotBlank()) out += "text" to sb.toString().trimEnd()
    if (code.isNotBlank()) out += "code" to code.toString()
    return out
}

@Composable
private fun InlineMarkdown(text: String) {
    val annotated = remember(text) {
        androidx.compose.ui.text.AnnotatedString.Builder().apply {
            var i = 0
            val pattern = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*")
            for (m in pattern.findAll(text)) {
                append(text.substring(i, m.range.first))
                when {
                    m.groups[1] != null -> {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = FontFamily.Monospace))
                        append(m.groupValues[1]); pop()
                    }
                    else -> {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                        append(m.groupValues[2]); pop()
                    }
                }
                i = m.range.last + 1
            }
            append(text.substring(i.coerceAtMost(text.length)))
        }.toAnnotatedString()
    }
    Text(annotated, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun CodeBlock(code: String) {
    Surface(
        color = Color(0x11FFFFFF),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Text(
            code,
            style = CodeStyle,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        )
    }
}
