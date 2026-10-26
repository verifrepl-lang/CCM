package com.crabcode.ide.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.crabcode.ide.BuildConfig
import com.crabcode.ide.R
import com.crabcode.ide.ui.CrabViewModel
import java.io.File

/** Mandatory sign-in gate (Google or local fallback) + crash-log sharing. */
@Composable
fun LoginScreen(vm: CrabViewModel) {
    val busy by vm.busy.collectAsState()
    val error by vm.authError.collectAsState()
    val ctx = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_crab_logo),
            contentDescription = null,
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text("Crab-Code-IDE", style = MaterialTheme.typography.titleLarge)
        Text(
            "Мобильная IDE с ИИ-агентом и Linux-окружением",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        if (busy != null) {
            CircularProgressIndicator()
            Text(busy ?: "", modifier = Modifier.padding(top = 16.dp))
        } else {
            Button(onClick = { vm.signIn() }, modifier = Modifier.fillMaxWidth()) {
                Image(painterResource(R.drawable.ic_google), contentDescription = null, modifier = Modifier.size(20.dp))
                Text("  Войти через Google", modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { vm.signInLocal() }, modifier = Modifier.fillMaxWidth()) {
                Text("Продолжить локально (без Google)")
            }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Вход обязателен: каждый пользователь получает изолированное " +
                "пространство проектов, чатов, терминала и ключей.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        CrashLogShare(ctx)
    }
}

/** If a previous run crashed, offer the log file for sharing straight from the gate. */
@Composable
private fun CrashLogShare(context: android.content.Context) {
    val crashFile = remember {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "crash_log.txt")
    }
    if (!crashFile.exists()) return
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context, "${BuildConfig.APPLICATION_ID}.fileprovider", crashFile
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Crab-Code-IDE crash log")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Лог сбоя"))
        }
    }) {
        Text(
            "Отправить лог последнего сбоя",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
