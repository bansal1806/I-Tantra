package com.itantra.app

import android.content.Context
import android.media.AudioFormat
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
    fun stopListeningAndTranscribe(): String {
        isRecording = false
        recordingThread?.join()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val total = pendingChunks.sumOf { it.size }
        if (total == 0) return ""
        val samples = FloatArray(total)
        var offset = 0
        for (chunk in pendingChunks) {
            chunk.copyInto(samples, offset)
            offset += chunk.size
        }

        val trimmed = trimWithVad(samples)
        val rec = recognizer ?: return ""
        val stream = rec.createStream()
        stream.acceptWaveform(trimmed, SAMPLE_RATE)
        rec.decode(stream)
        val text = rec.getResult(stream).text
        stream.release()
        return text
    }

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
        val segment = v.front().samples
        v.clear()
        return segment
    }

    // ---------------------------------------------------------------------
    // TTS: text -> speech playback
    // ---------------------------------------------------------------------

    /** Synthesizes and plays [text]. Blocks until synthesis is done and playback is queued. */
    fun speak(text: String) {
        val t = tts ?: return
        val audio = t.generate(text = text, sid = 0, speed = 1.0f)
        playAudio(audio.samples, audio.sampleRate)
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = AudioTrack.Builder()
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
