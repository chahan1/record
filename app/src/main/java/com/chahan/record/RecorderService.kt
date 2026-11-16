package com.chahan.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RecState { IDLE, RECORDING, PAUSED }

/**
 * 前景服務：錄音並輸出 AAC（.m4a）。
 * - 麥克風：MediaRecorder
 * - 其他 App 的聲音（可混入麥克風）：PlaybackCaptureRecorder
 * 螢幕關閉或切到別的 App 時仍會持續錄音。
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.chahan.record.START"
        const val ACTION_STOP = "com.chahan.record.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.chahan.record.TOGGLE_PAUSE"

        const val EXTRA_SOURCE = "source"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1

        @Volatile
        var state: RecState = RecState.IDLE

        /** 正在寫入的檔案路徑（清單中會略過） */
        @Volatile
        var currentPath: String? = null

        @Volatile
        private var segmentStart = 0L

        @Volatile
        private var accumulated = 0L

        fun elapsedMs(): Long =
            if (state == RecState.RECORDING) {
                accumulated + (SystemClock.elapsedRealtime() - segmentStart)
            } else {
                accumulated
            }

        fun recordingsDir(ctx: Context): File {
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val dir = File(base, "recordings")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }

    private var recorder: MediaRecorder? = null
    private var pcm: PlaybackCaptureRecorder? = null
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
            ACTION_TOGGLE_PAUSE -> togglePause()
            else -> if (state == RecState.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (state != RecState.IDLE) {
            finishRecorder(deleteIfTooShort = false)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 開始

    private fun startRecording(intent: Intent) {
        if (state != RecState.IDLE) return

        val source = intent.getIntExtra(EXTRA_SOURCE, Prefs.SRC_MIC)
        val fgType = when (source) {
            Prefs.SRC_INTERNAL -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            Prefs.SRC_MIX ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        // Android 14：必須在 5 秒內進入前景，且類型要與實際用途一致
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), fgType)
        } catch (e: Exception) {
            failAndStop()
            return
        }

        val name = "rec_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
        val file = File(recordingsDir(this), name)

        try {
            if (source == Prefs.SRC_MIC) {
                startMicRecorder(file)
            } else {
                startPlaybackCapture(intent, file, withMic = source == Prefs.SRC_MIX)
            }
        } catch (e: Exception) {
            releaseRecorderQuietly()
            stopProjectionQuietly()
            file.delete()
            failAndStop()
            return
        }

        currentPath = file.absolutePath
        accumulated = 0L
        segmentStart = SystemClock.elapsedRealtime()
        state = RecState.RECORDING

        acquireWakeLock()
        notifyState()
    }

    private fun startMicRecorder(file: File) {
        val r = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder = r
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(44100)
        r.setAudioEncodingBitRate(96000)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
    }

    private fun startPlaybackCapture(intent: Intent, file: File, withMic: Boolean) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            ?: throw IllegalStateException("no projection permission")

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj

        // Android 14 要求在使用前先註冊回呼；使用者從系統列停止分享時也會進來
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { if (state != RecState.IDLE) stopRecording() }
            }
        }, mainHandler)

        val p = PlaybackCaptureRecorder(proj, withMic, file) {
            mainHandler.post { if (state != RecState.IDLE) stopRecording() }
        }
        pcm = p
        p.start()
    }

    // ---------------------------------------------------------------- 暫停／繼續

    private fun togglePause() {
        if (state != RecState.RECORDING && state != RecState.PAUSED) return
        val p = pcm
        val r = recorder
        try {
            if (p != null) {
                if (state == RecState.RECORDING) {
                    p.paused = true
                    accumulated = elapsedMs()
                    state = RecState.PAUSED
                } else {
                    p.paused = false
                    segmentStart = SystemClock.elapsedRealtime()
                    state = RecState.RECORDING
                }
            } else if (r != null) {
                if (state == RecState.RECORDING) {
                    r.pause()
                    accumulated = elapsedMs()
                    state = RecState.PAUSED
                } else {
                    r.resume()
                    segmentStart = SystemClock.elapsedRealtime()
                    state = RecState.RECORDING
                }
            } else {
                return
            }
        } catch (e: Exception) {
            return
        }
        notifyState()
    }

    // ---------------------------------------------------------------- 停止

    private fun stopRecording() {
        finishRecorder(deleteIfTooShort = true)
        maybeAutoBackup()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 錄音結束後，如果有開啟自動備份就啟動備份服務 */
    private fun maybeAutoBackup() {
        if (!Prefs.autoBackup(this)) return
        if (Prefs.scriptUrl(this).isBlank() || Prefs.scriptKey(this).isBlank()) return
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                Intent(this, BackupService::class.java).putExtra(BackupService.EXTRA_MANUAL, false)
            )
        } catch (e: Exception) {
            // 無法啟動備份不影響錄音
        }
    }

    private fun finishRecorder(deleteIfTooShort: Boolean) {
        val path = currentPath

        val p = pcm
        if (p != null) {
            p.stop()
            pcm = null
        }
        stopProjectionQuietly()

        val r = recorder
        if (r != null) {
            try {
                r.stop()
            } catch (e: RuntimeException) {
                // 錄音太短時 stop() 會丟例外，檔案無效，刪除
                if (deleteIfTooShort && path != null) File(path).delete()
            }
            try {
                r.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        recorder = null
        currentPath = null
        accumulated = 0L
        state = RecState.IDLE
        releaseWakeLock()
    }

    private fun stopProjectionQuietly() {
        try {
            projection?.stop()
        } catch (e: Exception) {
            // ignore
        }
        projection = null
    }

    private fun releaseRecorderQuietly() {
        try {
            recorder?.release()
        } catch (e: Exception) {
            // ignore
        }
        recorder = null
        pcm = null
    }

    private fun failAndStop() {
        state = RecState.IDLE
        currentPath = null
        Toast.makeText(this, R.string.record_failed, Toast.LENGTH_LONG).show()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // ignore
        }
        stopSelf()
    }

    // ---------------------------------------------------------------- WakeLock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "record:recording").apply {
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        wakeLock = null
    }

    // ---------------------------------------------------------------- 通知

    private fun notifyState() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun servicePending(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val paused = state == RecState.PAUSED
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(if (paused) R.string.notif_paused else R.string.notif_recording))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(!paused)
            .setWhen(System.currentTimeMillis() - elapsedMs())
            .addAction(
                0,
                getString(if (paused) R.string.btn_resume else R.string.btn_pause),
                servicePending(ACTION_TOGGLE_PAUSE, 1)
            )
            .addAction(0, getString(R.string.act_stop_short), servicePending(ACTION_STOP, 2))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
