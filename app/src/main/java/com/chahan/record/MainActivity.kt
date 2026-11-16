package com.chahan.record

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class RecItem(val file: File, val label: String)

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPause: Button
    private lateinit var swAuto: SwitchCompat
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>

    private var items: List<RecItem> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var lastState = RecState.IDLE
    private var autoStartPending = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                startRecording()
            } else {
                Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
            }
        }

    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvTime = findViewById(R.id.tvTime)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnRecord = findViewById(R.id.btnRecord)
        btnPause = findViewById(R.id.btnPause)
        swAuto = findViewById(R.id.swAuto)
        listView = findViewById(R.id.listView)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { showActions(it) }
        }

        swAuto.isChecked = Prefs.autoStart(this)
        swAuto.setOnCheckedChangeListener { _, checked -> Prefs.setAutoStart(this, checked) }

        btnRecord.setOnClickListener {
            if (RecorderService.state == RecState.IDLE) {
                requestStart()
            } else {
                sendToService(RecorderService.ACTION_STOP)
            }
        }
        btnPause.setOnClickListener { sendToService(RecorderService.ACTION_TOGGLE_PAUSE) }

        // 只在「全新啟動」時自動錄音，旋轉畫面或切回前景不會重複觸發
        autoStartPending = savedInstanceState == null && Prefs.autoStart(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        loadFiles()
        if (autoStartPending) {
            autoStartPending = false
            if (RecorderService.state == RecState.IDLE) requestStart()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------------------------------------------------------------- 錄音控制

    private fun requestStart() {
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMic) {
            startRecording()
            return
        }
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(need.toTypedArray())
    }

    private fun startRecording() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START)
        )
    }

    private fun sendToService(action: String) {
        startService(Intent(this, RecorderService::class.java).setAction(action))
    }

    // ---------------------------------------------------------------- 畫面更新

    private fun refreshUi() {
        val s = RecorderService.state
        tvTime.text = formatTime(RecorderService.elapsedMs())
        btnRecord.setText(if (s == RecState.IDLE) R.string.btn_start else R.string.btn_stop)
        btnPause.visibility = if (s == RecState.IDLE) View.GONE else View.VISIBLE
        btnPause.setText(if (s == RecState.PAUSED) R.string.btn_resume else R.string.btn_pause)
        tvStatus.setText(
            when (s) {
                RecState.IDLE -> R.string.status_idle
                RecState.RECORDING -> R.string.status_recording
                RecState.PAUSED -> R.string.status_paused
            }
        )
        if (lastState != RecState.IDLE && s == RecState.IDLE) {
            handler.postDelayed({ loadFiles() }, 400)
        }
        lastState = s
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", m, sec)
        }
    }

    // ---------------------------------------------------------------- 錄音清單

    private fun loadFiles() {
        Thread {
            val dir = RecorderService.recordingsDir(this)
            val current = RecorderService.currentPath
            val files = (dir.listFiles { f -> f.extension == "m4a" && f.absolutePath != current }
                ?: emptyArray())
                .sortedByDescending { it.name }
            val built = files.map { RecItem(it, describe(it)) }
            runOnUiThread {
                items = built
                adapter.clear()
                adapter.addAll(built.map { it.label })
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (built.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun describe(f: File): String {
        val start = try {
            val stamp = f.nameWithoutExtension.removePrefix("rec_")
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(stamp) ?: Date(f.lastModified())
        } catch (e: Exception) {
            Date(f.lastModified())
        }
        val whenText = SimpleDateFormat("MM/dd HH:mm", Locale.US).format(start)

        var durText = "--:--"
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(f.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (ms != null) durText = formatTime(ms)
        } catch (e: Exception) {
            // 無法讀取時間長度就顯示 --:--
        } finally {
            try {
                mmr.release()
            } catch (e: Exception) {
                // ignore
            }
        }

        val sizeText = String.format(Locale.US, "%.1f MB", f.length() / 1024.0 / 1024.0)
        return "$whenText   ·   $durText   ·   $sizeText"
    }

    private fun showActions(item: RecItem) {
        val actions = arrayOf(
            getString(R.string.act_play),
            getString(R.string.act_share),
            getString(R.string.act_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(item.file.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> play(item.file)
                    1 -> share(item.file)
                    2 -> confirmDelete(item.file)
                }
            }
            .show()
    }

    private fun uriFor(f: File) = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    private fun play(f: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(f), "audio/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_LONG).show()
        }
    }

    private fun share(f: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uriFor(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, f.name))
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.ok) { _, _ ->
                f.delete()
                loadFiles()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
