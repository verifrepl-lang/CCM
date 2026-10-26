package com.crabcode.ide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.crabcode.ide.core.update.NotificationChannels
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrabApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        runCatching { createNotificationChannels() }
    }

    /**
     * Every uncaught exception is appended to an app-private file that is still
     * visible to any file manager:
     *   /sdcard/Android/data/com.crabcode.ide.mobile/files/crash_log.txt
     * The default handler still runs afterwards, so the system crash dialog
     * and ANR statistics keep working. The login screen offers a share button
     * for this file when it exists.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, "crash_log.txt")
                if (file.length() > 512 * 1024) file.delete()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file.appendText(
                    buildString {
                        append("\n==== $stamp  [thread: ${thread.name}] ====\n")
                        append(Log.getStackTraceString(error))
                    }
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** SVG icons of programming languages ship as original CrabCode assets. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(true)
            .build()

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    NotificationChannels.AGENT,
                    getString(R.string.channel_agent),
                    NotificationManager.IMPORTANCE_HIGH
                ),
                NotificationChannel(
                    NotificationChannels.ENVIRONMENT,
                    getString(R.string.channel_env),
                    NotificationManager.IMPORTANCE_LOW
                ),
                NotificationChannel(
                    NotificationChannels.TERMINAL,
                    getString(R.string.channel_terminal),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        )
    }
}
