package com.cookinlet.belugas

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Creates the app's notification channel before anything can post to it. This has to run in
 * Application.onCreate() rather than MainActivity.onCreate() -- FCM can start
 * BelugasMessagingService in a fresh process (delivering a notification) without MainActivity
 * ever having run, but Application.onCreate() always runs first in any process regardless.
 */
class BelugasApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                getString(R.string.default_notification_channel_id),
                "Sighting Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications about beluga whale sightings near your subscribed zones."
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
