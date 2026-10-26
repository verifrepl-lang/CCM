package com.crabcode.ide.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crabcode.ide.R
import com.crabcode.ide.core.settings.AppSettings
import com.crabcode.ide.terminal.AnsiTerminal
import com.crabcode.ide.terminal.TerminalSession
import com.crabcode.ide.ui.CrabViewModel
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * Terminal: live rendering of [AnsiTerminal] (ANSI colors included), interactive
 * input, multiple sessions and the separate agent-output console.
 */
@Composable
fun TerminalScreen(vm: CrabViewModel, expanded: Boolean) {
    val context = remember { if (vm.hasContext()) vm.context() else null }
    if (context == null) { EmptyHintBox("Требуется вход"); return }

    val project by vm.selectedProject.collectAsState()
    val sessions by context.terminal.sessions.collectAsState()
    val activeId by context.terminal.activeId.collectAsState()
    val app = vm.getApplication<com.crabcode.ide.CrabApp>()
    val settings = remember { AppSettings(app) }
    val fontSp by settings.terminalFontSp.collectAsState(initial = 12f)
    val scope = rememberCoroutineScope()

    var showAgentConsole by remember { mutableStateOf(false) }

    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()

    LaunchedEffect(sessions.size) {
        if (sessions.isEmpty() && context.environment.isReady()) {
            val dir = project?.let { context.space.project(it.id) } ?: context.space.home
            val s = context.terminal.createSession(dir, "bash")
            context.terminal.start(s)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sessions, key = { it.id }) { s ->
                    FilterChip(
                        selected = !showAgentConsole && s.id == active?.id,
                        onClick = { showAgentConsole = false; context.terminal.switch(s.id) },
                        label = { Text(s.title, style = MaterialTheme.typography.labelSmall) }
                    )
                }
                item {
                    FilterChip(
                        selected = showAgentConsole,
                        onClick = { showAgentConsole = true },
                        label = { Text("Вывод агента", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            IconButton(onClick = {
                val dir = project?.let { context.space.project(it.id) } ?: context.space.home
                val s = context.terminal.createSession(dir, "bash ${sessions.size + 1}")
                context.terminal.start(s)
            }) { Icon(painterResource(R.drawable.ic_action_new_terminal), "Новый терминал") }
            IconButton(onClick = { active?.let { context.terminal.close(it.id) } }) {
                Icon(painterResource(R.drawable.ic_action_delete), "Закрыть сессию")
            }
            IconButton(onClick = { scope.launch { settings.setTerminalFontSp(fontSp + 1f) } }) {
                Icon(painterResource(R.drawable.ic_action_font_up), "Крупнее")
            }
            IconButton(onClick = { scope.launch { settings.setTerminalFontSp(fontSp - 1f) } }) {
                Icon(painterResource(R.drawable.ic_action_font_down), "Мельче")
            }
        }

        if (showAgentConsole) {
            AgentConsoleView(context.terminal, fontSp)
        } else if (active == null) {
            EmptyHintBox(
                if (context.environment.isReady()) "Создайте терминал кнопкой +"
                else "Linux-окружение не установлено — раздел «Окружение»"
            )
        } else {
            TerminalView(active, fontSp)
            TerminalInput(active)
            TerminalControlRow(active)
        }
    }
}

@Composable
private fun TerminalView(session: TerminalSession, fontSp: Float) {
    val density = LocalDensity.current
    val fontSize = fontSp.sp
    val charWidthPx = with(density) { fontSize.toPx() * 0.6f }
    val lineHeight = fontSize.value * 1.3f

    var cols by remember(session.id) { mutableStateOf(80) }
    var rows by remember(session.id) { mutableStateOf(24) }
    var frames by remember(session.id) { mutableStateOf(0L) }

    // poll terminal revision (also drives streaming updates)
    LaunchedEffect(session.id) {
        while (true) {
            kotlinx.coroutines.delay(60)
            val rev = synchronized(session.terminal) { session.terminal.revision }
            if (rev != frames) frames = rev
        }
    }

    val fg = MaterialTheme.colorScheme.onSurface
    val vScroll = rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(frames) {
        // auto-stick to the bottom while output is streaming
        vScroll.scrollTo(vScroll.maxValue)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .onSizeChanged { size ->
                val c = floor(size.width / charWidthPx).toInt().coerceAtLeast(10)
                val r = floor(size.height / with(density) { lineHeight.dp.toPx() }).toInt().coerceAtLeast(4)
                if (c != cols || r != rows) {
                    cols = c; rows = r
                    session.resize(cols, rows)
                }
            }
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(vScroll)
                .horizontalScroll(rememberScrollState())
        ) {
            val term = session.terminal
            // Full history rendering (scrollback + screen); the Compose scroll
            // state handles view scrolling, auto-stick to bottom when fresh.
            val rendered = synchronized(term) {
                (0 until term.totalLines).map { idx -> term.line(idx, 0) }
            }
            rendered.forEach { row ->
                Text(
                    text = lineAnnotated(row, fg),
                    softWrap = false,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = fontSize * 1.3f),
                    modifier = Modifier.padding(0.dp)
                )
            }
        }
        // scroll controls (view-level)
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = {
                scope.launch { vScroll.scrollTo((vScroll.value - 800).coerceAtLeast(0)) }
            }) { Text("↑ журнал") }
            TextButton(onClick = { scope.launch { vScroll.scrollTo(0) } }) { Text("Вверх") }
            TextButton(onClick = { scope.launch { vScroll.scrollTo(vScroll.maxValue) } }) { Text("Вниз") }
        }
    }
}

private fun lineAnnotated(row: AnsiTerminal.Row?, defaultFg: Color) = buildAnnotatedString {
    if (row == null) { append(' '); return@buildAnnotatedString }
    var currentFg: Int? = null
    var currentBold = false
    val sb = StringBuilder()
    fun flush() {
        if (sb.isNotEmpty()) {
            val color = AnsiTerminal.styleColor(currentFg, true, defaultFg.hashCode())
            withStyle(SpanStyle(color = Color(color))) { append(sb.toString()) }
            sb.clear()
        }
    }
    for (cell in row.cells) {
        val style = cell.style
        if (style.fg != currentFg || style.bold != currentBold) {
            flush()
            currentFg = style.fg
            currentBold = style.bold
        }
        sb.append(cell.ch)
    }
    flush()
}

@Composable
private fun AgentConsoleView(terminal: com.crabcode.ide.terminal.TerminalManager, fontSp: Float) {
    val revision by terminal.agentRevision.collectAsState()
    val scrollState = rememberScrollState()
    LaunchedEffect(revision) { scrollState.scrollTo(scrollState.maxValue) }
    val text = remember(revision) { terminal.agentConsoleText() }
    Text(
        text,
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        fontSize = fontSp.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun TerminalInput(session: TerminalSession) {
    var input by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Bottom
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Команда") },
            maxLines = 3,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        )
        TextButton(onClick = {
            if (input.isNotBlank()) {
                session.sendCommand(input)
                input = ""
            }
        }) { Icon(painterResource(R.drawable.ic_action_send), null) }
    }
}

@Composable
private fun TerminalControlRow(session: TerminalSession) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        TextButton(onClick = { session.interrupt() }) { Text("Ctrl+C") }
        TextButton(onClick = { session.suspendJob() }) { Text("Ctrl+Z") }
        TextButton(onClick = { session.eof() }) { Text("Ctrl+D") }
        TextButton(onClick = { session.signal(15) }) { Text("SIGTERM") }
    }
}
