package com.aonminers.smminer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps a persistent notification showing
 * real-time hashrate while the miner is active.  The notification
 * disappears automatically when the user stops mining.
 */
class MiningService : Service() {

    private var updater: Thread? = null
    private val notifyMgr by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    private fun launchIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
    }

    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(applicationContext)
        val channel = NotificationChannel(
            CHANNEL_ID, "Mining status", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent hashrate indicator"
            setShowBadge(false)
        }
        notifyMgr.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("0 H/s")
        try {
            startForeground(NOTIFY_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+ without POST_NOTIFICATIONS permission — still works, just no notification
        }

        // Background thread: reads @Volatile hashrate / bestShare every 5 s
        if (updater == null || updater?.isAlive != true) {
            updater = Thread {
                var last = ""
                while (!Thread.interrupted()) {
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                    if (!isAonMinerRunning()) break
                    val hashStr = formatHashrate(hashrate)
                    val bestStr = formatDiff(bestShare)
                    val text = "Hashrate: $hashStr/s    Best: $bestStr"
                    if (text != last) {
                        last = text
                        notifyMgr.notify(NOTIFY_ID, buildNotification(text))
                    }
                }
                // miner stopped → remove notification
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }.apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        updater?.interrupt()
        updater = null
        super.onDestroy()
    }

    // ── helpers ──────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val lang = SettingsStore.loadLanguage(Language.PT)
        val title = when (lang) { Language.PT -> "smminer — Minerando"; Language.EN -> "smminer — Mining" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(launchIntent())
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(launchIntent())
                .build()
        }
    }

    private fun formatHashrate(h: Double): String =
        if (h >= 1e12) String.format("%.2f TH", h / 1e12)
        else if (h >= 1e9) String.format("%.2f GH", h / 1e9)
        else if (h >= 1e6) String.format("%.2f MH", h / 1e6)
        else String.format("%.0f H", h)

    companion object {
        private const val CHANNEL_ID = "smminer_hashrate"
        private const val NOTIFY_ID = 1
    }
}
