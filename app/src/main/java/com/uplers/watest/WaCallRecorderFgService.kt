package com.uplers.watest

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class WaCallRecorderFgService : Service() {

    companion object {
        private const val TAG = "WaCallRecorder"
        const val ACTION_START = "com.uplers.watest.WA_CALL_START"
        const val ACTION_STOP  = "com.uplers.watest.WA_CALL_STOP"
        const val EXTRA_WA_NUMBER  = "wa_number"
        const val EXTRA_STARTED_AT = "started_at_ms"

        private const val NOTIF_ID   = 9000
        private const val SAMPLE_RATE  = 16_000
        private const val CHANNEL_CFG  = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING     = AudioFormat.ENCODING_PCM_16BIT
        private const val AAC_BIT_RATE = 64_000

        fun startRecording(ctx: Context, waNumber: String, startedAtMs: Long) {
            val i = Intent(ctx, WaCallRecorderFgService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WA_NUMBER, waNumber)
                putExtra(EXTRA_STARTED_AT, startedAtMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stopRecording(ctx: Context) {
            ctx.startService(Intent(ctx, WaCallRecorderFgService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var pcmFile: File? = null
    private var waNumber = "unknown"
    private var startedAtMs = 0L
    @Volatile private var isRecording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP  -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (isRecording) return
        waNumber    = intent.getStringExtra(EXTRA_WA_NUMBER) ?: "unknown"
        startedAtMs = intent.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis())

        val notif = NotificationCompat.Builder(this, RecordingUploadNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("WA Recorder Test")
            .setContentText("Recording call with $waNumber…")
            .setOngoing(true).setOnlyAlertOnce(true).build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, notif)

        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, ENCODING).coerceAtLeast(8192)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, CHANNEL_CFG, ENCODING, bufSize)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed"); recorder.release(); stopSelf(); return
        }

        val dir = File(applicationContext.filesDir, "wa_recordings").apply { mkdirs() }
        pcmFile = File(dir, "wa_${startedAtMs}.pcm")
        audioRecord = recorder
        isRecording = true
        Log.i(TAG, "Recording started waNumber=$waNumber")

        recordingJob = scope.launch {
            val out = FileOutputStream(pcmFile)
            val buf = ByteArray(bufSize)
            recorder.startRecording()
            try {
                while (isActive && isRecording) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) out.write(buf, 0, read)
                }
            } finally {
                out.flush(); out.close(); recorder.stop(); recorder.release()
            }
        }
    }

    private fun handleStop() {
        if (!isRecording) { stopSelf(); return }
        val endedAtMs = System.currentTimeMillis()
        val durationSec = ((endedAtMs - startedAtMs) / 1000L).toInt()
        isRecording = false
        recordingJob?.cancel()

        val capturedPcm = pcmFile
        val capturedWa  = waNumber
        val capturedStart = startedAtMs

        Log.i(TAG, "Recording stopped waNumber=$capturedWa duration=${durationSec}s")

        scope.launch {
            try {
                if (capturedPcm != null && capturedPcm.exists() && capturedPcm.length() > 0) {
                    val m4a = File(capturedPcm.parent, capturedPcm.name.replace(".pcm", ".m4a"))
                    encodePcmToM4a(capturedPcm, m4a)
                    capturedPcm.delete()
                    if (m4a.exists() && m4a.length() > 0) {
                        Log.i(TAG, "M4A ready size=${m4a.length()} — enqueueing upload")
                        WaCallPrefs.setLastRecordingInfo(applicationContext,
                            "Caller: $capturedWa | Duration: ${durationSec}s | Size: ${m4a.length()/1024}KB")
                        WaCallUploadWorker.enqueue(applicationContext, m4a.absolutePath, capturedWa, capturedStart, endedAtMs, durationSec)
                    } else {
                        Log.e(TAG, "M4A empty after encoding")
                        WaCallPrefs.setLastRecordingInfo(applicationContext, "FAILED: M4A empty after encoding")
                    }
                } else {
                    Log.w(TAG, "PCM file missing or empty")
                    WaCallPrefs.setLastRecordingInfo(applicationContext, "FAILED: PCM file missing")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Encode/enqueue failed", e)
                WaCallPrefs.setLastRecordingInfo(applicationContext, "FAILED: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { stopSelf() }
            }
        }
    }

    private fun encodePcmToM4a(pcm: File, m4a: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(m4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var trackIndex = -1; var muxerStarted = false; var presentationUs = 0L
        val frameBytes = 1024 * 2
        val readBuf = ByteArray(frameBytes)
        val stream = FileInputStream(pcm)
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000L)
                    if (idx >= 0) {
                        val inBuf: ByteBuffer = codec.getInputBuffer(idx)!!
                        val read = stream.read(readBuf)
                        if (read <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            inBuf.clear(); inBuf.put(readBuf, 0, read)
                            codec.queueInputBuffer(idx, 0, read, presentationUs, 0)
                            presentationUs += (read / 2) * 1_000_000L / SAMPLE_RATE
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                    }
                    outIdx >= 0 -> {
                        val outBuf: ByteBuffer = codec.getOutputBuffer(outIdx)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && muxerStarted && info.size > 0) {
                            outBuf.position(info.offset); outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            stream.close()
            if (muxerStarted) muxer.stop()
            muxer.release(); codec.stop(); codec.release()
        }
    }

    override fun onDestroy() {
        isRecording = false; recordingJob?.cancel()
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Exception) {}
        super.onDestroy()
    }
}
