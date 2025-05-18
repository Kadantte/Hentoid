package me.devsaki.hentoid.core

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import com.awxkee.jxlcoder.coil.AnimatedJxlDecoder
import com.github.awxkee.avifcoil.decoder.HeifDecoder
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.devsaki.hentoid.BuildConfig
import me.devsaki.hentoid.R
import me.devsaki.hentoid.activities.TextIntentActivity
import me.devsaki.hentoid.database.CollectionDAO
import me.devsaki.hentoid.database.DatabaseMaintenance
import me.devsaki.hentoid.database.ObjectBoxDAO
import me.devsaki.hentoid.enums.Site
import me.devsaki.hentoid.enums.StorageLocation
import me.devsaki.hentoid.events.AppUpdatedEvent
import me.devsaki.hentoid.json.core.JsonSiteSettings
import me.devsaki.hentoid.notification.appUpdate.UpdateNotificationChannel
import me.devsaki.hentoid.notification.archive.ArchiveNotificationChannel
import me.devsaki.hentoid.notification.delete.DeleteNotificationChannel
import me.devsaki.hentoid.notification.download.DownloadNotificationChannel
import me.devsaki.hentoid.notification.jsonUpdate.UpdateJsonNotificationChannel
import me.devsaki.hentoid.notification.splitMerge.initChannelSplitMerge
import me.devsaki.hentoid.notification.startup.StartupNotificationChannel
import me.devsaki.hentoid.notification.transform.TransformNotificationChannel
import me.devsaki.hentoid.notification.userAction.UserActionNotificationChannel
import me.devsaki.hentoid.receiver.PlugEventsReceiver
import me.devsaki.hentoid.util.AchievementsManager
import me.devsaki.hentoid.util.Settings
import me.devsaki.hentoid.util.file.StorageCache
import me.devsaki.hentoid.util.file.findFile
import me.devsaki.hentoid.util.file.getDocumentFromTreeUriString
import me.devsaki.hentoid.util.file.readStreamAsString
import me.devsaki.hentoid.util.image.AnimatedPngDecoder
import me.devsaki.hentoid.util.initResources
import me.devsaki.hentoid.util.jsonToObject
import me.devsaki.hentoid.util.updateBookmarksJson
import me.devsaki.hentoid.workers.StartupWorker
import me.devsaki.hentoid.workers.UpdateCheckWorker
import org.conscrypt.Conscrypt
import org.greenrobot.eventbus.EventBus
import timber.log.Timber
import java.io.IOException
import java.security.Security

@Suppress("UNUSED_PARAMETER")
object AppStartup {

    var appKilled = true

    fun initApp(
        context: Context,
        onMainProgress: (Float) -> Unit,
        onSecondaryProgress: (Float) -> Unit,
        onComplete: () -> Unit
    ) {
        val prelaunchTasks: MutableList<BiConsumer<Context, (Float) -> Unit>> = ArrayList()
        prelaunchTasks.addAll(getPreLaunchTasks())
        prelaunchTasks.addAll(DatabaseMaintenance.getPreLaunchCleanupTasks())

        Timber.i("Init app : %d prelaunch tasks", prelaunchTasks.size)
        // Wait until pre-launch tasks are completed
        runPrelaunchTasks(
            context, prelaunchTasks, onMainProgress, onSecondaryProgress
        ) { postPrelaunch(context, onComplete) }
    }

    private fun postPrelaunch(
        context: Context, onComplete: () -> Unit
    ) {
        onComplete()
        // Run post-launch tasks on a worker
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(
            R.id.startup_service.toString(),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<StartupWorker>().build()
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun runPrelaunchTasks(
        context: Context,
        tasks: List<BiConsumer<Context, (Float) -> Unit>>,
        onMainProgress: (Float) -> Unit,
        onSecondaryProgress: (Float) -> Unit,
        onComplete: () -> Unit
    ) {
        // Yes, we do need this to run on the GlobalScope
        GlobalScope.launch {
            tasks.forEachIndexed { index, task ->
                Timber.i("Pre-launch task %s/%s", index + 1, tasks.size)
                try {
                    withContext(Dispatchers.IO) {
                        task.invoke(context, onSecondaryProgress)
                        onMainProgress(index * 1f / tasks.size)
                    }
                } catch (e: Exception) {
                    Timber.w(e)
                }
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    /**
     * Application initialization tasks
     * NB : Heavy operations; must be performed in the background to avoid ANR at startup
     */
    private fun getPreLaunchTasks(): List<BiConsumer<Context, (Float) -> Unit>> {
        return listOf(
            this::stopWorkers,
            this::processAppUpdate,
            this::loadSiteProperties,
            this::initNotifications,
            this::initTLS,
            this::initCoil
        )
    }

    fun getPostLaunchTasks(): List<BiConsumer<Context, (Float) -> Unit>> {
        return listOf(
            this::initStorageCache,
            this::initHelperResources,
            this::searchForUpdates,
            this::sendFirebaseStats,
            this::createBookmarksJson,
            this::createPlugReceiver,
            this::activateTextIntent,
            this::checkAchievements
        )
    }

    private fun stopWorkers(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Stop workers : start")
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_CLOSEABLE)
        Timber.i("Stop workers : done")
    }

    private fun loadSiteProperties(context: Context, emitter: (Float) -> Unit) {
        try {
            context.resources.openRawResource(R.raw.sites).use { stream ->
                val siteSettingsStr = readStreamAsString(stream)
                jsonToObject(siteSettingsStr, JsonSiteSettings::class.java)?.let { siteSettings ->
                    for ((key, value) in siteSettings.sites) {
                        for (site in Site.entries) {
                            if (site.name.equals(key, ignoreCase = true)) {
                                site.updateFrom(value)
                                break
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Timber.e(e)
        }
    }

    private fun initNotifications(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Init notifications : start")
        // Init notification channels
        StartupNotificationChannel.init(context)
        UpdateNotificationChannel.init(context)
        DownloadNotificationChannel.init(context)
        UserActionNotificationChannel.init(context)
        DeleteNotificationChannel.init(context)
        UpdateJsonNotificationChannel.init(context)
        TransformNotificationChannel.init(context)
        ArchiveNotificationChannel.init(context)
        initChannelSplitMerge(context)
        // Clears all previous notifications
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        Timber.i("Init notifications : done")
    }

    private fun processAppUpdate(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Process app update : start")
        if (Settings.lastKnownAppVersionCode < BuildConfig.VERSION_CODE) {
            Timber.d(
                "Process app update : update detected from %s to %s",
                Settings.lastKnownAppVersionCode,
                BuildConfig.VERSION_CODE
            )
            Timber.d("Process app update : Clearing webview cache")
            context.clearWebviewCache(null)
            Timber.d("Process app update : Clearing app cache")
            context.clearAppCache()
            StorageCache.init(context)
            Timber.d("Process app update : Complete")
            EventBus.getDefault().postSticky(AppUpdatedEvent())
            Settings.lastKnownAppVersionCode = BuildConfig.VERSION_CODE
        }
        Timber.i("Process app update : done")
    }

    private fun searchForUpdates(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Run app update : start")
        if (Settings.isAutomaticUpdateEnabled) {
            Timber.i("Run app update : auto-check is enabled")
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(
                R.id.update_check_service.toString(),
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UpdateCheckWorker>().build()
            )
        }
        Timber.i("Run app update : done")
    }

    private fun sendFirebaseStats(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Send Firebase stats : start")
        try {
            FirebaseAnalytics.getInstance(context).setUserProperty(
                "color_theme", Settings.colorTheme.toString()
            )
            FirebaseAnalytics.getInstance(context).setUserProperty(
                "endless", Settings.endlessScroll.toString()
            )
            FirebaseCrashlytics.getInstance().setCustomKey(
                "Library display mode", if (Settings.endlessScroll) "endless" else "paged"
            )
        } catch (e: IllegalStateException) { // Happens during unit tests
            Timber.e(e, "fail@init Crashlytics")
        }
        Timber.i("Send Firebase stats : done")
    }

    // Creates the JSON file for bookmarks if it doesn't exist
    private fun createBookmarksJson(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Create bookmarks JSON : start")
        val appRoot = getDocumentFromTreeUriString(
            context, Settings.getStorageUri(StorageLocation.PRIMARY_1)
        )
        if (appRoot != null) {
            val bookmarksJson = findFile(context, appRoot, BOOKMARKS_JSON_FILE_NAME)
            if (null == bookmarksJson) {
                Timber.i("Create bookmarks JSON : creating JSON")
                val dao: CollectionDAO = ObjectBoxDAO()
                try {
                    updateBookmarksJson(context, dao)
                } finally {
                    dao.cleanup()
                }
            } else {
                Timber.i("Create bookmarks JSON : already exists")
            }
        }
        Timber.i("Create bookmarks JSON : done")
    }

    private fun createPlugReceiver(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Create plug receiver : start")
        val rcv = PlugEventsReceiver()
        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(Intent.ACTION_POWER_CONNECTED)
        filter.addAction(Intent.ACTION_HEADSET_PLUG)
        context.registerReceiver(rcv, filter)
        Timber.i("Create plug receiver : done")
    }

    private fun initStorageCache(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Init storage cache : start")
        StorageCache.init(context)
        Timber.i("Init storage cache : done")
    }

    private fun initHelperResources(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Init helper resources : start")
        initResources(context.resources)
        Timber.i("Init helper resources : done")
    }

    private fun initTLS(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Init Conscrypt : start")
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        Timber.i("Init Conscrypt : done")
    }

    private fun checkAchievements(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Check achievements : start")
        AchievementsManager.checkPrefs()
        AchievementsManager.checkStorage(context)
        AchievementsManager.checkCollection()
        Timber.i("Check achievements : done")
    }

    private fun initCoil(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Init coil : start")
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(context)
                .components {
                    if (SDK_INT >= 28) {
                        add(AnimatedImageDecoder.Factory(false))
                    } else {
                        add(GifDecoder.Factory(false))
                    }
                    add(AnimatedPngDecoder.Factory())
                    add(AnimatedJxlDecoder.Factory())
                    add(HeifDecoder.Factory())
                }
                .build()
        }
        Timber.i("Init coil : done")
    }

    private fun activateTextIntent(context: Context, emitter: (Float) -> Unit) {
        Timber.i("Activate text intent : start")

        val flags = if (SDK_INT < Build.VERSION_CODES.R) 0
        else PackageManager.SYNCHRONOUS

        val state = if (Settings.isTextMenuOn) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, TextIntentActivity::class.java), state, flags
        )

        Timber.i("Activate text intent : done")
    }
}