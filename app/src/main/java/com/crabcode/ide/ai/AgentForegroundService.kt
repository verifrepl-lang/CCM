package com.crabcode.ide.ai

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.crabcode.ide.MainActivity
import com.crabcode.ide.R
import com.crabcode.ide.core.update.NotificationChannels

/**
 * Foreground keep-alive while an agent task is running: Android may otherwise
 * kill the process and abort a streaming answer mid-flight. The runtime itself
 * lives in the app process (CrabContext); this service only holds a visible
 * notification for the duration of the task.
 */
class AgentForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFY_ID, buildNotification())
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NotificationChannels.AGENT)
            .setSmallIcon(R.drawable.ic_stat_crab)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_agent_running))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.crabcode.ide.action.AGENT_STOP"
        private const val NOTIFY_ID = 2002

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, AgentForegroundService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AgentForegroundService::class.java).apply { action = ACTION_STOP }
                )
            }
        }
    }
}
