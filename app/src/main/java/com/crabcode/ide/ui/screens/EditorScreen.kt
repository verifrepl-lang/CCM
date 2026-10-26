package com.crabcode.ide.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crabcode.ide.R
import com.crabcode.ide.editor.SyntaxHighlighter
import com.crabcode.ide.ui.CrabViewModel
import com.crabcode.ide.ui.theme.CodeStyle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Code editor with regex syntax highlighting (mobile substitute for Monaco). */
@Composable
fun EditorScreen(vm: CrabViewModel) {
    val file by vm.currentFile.collectAsState()
    val scope = rememberCoroutineScope()

    if (file == null || !file!!.isFile) {
        EmptyHintBox("Откройте файл из раздела «Файлы»")
        return
    }

    val f = file!!
    var text by remember(f.absolutePath) { mutableStateOf(f.readText()) }
    var dirty by remember(f.absolutePath) { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(f.absolutePath) { text = f.readText(); dirty = false }

    Column(Modifier.fillMaxSize().imePadding().padding(8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            LanguageIcon(f)
            Text(
                f.name + if (dirty) " •" else "",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            IconButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { f.writeText(text) }
                            .onSuccess { dirty = false; saveError = null }
                            .onFailure { saveError = it.message }
                    }
                }
            }) {
                Icon(painterResource(R.drawable.ic_action_save), "Сохранить")
            }
        }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        // Highlighted view + plain editing field. On phones editing plain text with
        // a highlighted preview above is the pragmatic approach.
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; dirty = true },
            modifier = Modifier.fillMaxSize(),
            textStyle = CodeStyle,
            label = { }
        )
    }
}

/** Render highlighted code (used in previews/diffs). */
@Composable
fun HighlightedCode(code: String, fileName: String) {
    val language = remember(fileName) { SyntaxHighlighter.languageOf(fileName) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(4.dp)) {
        code.lines().take(2000).forEach { line ->
            val annotated = highlightLine(line, language)
            Text(annotated, style = CodeStyle.copy(fontSize = 13.sp), softWrap = false)
        }
    }
}

private fun highlightLine(line: String, language: String): AnnotatedString = buildAnnotatedString {
    for (token in SyntaxHighlighter.tokenize(line, language)) {
        val color = when (token.type) {
            SyntaxHighlighter.TokenType.KEYWORD -> 0xFFC678DD
            SyntaxHighlighter.TokenType.STRING -> 0xFF98C379
            SyntaxHighlighter.TokenType.COMMENT -> 0xFF5C6370
            SyntaxHighlighter.TokenType.NUMBER -> 0xFFD19A66
            SyntaxHighlighter.TokenType.TYPE -> 0xFFE5C07B
            SyntaxHighlighter.TokenType.FUNCTION -> 0xFF61AFEF
            SyntaxHighlighter.TokenType.ANNOTATION -> 0xFFE06C75
            SyntaxHighlighter.TokenType.OPERATOR -> 0xFF56B6C2
            else -> 0xFFABB2BF
        }.toInt()
        withStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(color))) { append(token.text) }
    }
}
