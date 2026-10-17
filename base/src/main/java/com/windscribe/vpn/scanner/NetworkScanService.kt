package com.windscribe.vpn.scanner

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.windscribe.common.startSafeForeground
import com.windscribe.vpn.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** User-started, non-sticky foreground operation. Process death leaves completed checkpoints to resume. */
@AndroidEntryPoint
class NetworkScanService : Service() {
    @Inject lateinit var scanner: NetworkScanner
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private var notifications: NotificationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            notifications?.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.scanner_title), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = notification(ScanProgress(running = true))
        startSafeForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == STOP) {
            if (scanJob == null) stopSelf() else scanJob?.cancel()
            return START_NOT_STICKY
        }
        if (scanJob?.isActive == true || !scanner.session.acquire()) return START_NOT_STICKY
        val route = runCatching { intent?.getStringExtra(ROUTE)?.let { Gson().fromJson(it, ScanRoute::class.java) } }.getOrNull()
        wakeLock =
            checkNotNull(
                getSystemService(PowerManager::class.java),
            ).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:network-scanner").apply {
                acquire()
            }
        scanJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Enter try/finally before Stop can cancel a not-yet-dispatched job.
                val updates = launch { scanner.progress.collect { notifications?.notify(NOTIFICATION_ID, notification(it)) } }
                try {
                    scanner.load()
                    scanner.scan(route)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    scanner.reportFailure(error.message ?: "Unable to start scanner")
                } finally {
                    updates.cancel()
                    scanner.session.release()
                    wakeLock?.let { if (it.isHeld) it.release() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        return START_NOT_STICKY
    }

    private fun notification(progress: ScanProgress): Notification {
        val open =
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        val stop =
            PendingIntent.getService(
                this,
                NOTIFICATION_ID,
                Intent(this, NetworkScanService::class.java).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_scanner_notification)
            .setContentTitle(getString(R.string.scanner_notification_title))
            .setContentText(
                progress.route?.let { "${it.location.city} · ${it.protocolName}:${it.port}" }
                    ?: getString(R.string.scanner_notification_description),
            ).setSubText(getString(R.string.scanner_progress_count, progress.completed, progress.total))
            .setProgress(progress.total, progress.completed, progress.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(
                open?.let {
                    PendingIntent.getActivity(
                        this,
                        NOTIFICATION_ID,
                        it,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                },
            ).addAction(0, getString(R.string.scanner_stop), stop)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "network_scanner"
        private const val NOTIFICATION_ID = 42
        private const val STOP = "com.windscribe.vpn.STOP_NETWORK_SCAN"
        private const val ROUTE = "scanner_route"

        fun start(
            context: Context,
            route: ScanRoute? = null,
        ) {
            val intent = Intent(context, NetworkScanService::class.java)
            route?.let { intent.putExtra(ROUTE, Gson().toJson(it)) }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NetworkScanService::class.java).setAction(STOP))
        }
    }
}
