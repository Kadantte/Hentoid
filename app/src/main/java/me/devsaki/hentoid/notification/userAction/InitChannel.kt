package me.devsaki.hentoid.notification.userAction

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import me.devsaki.hentoid.R

const val ID = "user action"

// IMPORTANT : ALWAYS INIT THE CHANNEL BEFORE FIRING NOTIFICATIONS !
fun init(context: Context) {
    val name: String? = context.getString(R.string.notification_user_action_title)
    val importance = NotificationManager.IMPORTANCE_DEFAULT
    val channel = NotificationChannel(ID, name, importance)
    channel.setSound(null, null)
    channel.setVibrationPattern(null)

    context.getSystemService<NotificationManager?>(NotificationManager::class.java)?.apply {
        createNotificationChannel(channel)
    }
}