package com.itantra.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "SherpaEngine"
private const val SAMPLE_RATE = 16000
private const val VAD_WINDOW = 512

/**
 * [text] is what push-to-talk recognized; [durationSeconds] is the trimmed utterance's
 * length (used for M3's "equivalent voice-note size" bitrate comparison); [decodeMs] is how
 * long the STT model itself took, i.e. real-time factor = decodeMs / (durationSeconds*1000)
 * -- the M4 latency number the rubric weights explicitly.
 */
data class SttResult(val text: String, val durationSeconds: Float, val decodeMs: Long = 0)

/** [synthMs] is how long TTS synthesis took; [audioDurationSeconds] is the produced audio's
 *  length, so RTF = synthMs / (audioDurationSeconds*1000), same idea as [SttResult]. */
data class TtsResult(val synthMs: Long, val audioDurationSeconds: Float)

/**
 * Wraps sherpa-onnx's VAD + STT + TTS for one language, loading models straight from the
 * app's assets (see build.gradle.kts noCompress + the models copied under src/main/assets).
 *
 * M1 scope: one phone, no networking. Push-to-talk itself is the endpointer (record while
 * held, transcribe on release) -- VAD is still real work, though: it trims leading/trailing
 * silence from the held-button recording before STT sees it, which is exactly the
 * "endpointing" job the architecture doc describes, just anchored to the button instead of
 * running hands-free. Hands-free auto-endpointing (finalizing before release) is a latency
 * optimization for a later pass, not needed for this milestone.
 *
 * One language is loaded at a time (not all bundled languages held in RAM at once, per the
 * efficiency goal) -- call [init] again with a different lang to switch; it releases the
 * previous models first.
 */
class SherpaEngine(private val context: Context) {

    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    private var tts: OfflineTts? = null

    @Volatile
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    /**
     * Loads all three models from assets for [lang], releasing whatever was previously
     * loaded first. Call off the main thread -- this takes real time.
     */
    fun init(lang: String) {
        release()
        val assets = context.assets

        vad = Vad(
            assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "vad/silero_vad.onnx",
                    threshold = 0.5F,
                    minSilenceDuration = 0.25F,
                    minSpeechDuration = 0.25F,
                    windowSize = VAD_WINDOW,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            ),
        )

        recognizer = OfflineRecognizer(
            assets,
            OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = "stt/$lang/model.int8.onnx"),
                    tokens = "stt/$lang/tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            ),
        )

        val ttsModelFile = ttsModelFileFor(lang)
        val assetDataDir = "tts/$lang/espeak-ng-data"
        val extractedRoot = copyDataDir(assetDataDir)
        tts = OfflineTts(
            assets,
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "tts/$lang/$ttsModelFile",
                        tokens = "tts/$lang/tokens.txt",
                        dataDir = "$extractedRoot/$assetDataDir",
                    ),
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )
        Log.i(TAG, "SherpaEngine ready for lang=$lang")
    }

    /** Frees whatever models are currently loaded. Safe to call when nothing is loaded. */
    fun release() {
        vad?.release()
        recognizer?.release()
        tts?.release()
        vad = null
        recognizer = null
        tts = null
    }

    private fun ttsModelFileFor(lang: String): String = when (lang) {
        "hi" -> "hi_IN-priyamvada-medium.onnx"
        "en" -> "en_US-amy-medium.onnx"
        else -> throw IllegalArgumentException("No TTS voice bundled for lang=$lang")
    }

    // ---------------------------------------------------------------------
    // STT: push-to-talk recording
    // ---------------------------------------------------------------------

    /** Starts recording from the mic. Call on button-down. */
    @Suppress("MissingPermission") // caller (MainActivity) checks RECORD_AUDIO first
    fun startListening() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, VAD_WINDOW * 2) * 4,
        )
        val chunks = mutableListOf<FloatArray>()
        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ShortArray(VAD_WINDOW)
            while (isRecording) {
                val n = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (n > 0) {
                    chunks.add(FloatArray(n) { buffer[it] / 32768.0f })
                }
            }
            pendingChunks = chunks
        }
        recordingThread?.start()
    }

    private var pendingChunks: List<FloatArray> = emptyList()

    /** Stops recording and runs VAD-trimmed STT on whatever was captured. Call on button-up. */
    fun stopListeningAndTranscribe(): SttResult {
        isRecording = false
        recordingThread?.join()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val total = pendingChunks.sumOf { it.size }
        if (total == 0) return SttResult("", 0f)
        val samples = FloatArray(total)
        var offset = 0
        for (chunk in pendingChunks) {
            chunk.copyInto(samples, offset)
            offset += chunk.size
        }

        val trimmed = trimWithVad(samples)
        val durationSeconds = trimmed.size / SAMPLE_RATE.toFloat()
        val rec = recognizer ?: return SttResult("", durationSeconds)
        val stream = rec.createStream()
        stream.acceptWaveform(trimmed, SAMPLE_RATE)
        val t0 = System.nanoTime()
        rec.decode(stream)
        val decodeMs = (System.nanoTime() - t0) / 1_000_000
        val text = rec.getResult(stream).text
        stream.release()
        return SttResult(text, durationSeconds, decodeMs)
    }

    /**
     * Trims leading/trailing silence, but -- unlike a naive "just take the first segment"
     * approach -- keeps every segment VAD finds, concatenated in order. A push-to-talk
     * recording routinely contains more than one segment (any natural pause/breath mid-
     * sentence splits it), and dropping everything after the first one silently truncated
     * real speech; this was a real accuracy bug, not just a missing nice-to-have.
     */
    private fun trimWithVad(samples: FloatArray): FloatArray {
        val v = vad ?: return samples
        v.reset()
        var i = 0
        while (i + VAD_WINDOW <= samples.size) {
            v.acceptWaveform(samples.copyOfRange(i, i + VAD_WINDOW))
            i += VAD_WINDOW
        }
        v.flush()
        if (v.empty()) return samples // no speech detected -- fall back to the raw clip

        val segments = mutableListOf<FloatArray>()
        while (!v.empty()) {
            segments.add(v.front().samples)
            v.pop()
        }
        val total = segments.sumOf { it.size }
        val combined = FloatArray(total)
        var offset = 0
        for (seg in segments) {
            seg.copyInto(combined, offset)
            offset += seg.size
        }
        return combined
    }

    // ---------------------------------------------------------------------
    // TTS: text -> speech playback
    // ---------------------------------------------------------------------

    /**
     * Synthesizes and plays [text]. Blocks until synthesis is done and playback is queued
     * (there's no stop/cancel control anywhere in the UI, so playback is non-interruptible
     * by construction -- that's part of what the PS asks for alert messages specifically,
     * and it costs nothing extra to also be true for normal ones).
     *
     * [alert] is the other half of the PS's alert requirement: routes audio through the
     * ALARM stream (the one Android usage class designed to sound even through silent/DND,
     * same mechanism an alarm-clock app relies on) and forces that stream to max volume
     * first, instead of playing at whatever the media volume happens to be.
     */
    fun speak(text: String, alert: Boolean = false): TtsResult {
        val t = tts ?: return TtsResult(0, 0f)
        val t0 = System.nanoTime()
        val audio = t.generate(text = text, sid = 0, speed = 1.0f)
        val synthMs = (System.nanoTime() - t0) / 1_000_000
        if (alert) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        }
        playAudio(audio.samples, audio.sampleRate, alert)
        val audioDurationSeconds = audio.samples.size / audio.sampleRate.toFloat()
        return TtsResult(synthMs, audioDurationSeconds)
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int, alert: Boolean) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val attributes = AudioAttributes.Builder()
            .setUsage(if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, samples.size * 4))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track.play()
    }

    // ---------------------------------------------------------------------
    // Asset extraction (espeak-ng-data must live on real disk -- it's read by a C library
    // that can't go through Android's AssetManager). Mirrors sherpa-onnx's own Android
    // example apps (SherpaOnnxTts) exactly.
    // ---------------------------------------------------------------------

    private fun copyDataDir(dataDir: String): String {
        copyAssets(dataDir)
        return context.getExternalFilesDir(null)!!.absolutePath
    }

    private fun copyAssets(path: String) {
        try {
            val entries = context.assets.list(path)
            if (entries.isNullOrEmpty()) {
                copyFile(path)
            } else {
                File("${context.getExternalFilesDir(null)}/$path").mkdirs()
                for (entry in entries) {
                    copyAssets(if (path.isEmpty()) entry else "$path/$entry")
                }
            }
        } catch (ex: IOException) {
            Log.e(TAG, "Failed to copy asset $path", ex)
        }
    }

    private fun copyFile(filename: String) {
        try {
            context.assets.open(filename).use { input ->
                FileOutputStream("${context.getExternalFilesDir(null)}/$filename").use { output ->
                    input.copyTo(output)
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to copy asset file $filename", ex)
        }
    }
}
