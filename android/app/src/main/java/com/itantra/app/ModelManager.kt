package com.itantra.app

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ModelManager"

/**
 * Download-on-demand for TTS voices not bundled in the APK.
 *
 * Bundling all 5 supported languages' STT+TTS pushed the APK to 1.02GB -- genuinely too
 * large for the PS's low/mid-range-phone target. Hindi (STT+TTS+VAD) ships in assets and
 * works instantly with zero setup, matching the flagship demo language; every other
 * language's *TTS* voice is fetched once, on first selection, from the same public URLs
 * scripts/download_models.ps1 already uses for desktop development.
 *
 * STT for the non-Hindi languages stays bundled too, for now -- not a size call, a
 * correctness one. AI4Bharat's IndicConformer STT exports (via OpenVoiceOS) are missing
 * ONNX metadata sherpa-onnx's loader hard-requires (see scripts/patch_stt_metadata.py); that
 * patch runs once, offline, with Python's onnx library, which this app doesn't have access
 * to. Reproducing it in-app would mean hand-editing ONNX's protobuf wire format at runtime
 * with no fast way to verify correctness short of a full build+deploy cycle per attempt --
 * a real, well-scoped next step (or: self-host pre-patched copies), not a same-session one.
 * TTS voices need no such patching (confirmed empirically for all 5 languages), so they're
 * the safe, immediately-downloadable half.
 *
 * This is a one-time asset fetch, not a runtime STT/TTS API call -- the same thing
 * installing any app's ML models amounts to. Recognition and synthesis themselves never
 * touch the network; see docs/metrics.md for why this doesn't compromise "fully offline".
 */
object ModelManager {

    data class TtsVoice(
        val archiveUrl: String,
        val modelFileName: String,
        val hasEspeakData: Boolean,
    )

    private val DOWNLOADABLE_TTS = mapOf(
        "en" to TtsVoice(
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2",
            modelFileName = "en_US-amy-medium.onnx",
            hasEspeakData = true,
        ),
        "ml" to TtsVoice(
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ml_IN-meera-medium.tar.bz2",
            modelFileName = "ml_IN-meera-medium.onnx",
            hasEspeakData = true,
        ),
        "gu" to TtsVoice(
            // Mimic3, not Piper -- Piper has no Gujarati voice at all. Same interface either way.
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mimic3-gu_IN-cmu-indic_low.tar.bz2",
            modelFileName = "gu_IN-cmu-indic_low.onnx",
            hasEspeakData = true,
        ),
        "bn" to TtsVoice(
            // Coqui, not Piper -- tokenizes by character, ships no espeak-ng-data.
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-coqui-bn-custom_female.tar.bz2",
            modelFileName = "model.onnx",
            hasEspeakData = false,
        ),
    )

    /** True if [lang]'s TTS voice comes from this download path (as opposed to bundled). */
    fun isDownloadable(lang: String): Boolean = lang in DOWNLOADABLE_TTS

    private fun ttsDir(context: Context, lang: String): File =
        File(context.getExternalFilesDir(null), "models/tts/$lang")

    /** True if [lang] needs no download (bundled) or has already been fetched. */
    fun isReady(context: Context, lang: String): Boolean {
        val voice = DOWNLOADABLE_TTS[lang] ?: return true
        return File(ttsDir(context, lang), voice.modelFileName).exists()
    }

    fun modelFile(context: Context, lang: String): File =
        File(ttsDir(context, lang), DOWNLOADABLE_TTS.getValue(lang).modelFileName)

    fun tokensFile(context: Context, lang: String): File =
        File(ttsDir(context, lang), "tokens.txt")

    /** "" if this voice needs no espeak-ng-data (Coqui-trained bn), matching how
     *  SherpaEngine.ttsDataDirFor treats the same case for bundled voices. */
    fun dataDir(context: Context, lang: String): String {
        val voice = DOWNLOADABLE_TTS.getValue(lang)
        return if (voice.hasEspeakData) File(ttsDir(context, lang), "espeak-ng-data").absolutePath else ""
    }

    /**
     * Downloads and extracts [lang]'s TTS voice archive. Blocking -- call off the main
     * thread. [onProgress] receives 0..100 for the download; extraction isn't usefully
     * granular so it isn't reported step-by-step.
     */
    fun download(context: Context, lang: String, onProgress: (Int) -> Unit) {
        val voice = DOWNLOADABLE_TTS.getValue(lang)
        val dir = ttsDir(context, lang)
        dir.mkdirs()
        val archiveFile = File(dir, "_archive.tar.bz2")

        val connection = URL(voice.archiveUrl).openConnection() as HttpURLConnection
        try {
            connection.connect()
            val total = connection.contentLength
            var downloaded = 0
            connection.inputStream.use { input ->
                FileOutputStream(archiveFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        if (total > 0) onProgress((downloaded * 100L / total).toInt())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        BZip2CompressorInputStream(archiveFile.inputStream()).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // Archive entries are nested one level under a folder named after the
                        // voice (e.g. vits-piper-en_US-amy-medium/en_US-amy-medium.onnx) --
                        // flatten to match the bundled-asset layout everything else uses.
                        val flatName = entry.name.substringAfter('/')
                        if (flatName.isNotEmpty()) {
                            val outFile = File(dir, flatName)
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        archiveFile.delete()
        Log.i(TAG, "Downloaded and extracted TTS voice for lang=$lang")
    }
}
