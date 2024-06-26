package me.devsaki.hentoid.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.devsaki.hentoid.util.download.ContentQueueManager.resetDownloadCount
import timber.log.Timber

/**
 * Broadcast receiver for when a download notification is dismissed.
 */
class DownloadNotificationDeleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        resetDownloadCount()
        Timber.d("Download count reset to 0")
    }
}