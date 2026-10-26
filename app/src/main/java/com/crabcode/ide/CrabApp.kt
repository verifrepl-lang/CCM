package com.crabcode.ide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.crabcode.ide.core.update.NotificationChannels

class CrabApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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
