package github.leavesczy.wifip2p.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import github.leavesczy.wifip2p.R
import github.leavesczy.wifip2p.hub.HubState
import github.leavesczy.wifip2p.hub.Phase
import github.leavesczy.wifip2p.hub.Role
import github.leavesczy.wifip2p.transfer.Formatters
import github.leavesczy.wifip2p.transferHub
import github.leavesczy.wifip2p.ui.SessionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process (and therefore the Wi-Fi Direct group and the sockets) alive while a transfer
 * session is open - also with the screen off or the app in the background. It also holds a CPU
 * wake lock and a Wi-Fi lock, because Android otherwise throttles the radio and the CPU after a few
 * seconds of screen-off, which is the usual reason for a transfer that "slows down" or stalls.
 */
class TransferService : Service() {

    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startInForeground(buildNotification(NotificationModel.from(this, transferHub().state.value)))
        acquireLocks()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = serviceScope
        serviceScope.launch {
            transferHub().state
                .map { NotificationModel.from(this@TransferService, it) }
                .distinctUntilChanged()
                .conflate()
                .collect { model ->
                    if (model.role == Role.NONE) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            ?.notify(NOTIFICATION_ID, buildNotification(model))
                    }
                    delay(400) // at most ~2 notification updates per second
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            transferHub().end()
            stopSelf()
            return START_NOT_STICKY
        }
        // Every startForegroundService() call must be answered with startForeground().
        startInForeground(buildNotification(NotificationModel.from(this, transferHub().state.value)))
        return START_NOT_STICKY
    }

    /** The user swiped the app away: nobody can see or control the session any more. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        transferHub().end()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /** Android 15+ limits data-sync foreground services to ~6 hours. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        transferHub().end()
        stopSelf()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        releaseLocks()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------

    private fun startInForeground(notification: Notification) {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (_: Exception) {
            // e.g. started from the background on Android 12+: the app keeps working while it is visible
            stopSelf()
        }
    }

    private fun buildNotification(model: NotificationModel): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, SessionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(model.title)
            .setContentText(model.text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_stop), stop)
        if (model.progressPermille >= 0) {
            builder.setProgress(1000, model.progressPermille, false)
        }
        return builder.build()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiP2P:transfer").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (_: Exception) {
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "WifiP2P:transfer").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        const val ACTION_STOP = "github.leavesczy.wifip2p.action.STOP"
        private const val CHANNEL_ID = "transfer"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = context.getString(R.string.notification_channel_description)
                channel.setShowBadge(false)
                manager.createNotificationChannel(channel)
            }
        }
    }
}

/** Everything the notification shows; a data class so unchanged states are not re-posted. */
private data class NotificationModel(
    val role: Role,
    val title: String,
    val text: String,
    /** 0..1000, or -1 for "no progress bar". */
    val progressPermille: Int
) {
    companion object {
        fun from(context: Context, state: HubState): NotificationModel {
            val out = state.activeOut
            val incoming = state.activeIn
            if (out != null || incoming != null) {
                val title = context.getString(
                    when {
                        out != null && incoming != null -> R.string.notification_title_both
                        out != null -> R.string.notification_title_send
                        else -> R.string.notification_title_receive
                    }
                )
                val speed = (out?.bytesPerSecond ?: 0L) + (incoming?.bytesPerSecond ?: 0L)
                val done = (out?.batchBytes ?: 0L) + (incoming?.batchBytes ?: 0L)
                val total = (out?.batchTotal ?: 0L) + (incoming?.batchTotal ?: 0L)
                val permille = if (total > 0) (done * 1000 / total).toInt().coerceIn(0, 1000) else 0
                val text = context.getString(
                    R.string.notification_progress,
                    Formatters.speed(speed), Formatters.duration(Formatters.etaSeconds(total - done, speed))
                )
                return NotificationModel(state.role, title, text, permille)
            }
            val title = context.getString(R.string.notification_title_idle)
            val text = when (state.phase) {
                Phase.CONNECTED -> context.getString(R.string.notification_connected, state.peerName ?: "")
                Phase.READY -> context.getString(
                    if (state.role == Role.HOST) R.string.notification_ready_host else R.string.notification_ready_join
                )

                Phase.ERROR -> state.detail ?: context.getString(R.string.error_connection_lost)
                else -> context.getString(R.string.notification_preparing)
            }
            return NotificationModel(state.role, title, text, -1)
        }
    }
}
