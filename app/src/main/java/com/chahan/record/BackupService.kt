package com.chahan.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 前景服務：在背景把錄音備份到 Google Drive，並顯示進度通知 */
class BackupService : Service() {

    companion object {
        const val EXTRA_MANUAL = "manual"
        private const val CHANNEL_ID = "backup"
        private const val NOTIF_ID = 2
        private const val NOTIF_DONE_ID = 3
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_backup_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manual = intent?.getBooleanExtra(EXTRA_MANUAL, false) ?: false

        // 用 startForegroundService 啟動的服務，一定要先進入前景
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(getString(R.string.backup_preparing)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (BackupStatus.running) return START_NOT_STICKY
        BackupStatus.running = true
        BackupStatus.text = getString(R.string.backup_preparing)

        Thread {
            var result: SyncResult
            try {
                result = if (!manual && Prefs.wifiOnly(this) && isMetered()) {
                    SyncResult(true, getString(R.string.backup_skip_wifi))
                } else {
                    DriveBackup(this).sync { progress ->
                        BackupStatus.text = progress
                        updateNotification(progress)
                    }
                }
            } catch (e: Exception) {
                result = SyncResult(false, "備份失敗：${e.message}")
            }

            val stamp = SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date())
            val summary = "$stamp\n${result.message}"
            Prefs.setLastBackup(this, summary)
            BackupStatus.text = summary
            BackupStatus.running = false

            showDone(result)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()

        return START_NOT_STICKY
    }

    private fun isMetered(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_backup_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun showDone(result: SyncResult) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_backup_title))
            .setContentText(result.message.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(result.message))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_DONE_ID, n)
    }
}
