package app.bolbol.tv.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import app.bolbol.tv.core.database.DownloadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Is the engine saving something? Written by [AndroidEngineListener], watched by [DownloadService]. */
internal object DownloadActivity {
    val active = MutableStateFlow(false)
}

/**
 * Keeps the process alive (foreground, type dataSync) while the engine saves a file. It does no work itself: the
 * engine runs in [DownloadRepositoryImpl]'s scope and starts this service when a download begins
 * ([AndroidEngineListener]). The service stops **itself** once [DownloadActivity.active] is false, always after
 * `startForeground()` (stopping a `startForegroundService()` service before that crashes the app on API 28+).
 * Not sticky: after a process death the queue resumes at the next app start.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var lastStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val notification = DownloadNotifier.latest ?: DownloadNotifier(this).build(title = null, percent = null)
        startForeground(DownloadNotifier.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watchJob == null) {
            watchJob = scope.launch {
                DownloadActivity.active.collect { active ->
                    if (!active) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId) // a newer start (a new download meanwhile) keeps it running
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** The one ongoing notification: «در حال دانلود» + title + Persian percent and a progress bar. */
internal class DownloadNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun build(title: String?, percent: Int?): Notification {
        ensureChannel()
        val text = listOfNotNull(title, percent?.let { "${it.toPersianDigits()}٪" }).joinToString(" · ")
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(TITLE)
            .setContentText(text.ifEmpty { null })
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .apply { launchIntent()?.let(::setContentIntent) }
            .build()
            .also { latest = it }
    }

    fun show(notification: Notification) {
        try {
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            DownloadLog.i("notification refused: ${e.message}") // API 33+ without POST_NOTIFICATIONS
        }
    }

    fun cancel() {
        latest = null
        manager?.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (manager?.getNotificationChannel(CHANNEL_ID) != null) return
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW))
    }

    private fun launchIntent(): PendingIntent? {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: pm.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val NOTIFICATION_ID = 4_401
        private const val CHANNEL_ID = "downloads"
        private const val CHANNEL_NAME = "دانلودها"
        private const val TITLE = "در حال دانلود"

        /** Last built notification, for the service's startForeground. */
        @Volatile var latest: Notification? = null
    }
}

/** Foreground service + notification + wake/Wi-Fi locks, driven by the engine's activity. */
internal class AndroidEngineListener(context: Context) : EngineListener {
    private val appContext = context.applicationContext
    private val notifier = DownloadNotifier(appContext)
    private val serviceIntent = Intent(appContext, DownloadService::class.java)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var active = false
    private var lastPercent: Int? = -1

    override fun onActive(item: DownloadEntity, bytes: Long, total: Long?) {
        val percent = total?.takeIf { it > 0 }?.let { ((bytes * 100) / it).toInt().coerceIn(0, 100) }
        val title = listOfNotNull(item.faTitle.ifBlank { item.title }, item.subtitleLine).joinToString(" · ")
        if (!active) {
            active = true
            notifier.build(title, percent)
            DownloadActivity.active.value = true
            try {
                appContext.startForegroundService(serviceIntent)
            } catch (e: RuntimeException) {
                // API 31+ refuses a start from the background; the download still runs while the process lives.
                DownloadLog.i("foreground service not started: ${e.message}")
            }
            acquireLocks()
        } else if (percent != lastPercent) {
            notifier.show(notifier.build(title, percent))
        }
        lastPercent = percent
    }

    override fun onIdle() {
        if (!active) return
        active = false
        lastPercent = -1
        DownloadActivity.active.value = false // the service stops itself (see DownloadService)
        notifier.cancel()
        releaseLocks()
    }

    private fun acquireLocks() {
        try {
            wakeLock = appContext.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mehrnaz:download")
                ?.apply { setReferenceCounted(false); acquire(LOCK_TIMEOUT_MS) }
            @Suppress("DEPRECATION") // FULL_HIGH_PERF is what API 29/30 have; the mode is ignored on 34+.
            wifiLock = appContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mehrnaz:download")
                ?.apply { setReferenceCounted(false); acquire() }
        } catch (e: RuntimeException) {
            DownloadLog.i("locks not acquired: ${e.message}")
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private companion object {
        /** Safety net: a 2.6 GB file at 1 MB/s takes 45 min. The lock is re-taken by the next download. */
        const val LOCK_TIMEOUT_MS = 3L * 60 * 60 * 1000
    }
}

/** Is there a usable network, and tell me when one comes back. */
internal class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    fun isAvailable(): Boolean {
        val cm = manager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun watch(onAvailable: () -> Unit) {
        try {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onAvailable()
            })
        } catch (e: RuntimeException) {
            DownloadLog.i("network callback not registered: ${e.message}")
        }
    }
}
