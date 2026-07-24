package com.neurotruth.mobile.ui.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * NT-07 STT capture: MPEG-4/AAC at 16 kHz, hard-capped at 30 seconds.
 *
 * The cap is enforced by the platform through `setMaxDuration`, not by a UI timer, so a backgrounded
 * screen cannot leave a recorder running past it. [onMaxDurationReached] fires on the platform's
 * info callback and the caller finalizes exactly as if the user had tapped stop.
 *
 * Every exit path deletes the temporary file: success, failure, cancel and leaving the screen. The
 * file lives in `cacheDir` and is never copied anywhere else.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    var onMaxDurationReached: (() -> Unit)? = null

    val isRecording: Boolean get() = recorder != null

    /** Returns false when the device refuses to start; the caller keeps text input working. */
    fun start(): Boolean {
        if (recorder != null) return false
        val file = File(context.cacheDir, "stt_${System.currentTimeMillis()}.m4a")
        val instance = newRecorder()
        return runCatching {
            instance.setAudioSource(MediaRecorder.AudioSource.MIC)
            instance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            instance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            instance.setAudioChannels(CHANNELS)
            instance.setAudioSamplingRate(SAMPLE_RATE_HZ)
            instance.setAudioEncodingBitRate(BIT_RATE)
            instance.setMaxDuration(MAX_DURATION_MS)
            instance.setOutputFile(file.absolutePath)
            instance.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    onMaxDurationReached?.invoke()
                }
            }
            instance.prepare()
            instance.start()
            recorder = instance
            outputFile = file
            true
        }.getOrElse {
            runCatching { instance.release() }
            file.delete()
            recorder = null
            outputFile = null
            false
        }
    }

    /** Returns the finished file, or null when nothing usable was captured. */
    fun stop(): File? {
        val instance = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        val stopped = runCatching { instance.stop() }.isSuccess
        runCatching { instance.release() }
        if (!stopped || file == null || !file.exists() || file.length() <= 0L) {
            file?.delete()
            return null
        }
        return file
    }

    /** Aborts and deletes. Safe to call when nothing is recording. */
    fun cancel() {
        val instance = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        if (instance != null) {
            runCatching { instance.stop() }
            runCatching { instance.release() }
        }
        file?.delete()
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    companion object {
        const val MAX_DURATION_MS: Int = 30_000
        const val SAMPLE_RATE_HZ: Int = 16_000
        private const val CHANNELS = 1
        private const val BIT_RATE = 32_000
    }
}
