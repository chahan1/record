package com.chahan.record

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import java.io.File
import java.nio.ByteOrder

/**
 * 錄「其他 App 播放的聲音」（Android 10+ AudioPlaybackCapture）。
 * 可選擇同時混入麥克風。流程：AudioRecord → MediaCodec(AAC) → MediaMuxer(.m4a)。
 * 輸出固定為 44.1kHz 立體聲 AAC。
 */
class PlaybackCaptureRecorder(
    private val projection: MediaProjection,
    private val withMic: Boolean,
    private val outFile: File,
    private val onFatal: () -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHUNK_SHORTS = 2048 // 1024 個立體聲取樣
        private const val BIT_RATE = 128000
    }

    @Volatile
    var paused = false

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var internalRec: AudioRecord? = null
    private var micRec: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var muxerOk = false
    private var framesFed = 0L
    private var failed = false
    private var anyData = false

    /** 初始化並開始錄音；失敗會丟出例外，由呼叫端處理 */
    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val ir = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf * 2, CHUNK_SHORTS * 2 * 4))
                .build()
            internalRec = ir
            if (ir.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("internal audio record not initialized")
            }

            if (withMic) {
                val micMin = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val mr = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(micMin * 2, CHUNK_SHORTS * 2 * 2)
                )
                micRec = mr
                if (mr.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("mic record not initialized")
                }
            }

            val aac = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2)
            aac.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            aac.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            aac.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_SHORTS * 2 * 2)
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(aac, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            ir.startRecording()
            micRec?.startRecording()
        } catch (e: Exception) {
            releaseAll()
            outFile.delete()
            throw e
        }

        running = true
        thread = Thread({ loop() }, "pcm-recorder").also { it.start() }
    }

    /** 停止錄音並等待檔案寫完 */
    fun stop() {
        running = false
        try {
            thread?.join(5000)
        } catch (e: InterruptedException) {
            // ignore
        }
    }

    // ------------------------------------------------------------ 錄音迴圈

    private fun loop() {
        val internal = ShortArray(CHUNK_SHORTS)
        val mic = ShortArray(CHUNK_SHORTS / 2)
        try {
            val ir = internalRec ?: return
            while (running) {
                var n = readFully(ir, internal, internal.size)
                if (n <= 0) {
                    failed = true
                    break
                }
                n -= n % 2 // 立體聲取樣需成對

                val mr = micRec
                if (mr != null) {
                    val frames = n / 2
                    val got = readFully(mr, mic, frames)
                    for (i in 0 until frames) {
                        val m = if (i < got) mic[i].toInt() else 0
                        internal[2 * i] = clip(internal[2 * i] + m)
                        internal[2 * i + 1] = clip(internal[2 * i + 1] + m)
                    }
                }

                // 暫停時仍持續讀取（避免緩衝溢位），但不送進編碼器
                if (!paused && n > 0) feed(internal, n)
                drain(false)
            }
        } catch (e: Exception) {
            failed = true
        } finally {
            finish()
        }
    }

    private fun clip(v: Int): Short =
        when {
            v > Short.MAX_VALUE -> Short.MAX_VALUE
            v < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> v.toShort()
        }

    private fun readFully(rec: AudioRecord, buf: ShortArray, count: Int): Int {
        var total = 0
        while (total < count && running) {
            val r = rec.read(buf, total, count - total)
            if (r < 0) return if (total > 0) total else r
            total += r
        }
        return total
    }

    // ------------------------------------------------------------ 編碼

    private fun feed(data: ShortArray, count: Int) {
        val c = codec ?: return
        var offset = 0
        while (offset < count) {
            var idx = c.dequeueInputBuffer(10_000)
            var tries = 0
            while (idx < 0 && tries < 50) {
                drain(false)
                idx = c.dequeueInputBuffer(10_000)
                tries++
            }
            if (idx < 0) throw IllegalStateException("encoder input stalled")

            val inBuf = c.getInputBuffer(idx) ?: throw IllegalStateException("no input buffer")
            inBuf.clear()
            val maxShorts = (inBuf.capacity() / 2) and 1.inv()
            val n = minOf(count - offset, maxShorts)
            inBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(data, offset, n)
            val pts = framesFed * 1_000_000L / SAMPLE_RATE
            c.queueInputBuffer(idx, 0, n * 2, pts, 0)
            framesFed += n / 2
            offset += n
            anyData = true
        }
    }

    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        var emptyTries = 0
        while (true) {
            val idx = c.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    emptyTries++
                    if (emptyTries > 300) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val m = muxer ?: return
                    if (!muxerStarted) {
                        trackIndex = m.addTrack(c.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer?.writeSampleData(trackIndex, buf, info)
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // ------------------------------------------------------------ 收尾

    private fun finish() {
        try {
            internalRec?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            micRec?.stop()
        } catch (e: Exception) {
            // ignore
        }

        try {
            val c = codec
            if (c != null && anyData) {
                var idx = c.dequeueInputBuffer(20_000)
                var tries = 0
                while (idx < 0 && tries < 50) {
                    drain(false)
                    idx = c.dequeueInputBuffer(20_000)
                    tries++
                }
                if (idx >= 0) {
                    val pts = framesFed * 1_000_000L / SAMPLE_RATE
                    c.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    drain(true)
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        try {
            if (muxerStarted) {
                muxer?.stop()
                muxerOk = true
            }
        } catch (e: Exception) {
            muxerOk = false
        }

        releaseAll()
        if (!muxerOk || !anyData) outFile.delete()
        if (failed) onFatal()
    }

    private fun releaseAll() {
        try {
            internalRec?.release()
        } catch (e: Exception) {
            // ignore
        }
        try {
            micRec?.release()
        } catch (e: Exception) {
            // ignore
        }
        try {
            codec?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            // ignore
        }
        try {
            muxer?.release()
        } catch (e: Exception) {
            // ignore
        }
        internalRec = null
        micRec = null
        codec = null
        muxer = null
    }
}
