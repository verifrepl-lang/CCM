package com.crabcode.ide.ui.screens

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.crabcode.ide.R
import com.crabcode.ide.ui.CrabViewModel

/** Mandatory Google Sign-In gate. */
@Composable
fun LoginScreen(vm: CrabViewModel) {
    val busy by vm.busy.collectAsState()
    val error by vm.authError.collectAsState()

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
    }
}
