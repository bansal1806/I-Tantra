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
private const val MIN_FREE_STORAGE_MB = 100L
private const val MAX_REMOTE_MUTE_MS = 8000L

/** The only language whose STT and TTS ship in assets -- see ModelManager for why the rest
 *  download on demand instead. VAD stays bundled for every language regardless (it's
 *  language-agnostic and tiny -- nothing to gain by ever downloading it separately). */
private const val BUNDLED_LANG = "hi"

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

/** Thrown by [SherpaEngine.init] on a problem worth telling the user about directly
 *  (rather than a native crash with an opaque message) -- currently just low storage. */
class EngineInitException(message: String) : Exception(message)

/**
 * Wraps sherpa-onnx's VAD + STT + TTS for one language. VAD always loads from the app's
 * assets (language-agnostic, tiny, bundled regardless). STT and TTS do too, but only for
 * [BUNDLED_LANG] -- every other language's STT model and TTS voice are fetched on demand by
 * [ModelManager] and loaded from real disk instead, to keep the APK a reasonable size (see
 * ModelManager's doc for the full reasoning).
 *
 * Two capture modes, matching the PS's own "push-to-talk, or if turned off it should work
 * like a phone" requirement:
 *   - [startListening]/[stopListeningAndTranscribe] -- push-to-talk. The button is the
 *     endpointer (record while held, transcribe on release); VAD still trims silence from
 *     what was captured, it just doesn't decide when the utterance ends.
 *   - [startPhoneMode]/[stopPhoneMode] -- continuous, hands-free. VAD itself decides
 *     sentence boundaries (on pauses) while the mic runs continuously, and each sentence is
 *     transcribed and handed off as soon as it's ready -- streamed one at a time, like a
 *     live call, not batched until some later stop event.
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

    @Volatile
    private var phoneModeActive = false
    private var phoneModeRecord: AudioRecord? = null
    private var phoneModeThread: Thread? = null

    /** True while [speak] is playing audio out loud. [startPhoneMode]'s capture loop checks
     *  this so the phone doesn't hear its own TTS playback and transcribe/re-send it back --
     *  a real feedback-loop risk once capture is continuous instead of button-bounded. Not
     *  relevant to push-to-talk (mic and speaker are never both live there). */
    @Volatile
    private var isSpeaking = false

    /**
     * True while the *other* phone has told us (via Frame.PRIORITY_MUTE_START) it's about to
     * play something -- [isSpeaking] alone can't catch this, since it only guards a phone
     * against hearing its *own* playback, not the peer's. Without this, phone mode's
     * continuous mic on each phone picks up the other phone's speaker, transcribes it as new
     * speech, and re-sends it: a real cross-device acoustic feedback loop found via on-device
     * testing (see Frame.kt for the exact symptom). [MainActivity] calls [setRemoteMuted] when
     * a mute control frame arrives.
     */
    @Volatile
    private var remoteMuted = false
    private var remoteMuteToken = 0

    /** Called by [MainActivity] on receiving a PRIORITY_MUTE_START/STOP control frame. Also
     *  self-clears after [MAX_REMOTE_MUTE_MS] regardless, in case the matching STOP is lost
     *  (a dropped packet, or the peer's app dying mid-utterance) -- otherwise this phone would
     *  stay silently deaf to real speech for the rest of the call. */
    fun setRemoteMuted(muted: Boolean) {
        remoteMuteToken++
        val myToken = remoteMuteToken
        remoteMuted = muted
        if (muted) {
            Thread {
                Thread.sleep(MAX_REMOTE_MUTE_MS)
                if (remoteMuteToken == myToken) remoteMuted = false
            }.start()
        }
    }

    /**
     * Loads all three models from assets for [lang], releasing whatever was previously
     * loaded first. Call off the main thread -- this takes real time.
     *
     * Throws [EngineInitException] on a low-storage precondition failure (extracting
     * espeak-ng-data onto real disk needs headroom; running out mid-extraction previously
     * meant a native crash with an opaque message instead of a clear one), or whatever
     * exception sherpa-onnx's native loaders throw for anything else that goes wrong.
     */
    fun init(lang: String) {
        release()
        checkStorageHeadroom()
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

        // Hindi's STT ships in assets (the flagship, zero-setup language); everything else
        // is fetched on demand by ModelManager and lives on real disk -- pass a null
        // AssetManager to OfflineRecognizer for that case, its documented signal to load
        // from file paths instead of assets (same pattern OfflineTts uses below).
        val sttModelPath: String
        val sttTokensPath: String
        val sttAssets: android.content.res.AssetManager?
        if (lang == BUNDLED_LANG) {
            sttModelPath = "stt/$lang/model.int8.onnx"
            sttTokensPath = "stt/$lang/tokens.txt"
            sttAssets = assets
        } else {
            sttModelPath = ModelManager.sttModelFile(context, lang).absolutePath
            sttTokensPath = ModelManager.sttTokensFile(context, lang).absolutePath
            sttAssets = null
        }
        recognizer = OfflineRecognizer(
            sttAssets,
            OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = sttModelPath),
                    tokens = sttTokensPath,
                    numThreads = 2,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            ),
        )

        // Same bundled-vs-downloaded split for TTS -- Hindi from assets, everything else
        // fetched by ModelManager and loaded from real disk.
        val ttsModelPath: String
        val ttsTokensPath: String
        val ttsDataDirPath: String
        val ttsAssets: android.content.res.AssetManager?
        if (lang == BUNDLED_LANG) {
            val assetDataDir = ttsDataDirFor(lang)
            ttsDataDirPath = if (assetDataDir.isNotEmpty()) {
                val extractedRoot = copyDataDir(assetDataDir)
                "$extractedRoot/$assetDataDir"
            } else {
                ""
            }
            ttsModelPath = "tts/$lang/${ttsModelFileFor(lang)}"
            ttsTokensPath = "tts/$lang/tokens.txt"
            ttsAssets = assets
        } else {
            ttsModelPath = ModelManager.modelFile(context, lang).absolutePath
            ttsTokensPath = ModelManager.tokensFile(context, lang).absolutePath
            ttsDataDirPath = ModelManager.dataDir(context, lang)
            ttsAssets = null
        }
        tts = OfflineTts(
            ttsAssets,
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = ttsModelPath,
                        tokens = ttsTokensPath,
                        dataDir = ttsDataDirPath,
                    ),
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )
        Log.i(TAG, "SherpaEngine ready for lang=$lang")
    }

    private fun checkStorageHeadroom() {
        val dir = context.getExternalFilesDir(null) ?: return
        val usableMb = dir.usableSpace / (1024 * 1024)
        if (usableMb < MIN_FREE_STORAGE_MB) {
            throw EngineInitException(
                "Only ${usableMb}MB free storage -- need at least ${MIN_FREE_STORAGE_MB}MB " +
                    "to extract TTS data. Free up space and try again."
            )
        }
    }

    /** Frees whatever models are currently loaded. Safe to call when nothing is loaded. */
    fun release() {
        // Stop phone mode first -- its capture thread reads vad/recognizer, so releasing
        // those out from under it while it's still running would race.
        stopPhoneMode()
        vad?.release()
        recognizer?.release()
        tts?.release()
        vad = null
        recognizer = null
        tts = null
    }

    // Only ever called for lang == BUNDLED_LANG ("hi") -- every other language's TTS
    // voice comes from ModelManager instead, which knows its own filenames/dataDir rules
    // (en/ml/gu are Piper-or-Mimic3/espeak-based like Hindi; bn is Coqui-trained and needs
    // no espeak-ng-data at all).
    private fun ttsModelFileFor(lang: String): String = when (lang) {
        "hi" -> "hi_IN-priyamvada-medium.onnx"
        else -> throw IllegalArgumentException("No bundled TTS voice for lang=$lang")
    }

    private fun ttsDataDirFor(lang: String): String = "tts/$lang/espeak-ng-data"

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
        return transcribeSegment(trimmed) ?: SttResult("", trimmed.size / SAMPLE_RATE.toFloat())
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
    // STT: phone mode (continuous, hands-free -- push-to-talk turned off)
    // ---------------------------------------------------------------------

    /**
     * Starts continuous listening. VAD segments the stream on pauses, and each completed
     * segment is transcribed and handed to [onSentence] immediately -- while the mic keeps
     * running for the next one, rather than waiting for a "stop" event. Runs its own
     * background thread; [onSentence] is called on that thread, not the caller's.
     *
     * Uses VOICE_COMMUNICATION (not MIC, as push-to-talk uses) so devices with hardware echo
     * cancellation apply it to this stream -- not guaranteed on the low-end phones this
     * targets, which is why [isSpeaking] is also checked as a software-level guard.
     */
    @Suppress("MissingPermission") // caller (MainActivity) checks RECORD_AUDIO first
    fun startPhoneMode(onSentence: (SttResult) -> Unit) {
        if (phoneModeActive) return
        val v = vad ?: return
        v.reset()
        phoneModeActive = true

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, VAD_WINDOW * 2) * 4,
        )
        phoneModeRecord = record
        record.startRecording()

        phoneModeThread = Thread {
            val buffer = ShortArray(VAD_WINDOW)
            while (phoneModeActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0 || isSpeaking || remoteMuted) continue
                v.acceptWaveform(FloatArray(n) { buffer[it] / 32768.0f })
                while (!v.empty()) {
                    val segment = v.front().samples
                    v.pop()
                    transcribeSegment(segment)?.let(onSentence)
                }
            }
        }
        phoneModeThread?.start()
    }

    private fun transcribeSegment(segment: FloatArray): SttResult? {
        val rec = recognizer ?: return null
        val durationSeconds = segment.size / SAMPLE_RATE.toFloat()
        val stream = rec.createStream()
        stream.acceptWaveform(segment, SAMPLE_RATE)
        val t0 = System.nanoTime()
        rec.decode(stream)
        val decodeMs = (System.nanoTime() - t0) / 1_000_000
        val text = rec.getResult(stream).text
        stream.release()
        return if (text.isNotBlank()) SttResult(text, durationSeconds, decodeMs) else null
    }

    /** Stops continuous listening. Blocks until the capture thread has actually exited. */
    fun stopPhoneMode() {
        phoneModeActive = false
        phoneModeThread?.join()
        phoneModeThread = null
        phoneModeRecord?.stop()
        phoneModeRecord?.release()
        phoneModeRecord = null
        setRemoteMuted(false) // don't carry a stale mute into the next call
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
     *
     * [onPlaybackDone], if given, fires once playback has actually finished (not once this
     * function returns, which is earlier -- see the isSpeaking comment below). MainActivity
     * uses this to send a PRIORITY_MUTE_STOP to the peer at the right moment, in the two-
     * phone echo-avoidance handshake documented on [remoteMuted].
     */
    fun speak(text: String, alert: Boolean = false, onPlaybackDone: (() -> Unit)? = null): TtsResult {
        val t = tts ?: return TtsResult(0, 0f)
        val t0 = System.nanoTime()
        val audio = t.generate(text = text, sid = 0, speed = 1.0f)
        val synthMs = (System.nanoTime() - t0) / 1_000_000
        if (alert) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        }
        val audioDurationSeconds = audio.samples.size / audio.sampleRate.toFloat()

        // isSpeaking stays up for the audio's actual playback duration, not just until
        // playback starts (which is when this function itself returns, matching the PS's
        // "time until the sentence started as audio" latency metric) -- otherwise phone
        // mode's mic would start listening again while our own voice is still audible.
        isSpeaking = true
        playAudio(audio.samples, audio.sampleRate, alert)
        Thread {
            Thread.sleep((audioDurationSeconds * 1000).toLong())
            isSpeaking = false
            onPlaybackDone?.invoke()
        }.start()

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
