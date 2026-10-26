package com.crabcode.ide.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.media.SoundPool
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crabcode.ide.BuildConfig
import com.crabcode.ide.CrabApp
import com.crabcode.ide.MainActivity
import com.crabcode.ide.R
import com.crabcode.ide.ai.agent.AgentRuntime
import com.crabcode.ide.core.CrabContext
import com.crabcode.ide.core.auth.GoogleAuth
import com.crabcode.ide.core.auth.UserAccount
import com.crabcode.ide.core.settings.AppSettings
import com.crabcode.ide.core.update.AppUpdateInfo
import com.crabcode.ide.core.update.GitHubReleases
import com.crabcode.ide.core.update.NotificationChannels
import com.crabcode.ide.data.ProjectEntity
import com.crabcode.ide.data.SessionEntity
import com.crabcode.ide.terminal.TerminalManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Root view model: authentication state, active CrabContext (per user),
 * selected project/session, environment and OTA state.
 */
class CrabViewModel(app: Application) : AndroidViewModel(app) {

    private val _account = MutableStateFlow<com.crabcode.ide.core.auth.UserAccount?>(null)
    val account: StateFlow<com.crabcode.ide.core.auth.UserAccount?> get() = _account

    val settings = AppSettings(app)
    val theme: StateFlow<String> = settings.theme
        .stateIn(viewModelScope, SharingStarted.Eagerly, "crab-dark")

    private var ctx: CrabContext? = null
    fun context(): CrabContext = ctx ?: error("Нет активной сессии пользователя")

    fun hasContext(): Boolean = ctx != null

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> get() = _busy

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> get() = _authError

    private val _updateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val updateInfo: StateFlow<AppUpdateInfo?> get() = _updateInfo

    private val _otaBusy = MutableStateFlow(false)
    val otaBusy: StateFlow<Boolean> get() = _otaBusy

    val releases = GitHubReleases(okHttpClient())

    private val _selectedProject = MutableStateFlow<ProjectEntity?>(null)
    val selectedProject: StateFlow<ProjectEntity?> get() = _selectedProject

    private val _selectedSession = MutableStateFlow<SessionEntity?>(null)
    val selectedSession: StateFlow<SessionEntity?> get() = _selectedSession

    private val _currentFile = MutableStateFlow<File?>(null)
    val currentFile: StateFlow<File?> get() = _currentFile

    private var soundPool: SoundPool? = null
    private var soundDone = 0
    private var soundStopped = 0

    init {
        // restore session after app restart
        GoogleAuth.currentAccount(app)?.let { restore(it) }
    }

    private val httpFallback: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder().build()
    }

    fun okHttpClient(): okhttp3.OkHttpClient = ctx?.http ?: httpFallback

    fun signIn() {
        viewModelScope.launch {
            _busy.value = getApplication<CrabApp>().getString(R.string.auth_in_progress)
            _authError.value = null
            runCatching {
                val auth = GoogleAuth(getApplication(), okHttpClient())
                auth.signIn()
            }.onSuccess { restore(it) }
                .onFailure { _authError.value = it.message ?: it.javaClass.simpleName }
            _busy.value = null
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val app = getApplication<CrabApp>()
            ctx?.shutdown()
            ctx = null
            GoogleAuth(app, okHttpClient()).signOut()
            _account.value = null
            _selectedProject.value = null
            _selectedSession.value = null
        }
    }

    private fun restore(account: UserAccount) {
        val app = getApplication<CrabApp>()
        val context = CrabContext(app, account, settings)
        ctx = context
        _account.value = account
        viewModelScope.launch {
            context.terminal.restore()
            context.environment.installIfNeeded()
            checkForUpdates(silent = true)
        }
    }

    fun selectProject(project: ProjectEntity?) {
        _selectedProject.value = project
        _selectedSession.value = null
        _currentFile.value = null
    }

    fun selectSession(session: SessionEntity?) { _selectedSession.value = session }

    fun openFile(file: File?) { _currentFile.value = file }

    fun agentRuntimeFor(session: SessionEntity): AgentRuntime {
        val project = _selectedProject.value ?: error("Проект не выбран")
        val c = context()
        return c.agentRuntime(session, c.space.project(project.id))
    }

    // ------------------------------------------------------------ notifications

    fun ensureSounds() {
        if (soundPool != null) return
        soundPool = SoundPool.Builder().setMaxStreams(2).build()
        soundDone = soundPool!!.load(getApplication(), R.raw.success, 1)
        soundStopped = soundPool!!.load(getApplication(), R.raw.stopped, 1)
    }

    fun notifyAgent(event: AgentRuntime.AgentNotification) {
        val app = getApplication<CrabApp>()
        viewModelScope.launch {
            val enabled = when (event) {
                AgentRuntime.AgentNotification.TASK_DONE -> settings.notifyAgentDone
                AgentRuntime.AgentNotification.NEEDS_APPROVAL -> settings.notifyApproval
                AgentRuntime.AgentNotification.ERROR, AgentRuntime.AgentNotification.QUOTA -> settings.notifyError
            }
            val soundEnabled = when (event) {
                AgentRuntime.AgentNotification.TASK_DONE -> settings.soundAgentDone
                AgentRuntime.AgentNotification.NEEDS_APPROVAL -> settings.soundAgentApproval
                AgentRuntime.AgentNotification.ERROR, AgentRuntime.AgentNotification.QUOTA -> settings.soundError
            }.first()
            if (event == AgentRuntime.AgentNotification.NEEDS_APPROVAL) ensureSounds()

            val text = when (event) {
                AgentRuntime.AgentNotification.TASK_DONE -> app.getString(R.string.notif_task_done)
                AgentRuntime.AgentNotification.NEEDS_APPROVAL -> app.getString(R.string.notif_approval)
                AgentRuntime.AgentNotification.ERROR -> app.getString(R.string.notif_error)
                AgentRuntime.AgentNotification.QUOTA -> app.getString(R.string.notif_quota)
            }

            if (soundEnabled) {
                soundPool?.play(
                    if (event == AgentRuntime.AgentNotification.TASK_DONE) soundDone else soundStopped,
                    1f, 1f, 1, 0, 1f
                )
            }
            if (enabled.first() && androidx.core.content.ContextCompat.checkSelfPermission(
                    app, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33) {
                val pi = PendingIntent.getActivity(
                    app, 0, Intent(app, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val n = NotificationCompat.Builder(app, NotificationChannels.AGENT)
                    .setSmallIcon(R.drawable.ic_stat_crab)
                    .setContentTitle(app.getString(R.string.app_name))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build()
                runCatching { NotificationManagerCompat.from(app).notify(1001, n) }
            }
        }
    }

    // ------------------------------------------------------------ OTA

    fun checkForUpdates(silent: Boolean = false) {
        viewModelScope.launch {
            _otaBusy.value = true
            runCatching {
                val release = releases.latestRelease()
                val info = releases.appUpdate(release)
                if (info != null && info.version != BuildConfig.VERSION_NAME &&
                    info.version != settings.otaSkippedVersion.first()) {
                    _updateInfo.value = info
                }
            }
            _otaBusy.value = false
        }
    }

    fun skipUpdate(version: String) {
        viewModelScope.launch { settings.setOtaSkippedVersion(version); _updateInfo.value = null }
    }

    /** Downloads the APK into app files and launches the system installer. */
    fun downloadAndInstallApk(onProgress: (Int) -> Unit, onError: (String) -> Unit) {
        val info = _updateInfo.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val target = File(getApplication<CrabApp>().filesDir, "update_${info.version}.apk")
                val downloader = com.crabcode.ide.core.update.ResumableDownloader(okHttpClient())
                downloader.download(info.apkUrl, target, info.sha256) { p ->
                    if (p is com.crabcode.ide.core.update.ResumableDownloader.Progress.Bytes && p.total > 0) {
                        onProgress((p.downloaded * 100 / p.total).toInt())
                    }
                }
                withContext(Dispatchers.Main) { installApk(target) }
            }.onFailure { withContext(Dispatchers.Main) { onError(it.message ?: "Ошибка загрузки") } }
        }
    }

    private fun installApk(file: File) {
        val app = getApplication<CrabApp>()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            app, "${BuildConfig.APPLICATION_ID}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { app.startActivity(intent) }
    }
}
