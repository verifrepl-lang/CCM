package com.crabcode.ide.linux

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.crabcode.ide.MainActivity
import com.crabcode.ide.R
import com.crabcode.ide.core.update.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Linux environment install/update alive
 * while the UI may be backgrounded (long downloads, rootfs extraction).
 */
class EnvironmentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, buildNotification(getString(R.string.env_service_running)))
        when (intent?.action) {
            ACTION_INSTALL -> {
                val userHash = intent.getStringExtra(EXTRA_USER_HASH) ?: return START_NOT_STICKY
                scope.launch {
                    val holder = EnvironmentManagerHolder.instance
                    holder?.installIfNeeded(intent.getBooleanExtra(EXTRA_FORCE, false))
                    stopSelf()
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NotificationChannels.ENVIRONMENT)
            .setSmallIcon(R.drawable.ic_stat_crab)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val ACTION_INSTALL = "com.crabcode.ide.action.INSTALL_ENV"
        const val EXTRA_FORCE = "force"
        const val EXTRA_USER_HASH = "user_hash"
        private const val NOTIFY_ID = 2001

        fun startInstall(context: Context, force: Boolean) {
            val intent = Intent(context, EnvironmentService::class.java).apply {
                action = ACTION_INSTALL
                putExtra(EXTRA_FORCE, force)
            }
            context.startForegroundService(intent)
        }
    }
}
