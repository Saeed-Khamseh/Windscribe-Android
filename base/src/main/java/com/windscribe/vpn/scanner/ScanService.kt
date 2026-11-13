package com.windscribe.vpn.scanner

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.windscribe.common.startSafeForeground
import com.windscribe.vpn.R
import com.windscribe.vpn.Windscribe
import com.windscribe.vpn.backend.Util
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Owns a running scan: starts it, keeps the process alive and foreground while it runs, shows its
 * progress with a Stop action, and leaves a result notification behind.
 *
 * Start it with [start]. The scan itself is [NetworkScanner]'s; this service only watches its state.
 * It stops itself on Finished, Cancelled or Failed, and if it is destroyed first it stops the scan
 * (nobody would be watching it) and removes its ongoing notification. A process killed mid-scan takes
 * the foreground notification with it and the service is not restarted ([START_NOT_STICKY]); the
 * preferences the scan changed are repaired at the next app start by `repairIfInterrupted`.
 */
@AndroidEntryPoint
class ScanService : Service() {
    @Inject
    lateinit var scanner: NetworkScanner

    private val logger = LoggerFactory.getLogger(SCANNER_LOG)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    @Volatile
    private var stopRequested = false
    private var finished = false
    private var foregroundFailed = false
    private var lastStartId = 0
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        // Before injection, for the same reason as the VPN service: a slow device can miss the
        // few seconds startForegroundService() allows.
        ensureChannels(this)
        try {
            startSafeForeground(
                PROGRESS_NOTIFICATION_ID,
                buildProgressNotification(this, ScanState.Probing(0, 0), stopping = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            // Started from the background (Android 12+) or without the type permission.
            logger.error("Could not go foreground; the scan will not start.", e)
            ScanRequestHandoff.clear()
            foregroundFailed = true
            stopSelf()
        }
        super.onCreate()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (foregroundFailed) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> begin(startId)
            ACTION_STOP -> requestStop()
            // The system restarted us with nothing to do, or someone sent an unknown action.
            else -> if (!scanner.state.value.isRunning()) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun begin(startId: Int) {
        lastStartId = startId
        NotificationManagerCompat.from(this).cancel(RESULT_NOTIFICATION_ID)
        // Taken first, so a request that arrives while one is being watched cannot linger.
        val request = ScanRequestHandoff.take()
        if (finished) {
            // A new scan began before the previous stop took effect: the same instance carries on.
            finished = false
            observing = false
            stopRequested = false
            startSafeForeground(
                PROGRESS_NOTIFICATION_ID,
                buildProgressNotification(this, ScanState.Probing(0, 0), stopping = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }
        if (observing) return
        val started = request != null && scanner.start(request)
        // False with a scan already running means adopting it: the service may have been recreated.
        if (!started && !scanner.state.value.isRunning()) {
            logger.warn("No scan to run; stopping the service.")
            stopSelf(startId)
            return
        }
        observing = true
        holdWakeLock()
        observer?.cancel()
        observer =
            serviceScope.launch {
                scanner.state.collect { state ->
                    when {
                        state.isRunning() -> {
                            notifyProgress(state)
                            // The collector conflates while this waits: updates are throttled, not queued.
                            delay(PROGRESS_INTERVAL_MS)
                        }

                        state.isTerminal() -> finish(state)
                    }
                }
            }
    }

    private fun requestStop() {
        if (!scanner.state.value.isRunning()) {
            stopSelf()
            return
        }
        stopRequested = true
        scanner.stop()
        notifyProgress(scanner.state.value)
    }

    private fun notifyProgress(state: ScanState) {
        if (finished) return
        NotificationManagerCompat
            .from(this)
            .notifyIfAllowed(PROGRESS_NOTIFICATION_ID, buildProgressNotification(this, state, stopRequested))
    }

    private fun finish(state: ScanState) {
        if (finished) return
        finished = true
        logger.info("Scan ended with {}; stopping the service.", state.javaClass.simpleName)
        releaseWakeLock()
        // Removes the ongoing notification; the result goes under another id, so it stays.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).notifyIfAllowed(RESULT_NOTIFICATION_ID, buildResultNotification(this, state))
        // With the id: a start that arrived since cancels the stop.
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        // Destroyed with the scan still going (stopService, a system stop): nobody is watching it.
        if (scanner.state.value.isRunning()) {
            logger.warn("Destroyed during a scan; stopping the scan.")
            scanner.stop()
        }
        // Never leave an ongoing notification behind a service that is gone.
        NotificationManagerCompat.from(this).cancel(PROGRESS_NOTIFICATION_ID)
        super.onDestroy()
    }

    /**
     * Screen off must not stop the scan: a foreground service keeps the process, and a partial wake
     * lock keeps the CPU awake for the probes and the timers. Bounded, so a bug cannot hold it forever.
     */
    private fun holdWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock =
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "windscribe:network-scan").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.windscribe.vpn.scanner.START"
        const val ACTION_STOP = "com.windscribe.vpn.scanner.STOP"

        /** The `type` extra the launcher activity reads to open the scanner screen. */
        const val EXTRA_TYPE = "type"
        const val TYPE_OPEN_SCANNER = "scanner"

        const val PROGRESS_NOTIFICATION_ID = 11
        const val RESULT_NOTIFICATION_ID = 12
        private const val PROGRESS_CHANNEL_ID = "scan_progress"
        private const val RESULT_CHANNEL_ID = "scan_result"
        private const val CONTENT_REQUEST_CODE = 300
        private const val STOP_REQUEST_CODE = 301
        private const val PROGRESS_INTERVAL_MS = 400L

        // The longest scan is 10 minutes (ScanLimits.CUSTOM) plus the clean-up.
        private const val WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L

        /**
         * Starts [request] in the service. Returns false if Android refused to start a foreground
         * service (the app is not in the foreground); nothing is running then.
         */
        fun start(
            context: Context,
            request: ScanRequest,
        ): Boolean {
            ScanRequestHandoff.put(request)
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ScanService::class.java).setAction(ACTION_START),
                )
                true
            } catch (e: Exception) {
                LoggerFactory.getLogger(SCANNER_LOG).error("Could not start the scan service.", e)
                ScanRequestHandoff.clear()
                false
            }
        }

        /** Asks a running scan to stop. Safe to call with none running. */
        fun stop(context: Context) {
            // A plain startService could be refused in the background; the service is already
            // foreground while a scan runs, so this reaches it.
            runCatching {
                context.startService(Intent(context, ScanService::class.java).setAction(ACTION_STOP))
            }
        }

        private fun ensureChannels(context: Context) {
            val manager = NotificationManagerCompat.from(context)
            manager.createNotificationChannel(
                NotificationChannelCompat
                    .Builder(PROGRESS_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.scan_channel_progress))
                    .setDescription(context.getString(R.string.scan_channel_progress_description))
                    .build(),
            )
            manager.createNotificationChannel(
                NotificationChannelCompat
                    .Builder(RESULT_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.scan_channel_result))
                    .setDescription(context.getString(R.string.scan_channel_result_description))
                    .build(),
            )
        }

        private fun openScannerIntent(context: Context): PendingIntent {
            val intent =
                Windscribe.appContext.applicationInterface.splashIntent.apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_TYPE, TYPE_OPEN_SCANNER)
                }
            return PendingIntent.getActivity(
                context,
                CONTENT_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun stopIntent(context: Context): PendingIntent =
            PendingIntent.getService(
                context,
                STOP_REQUEST_CODE,
                Intent(context, ScanService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )

        /** The ongoing notification for a running scan, from its state alone. No injected dependency. */
        internal fun buildProgressNotification(
            context: Context,
            state: ScanState,
            stopping: Boolean,
        ): Notification {
            val text =
                when {
                    stopping -> context.getString(R.string.scan_notification_stopping)
                    else -> progressText(context, state)
                }
            val builder =
                NotificationCompat
                    .Builder(context, PROGRESS_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.connecting)
                    .setContentTitle(context.getString(R.string.scan_notification_title))
                    .setContentText(text)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    .setContentIntent(openScannerIntent(context))
            when (state) {
                is ScanState.Probing -> builder.setProgress(state.total, state.done, state.total <= 0)
                is ScanState.Verifying -> builder.setProgress(state.total, state.index - 1, false)
                else -> builder.setProgress(0, 0, true)
            }
            if (!stopping) {
                builder.addAction(0, context.getString(R.string.scan_notification_stop), stopIntent(context))
            }
            return builder.build()
        }

        private fun progressText(
            context: Context,
            state: ScanState,
        ): String =
            when (state) {
                is ScanState.Probing -> context.getString(R.string.scan_notification_probing, state.done, state.total)
                is ScanState.Verifying ->
                    context.getString(
                        R.string.scan_notification_testing,
                        state.index,
                        state.total,
                        state.current.cityName,
                        Util.getProtocolLabel(state.current.protocol),
                        state.current.port.toString(),
                    )

                else -> context.getString(R.string.scan_preparing)
            }

        /** What is left behind once the scan ended: opens the scanner screen. */
        internal fun buildResultNotification(
            context: Context,
            state: ScanState,
        ): Notification {
            val text =
                when (state) {
                    is ScanState.Finished -> finishedText(context, state.results.working().size)
                    is ScanState.Cancelled -> context.getString(R.string.scan_notification_stopped)
                    is ScanState.Failed -> context.getString(R.string.scan_notification_failed, state.reason)
                    else -> context.getString(R.string.scan_notification_stopped)
                }
            return NotificationCompat
                .Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.connected)
                .setContentTitle(context.getString(R.string.scan_title))
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openScannerIntent(context))
                .build()
        }

        private fun finishedText(
            context: Context,
            working: Int,
        ): String =
            if (working == 0) {
                context.getString(R.string.scan_notification_finished_none)
            } else {
                context.resources.getQuantityString(R.plurals.scan_notification_finished, working, working)
            }

        /** Posting needs the notification permission on Android 13+; without it the call is dropped, not an error. */
        private fun NotificationManagerCompat.notifyIfAllowed(
            id: Int,
            notification: Notification,
        ) {
            try {
                notify(id, notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS was revoked between the check and the call.
            }
        }
    }
}

/**
 * Hands the [ScanRequest] to the service. A request is not parcelable and the service runs in this
 * same process, so it is passed here instead of through the intent. Taken exactly once.
 */
internal object ScanRequestHandoff {
    private val pending = AtomicReference<ScanRequest?>(null)

    fun put(request: ScanRequest) = pending.set(request)

    fun take(): ScanRequest? = pending.getAndSet(null)

    fun clear() = pending.set(null)
}
