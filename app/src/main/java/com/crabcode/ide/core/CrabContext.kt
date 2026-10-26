package com.crabcode.ide.core

import android.content.Context
import com.crabcode.ide.ai.agent.AgentRuntime
import com.crabcode.ide.ai.mcp.McpManager
import com.crabcode.ide.ai.providers.ChatClients
import com.crabcode.ide.ai.providers.ProviderRepository
import com.crabcode.ide.core.auth.UserAccount
import com.crabcode.ide.core.auth.UserSpace
import com.crabcode.ide.core.settings.AppSettings
import com.crabcode.ide.data.CrabStore
import com.crabcode.ide.data.SessionEntity
import com.crabcode.ide.linux.EnvironmentManager
import com.crabcode.ide.linux.ProotExecutor
import com.crabcode.ide.terminal.TerminalManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient

/**
 * Per-account session context — every subsystem is bound to the signed-in
 * user's [UserSpace], so data, processes and secrets are isolated per account.
 */
class CrabContext(
    val context: Context,
    val account: UserAccount,
    val settings: AppSettings
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .connectTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val space: UserSpace = UserSpace.forAccount(context, account)
    val store: CrabStore = CrabStore(context, space)
    val terminal: TerminalManager = TerminalManager(context, space)
    val mcp: McpManager = McpManager(context, http, space.config, space.home)
    val executor: ProotExecutor = ProotExecutor(context)
    val providers: ProviderRepository = ProviderRepository(context, space, account)
    val clients: ChatClients = ChatClients(http)
    val environment: EnvironmentManager = EnvironmentManager(context, http, com.crabcode.ide.core.update.GitHubReleases(http))
        .also { com.crabcode.ide.linux.EnvironmentManagerHolder.instance = it }

    val agentRuntimes = mutableMapOf<String, AgentRuntime>()

    fun agentRuntime(session: SessionEntity, projectRoot: java.io.File): AgentRuntime {
        agentRuntimes[session.id]?.let { return it }
        val active = providers.resolveActive()
            ?: error("Провайдер не настроен — добавьте API-ключ в разделе «Модели»")
        val (provider, model) = active
        val runtime = AgentRuntime(
            scope = scope,
            store = store,
            sessionProvider = { session },
            projectRoot = projectRoot,
            userHome = space.home,
            provider = provider,
            apiKey = providers.apiKey(provider.id),
            model = model,
            clients = clients,
            executor = executor,
            terminal = terminal,
            mcp = mcp,
            dialogueLanguage = "ru",
            onNotification = { }
        )
        agentRuntimes[session.id] = runtime
        return runtime
    }

    fun shutdown() {
        agentRuntimes.values.forEach { it.shutdown() }
        agentRuntimes.clear()
        com.crabcode.ide.ai.agent.ApprovalBroker.cancelAll()
        terminal.shutdown()
        store.close()
        scope.cancel()
    }
}
