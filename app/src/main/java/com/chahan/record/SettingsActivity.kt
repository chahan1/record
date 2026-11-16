package com.chahan.record

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/** 雲端備份設定：Apps Script 網址與金鑰、自動備份、立即備份並檢查 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var tvTest: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnTest: Button
    private lateinit var btnBackupNow: Button
    private lateinit var swAutoBackup: SwitchCompat
    private lateinit var swWifi: SwitchCompat

    private val handler = Handler(Looper.getMainLooper())

    // 備份進度通知需要通知權限（Android 13+），拒絕也不影響備份本身
    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startBackup() }

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etUrl = findViewById(R.id.etUrl)
        etKey = findViewById(R.id.etKey)
        tvTest = findViewById(R.id.tvTest)
        tvStatus = findViewById(R.id.tvStatus)
        btnTest = findViewById(R.id.btnTest)
        btnBackupNow = findViewById(R.id.btnBackupNow)
        swAutoBackup = findViewById(R.id.swAutoBackup)
        swWifi = findViewById(R.id.swWifi)

        findViewById<TextView>(R.id.tvBack).setOnClickListener { finish() }

        etUrl.setText(Prefs.scriptUrl(this))
        etKey.setText(Prefs.scriptKey(this))
        swAutoBackup.isChecked = Prefs.autoBackup(this)
        swWifi.isChecked = Prefs.wifiOnly(this)

        swAutoBackup.setOnCheckedChangeListener { _, checked -> Prefs.setAutoBackup(this, checked) }
        swWifi.setOnCheckedChangeListener { _, checked -> Prefs.setWifiOnly(this, checked) }

        btnTest.setOnClickListener {
            saveFields()
            tvTest.text = getString(R.string.testing)
            btnTest.isEnabled = false
            Thread {
                val msg = DriveBackup(this).test()
                runOnUiThread {
                    tvTest.text = msg
                    btnTest.isEnabled = true
                }
            }.start()
        }

        btnBackupNow.setOnClickListener {
            saveFields()
            if (Prefs.scriptUrl(this).isBlank() || Prefs.scriptKey(this).isBlank()) {
                Toast.makeText(this, R.string.backup_not_configured, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val needNotif = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            if (needNotif) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startBackup()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        saveFields()
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun saveFields() {
        Prefs.setScript(this, etUrl.text.toString(), etKey.text.toString())
    }

    private fun startBackup() {
        if (BackupStatus.running) return
        ContextCompat.startForegroundService(
            this,
            Intent(this, BackupService::class.java).putExtra(BackupService.EXTRA_MANUAL, true)
        )
    }

    private fun refreshStatus() {
        btnBackupNow.isEnabled = !BackupStatus.running
        tvStatus.text = when {
            BackupStatus.running -> BackupStatus.text
            Prefs.lastBackup(this).isNotEmpty() -> Prefs.lastBackup(this)
            else -> getString(R.string.backup_status_none)
        }
    }
}
