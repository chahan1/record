package com.chahan.record

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
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

private data class RecItem(val file: File, val title: String, val meta: String)

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvListTitle: TextView
    private lateinit var btnRecord: ImageButton
    private lateinit var btnPause: ImageButton
    private lateinit var swAuto: SwitchCompat
    private lateinit var spSource: Spinner
    private lateinit var listView: ListView
    private lateinit var adapter: RecAdapter

    private var items: List<RecItem> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var lastState = RecState.IDLE
    private var autoStartPending = false

    // 播放狀態
    private var player: MediaPlayer? = null
    private var playingPath: String? = null
    private var seeking = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                startRecording()
            } else {
                Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
            }
        }

    // 錄其他 App 的聲音：先取得系統的錄製授權（每次錄音都要同意一次）
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, RecorderService::class.java)
                        .setAction(RecorderService.ACTION_START)
                        .putExtra(RecorderService.EXTRA_SOURCE, Prefs.source(this))
                        .putExtra(RecorderService.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(RecorderService.EXTRA_RESULT_DATA, data)
                )
            } else {
                Toast.makeText(this, R.string.need_projection, Toast.LENGTH_LONG).show()
            }
        }

    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            updatePlayingRow()
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvTime = findViewById(R.id.tvTime)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvListTitle = findViewById(R.id.tvListTitle)
        btnRecord = findViewById(R.id.btnRecord)
        btnPause = findViewById(R.id.btnPause)
        swAuto = findViewById(R.id.swAuto)
        listView = findViewById(R.id.listView)

        adapter = RecAdapter()
        listView.adapter = adapter

        swAuto.isChecked = Prefs.autoStart(this)
        swAuto.setOnCheckedChangeListener { _, checked -> Prefs.setAutoStart(this, checked) }

        spSource = findViewById(R.id.spSource)
        val sourceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.src_mic),
                getString(R.string.src_internal),
                getString(R.string.src_mix)
            )
        )
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSource.adapter = sourceAdapter
        spSource.setSelection(Prefs.source(this))
        spSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Prefs.setSource(this@MainActivity, position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

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

    override fun onStop() {
        // 離開畫面時暫停播放（保留位置）
        val mp = player
        if (mp != null && mp.isPlaying) {
            mp.pause()
            adapter.notifyDataSetChanged()
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
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
        stopPlayback()
        val source = Prefs.source(this)
        if (source == Prefs.SRC_MIC) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RecorderService::class.java)
                    .setAction(RecorderService.ACTION_START)
                    .putExtra(RecorderService.EXTRA_SOURCE, Prefs.SRC_MIC)
            )
        } else {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun sendToService(action: String) {
        startService(Intent(this, RecorderService::class.java).setAction(action))
    }

    // ---------------------------------------------------------------- 畫面更新

    private fun refreshUi() {
        val s = RecorderService.state
        tvTime.text = formatTime(RecorderService.elapsedMs())

        btnRecord.setImageResource(if (s == RecState.IDLE) R.drawable.ic_mic else R.drawable.ic_stop)
        btnRecord.contentDescription =
            getString(if (s == RecState.IDLE) R.string.btn_start else R.string.btn_stop)

        spSource.isEnabled = s == RecState.IDLE
        btnPause.visibility = if (s == RecState.IDLE) View.INVISIBLE else View.VISIBLE
        btnPause.setImageResource(if (s == RecState.PAUSED) R.drawable.ic_play else R.drawable.ic_pause)
        btnPause.contentDescription =
            getString(if (s == RecState.PAUSED) R.string.btn_resume else R.string.btn_pause)

        tvStatus.setText(
            when (s) {
                RecState.IDLE -> R.string.status_idle
                RecState.RECORDING -> R.string.status_recording
                RecState.PAUSED -> R.string.status_paused
            }
        )
        tvStatus.setTextColor(
            ContextCompat.getColor(this, if (s == RecState.IDLE) R.color.text_sub else R.color.brand)
        )

        // 錄音開始時停止播放，避免聲音被錄進去
        if (s != RecState.IDLE && player != null) stopPlayback()

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
                .sortedByDescending { it.lastModified() }
            val built = files.map { describe(it) }
            runOnUiThread {
                items = built
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (built.isEmpty()) View.VISIBLE else View.GONE
                tvListTitle.text =
                    if (built.isEmpty()) getString(R.string.list_title)
                    else getString(R.string.list_title_count, built.size)
            }
        }.start()
    }

    private fun isDefaultName(base: String) = Regex("rec_\\d{8}_\\d{6}").matches(base)

    private fun describe(f: File): RecItem {
        val base = f.nameWithoutExtension
        val defaultName = isDefaultName(base)
        val start = try {
            if (defaultName) {
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(base.removePrefix("rec_"))
                    ?: Date(f.lastModified())
            } else {
                Date(f.lastModified())
            }
        } catch (e: Exception) {
            Date(f.lastModified())
        }
        val dateText = SimpleDateFormat("MM/dd  HH:mm", Locale.US).format(start)
        // 預設名稱顯示日期時間；改過名稱就顯示自訂名稱，日期放到下一行
        val title = if (defaultName) dateText else base

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
        val meta = if (defaultName) "$durText  ·  $sizeText" else "$dateText  ·  $durText  ·  $sizeText"
        return RecItem(f, title, meta)
    }

    private inner class RecAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_recording, parent, false)
            val item = items[position]
            val mp = player
            val isCurrent = mp != null && playingPath == item.file.absolutePath
            val isPlaying = isCurrent && mp != null && mp.isPlaying

            v.findViewById<TextView>(R.id.tvTitle).text = item.title
            v.findViewById<TextView>(R.id.tvMeta).text = item.meta

            val btnPlay = v.findViewById<ImageButton>(R.id.btnPlay)
            btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            btnPlay.contentDescription =
                getString(if (isPlaying) R.string.btn_pause else R.string.act_play)
            btnPlay.setOnClickListener { togglePlay(item) }

            v.findViewById<ImageButton>(R.id.btnRename).setOnClickListener { showRename(item.file) }
            v.findViewById<ImageButton>(R.id.btnShare).setOnClickListener { share(item.file) }
            v.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { confirmDelete(item.file) }

            val panel = v.findViewById<View>(R.id.playerPanel)
            if (isCurrent && mp != null) {
                panel.visibility = View.VISIBLE
                val seek = v.findViewById<SeekBar>(R.id.seek)
                val dur = mp.duration
                seek.max = dur
                seek.progress = mp.currentPosition
                setPosText(v, mp.currentPosition, dur)
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            player?.seekTo(progress)
                            setPosText(v, progress, dur)
                        }
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {
                        seeking = true
                    }

                    override fun onStopTrackingTouch(s: SeekBar?) {
                        seeking = false
                    }
                })
            } else {
                panel.visibility = View.GONE
            }
            return v
        }
    }

    private fun setPosText(row: View, pos: Int, dur: Int) {
        row.findViewById<TextView>(R.id.tvPos).text =
            "${formatTime(pos.toLong())} / ${formatTime(dur.toLong())}"
    }

    /** 每 300ms 更新正在播放那一列的進度條 */
    private fun updatePlayingRow() {
        val mp = player ?: return
        val path = playingPath ?: return
        if (seeking) return
        val index = items.indexOfFirst { it.file.absolutePath == path }
        if (index < 0) return
        val child = listView.getChildAt(index - listView.firstVisiblePosition) ?: return
        val pos = try {
            mp.currentPosition
        } catch (e: Exception) {
            return
        }
        child.findViewById<SeekBar>(R.id.seek).progress = pos
        setPosText(child, pos, mp.duration)
    }

    // ---------------------------------------------------------------- 播放

    private fun togglePlay(item: RecItem) {
        val path = item.file.absolutePath
        val current = player
        if (current != null && playingPath == path) {
            if (current.isPlaying) current.pause() else current.start()
            adapter.notifyDataSetChanged()
            return
        }

        stopPlayback()
        if (RecorderService.state != RecState.IDLE) {
            Toast.makeText(this, R.string.play_while_recording, Toast.LENGTH_SHORT).show()
            return
        }

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(path)
            mp.prepare()
            mp.setOnCompletionListener { stopPlayback() }
            mp.start()
            player = mp
            playingPath = path
        } catch (e: Exception) {
            try {
                mp.release()
            } catch (ignored: Exception) {
                // ignore
            }
            Toast.makeText(this, R.string.play_failed, Toast.LENGTH_SHORT).show()
        }
        adapter.notifyDataSetChanged()
    }

    private fun stopPlayback() {
        val mp = player
        player = null
        playingPath = null
        seeking = false
        if (mp != null) {
            try {
                mp.stop()
            } catch (e: Exception) {
                // ignore
            }
            try {
                mp.release()
            } catch (e: Exception) {
                // ignore
            }
            if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        }
    }

    // ---------------------------------------------------------------- 分享／刪除

    private fun uriFor(f: File) = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    private fun share(f: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uriFor(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, f.name))
    }

    // ---------------------------------------------------------------- 重新命名

    private fun showRename(f: File) {
        val base = f.nameWithoutExtension
        val input = EditText(this)
        input.setSingleLine()
        input.hint = getString(R.string.rename_hint)
        input.setText(if (isDefaultName(base)) "" else base)
        input.setSelection(input.text.length)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this)
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.act_rename)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> doRename(f, input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun doRename(f: File, raw: String) {
        // 移除檔名不能用的字元，最多 60 字
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), " ").trim().take(60)
        if (clean.isEmpty()) return

        val dir = f.parentFile ?: return
        var target = File(dir, "$clean.m4a")
        if (target.absolutePath == f.absolutePath) return
        if (target.exists()) {
            var i = 2
            while (File(dir, "$clean ($i).m4a").exists()) i++
            target = File(dir, "$clean ($i).m4a")
        }

        if (playingPath == f.absolutePath) stopPlayback()
        if (f.renameTo(target)) {
            loadFiles()
        } else {
            Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.act_delete)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.act_delete) { _, _ ->
                if (playingPath == f.absolutePath) stopPlayback()
                f.delete()
                loadFiles()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
