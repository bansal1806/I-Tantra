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
 * Download-on-demand for STT+TTS models not bundled in the APK.
 *
 * Bundling all 10 of the PS's languages' STT+TTS would balloon the APK well past a
 * reasonable install size for its low/mid-range-phone target. Hindi (STT+TTS+VAD) ships in
 * assets and works instantly with zero setup, matching the flagship demo language; every
 * other language's STT *and* TTS models are fetched once, on first selection, and cached
 * under app-external storage after that.
 *
 * TTS voices come straight from the same public sherpa-onnx tts-models release used for
 * desktop development (`scripts/download_models.ps1`) -- no processing needed. STT is a
 * different story: AI4Bharat's IndicConformer exports (via OpenVoiceOS) are missing ONNX
 * metadata sherpa-onnx's loader hard-requires (see scripts/patch_stt_metadata.py), and that
 * patch needs Python's onnx library, which this app doesn't have access to at runtime.
 * Rather than reproduce ONNX's protobuf wire format in Kotlin, the already-patched models
 * (same ones scripts/patch_stt_metadata.py produces locally) are packaged once
 * (scripts/package_stt_for_release.py) and self-hosted as GitHub Release assets on this
 * repo -- a one-time step per model update, not a build-time or runtime dependency on
 * Python. `stt-models-v1`: https://github.com/bansal1806/I-Tantra/releases/tag/stt-models-v1
 *
 * Marathi/Kannada/Telugu/Tamil/Odia's TTS voices are also self-hosted (`mms-tts-v1`) --
 * they come from Meta's MMS-TTS project rather than sherpa-onnx's own release, since no
 * Piper/Mimic3/Coqui voice exists for any of these 5 (checked exhaustively). IMPORTANT:
 * MMS-TTS is CC-BY-NC 4.0 (non-commercial), unlike every other model here -- fine for this
 * hackathon submission, worth flagging before any commercial use. See docs/metrics.md.
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
        // Marathi/Kannada/Telugu/Tamil/Odia: no Piper/Mimic3/Coqui voice exists anywhere
        // (checked exhaustively). Meta's MMS-TTS does cover them -- ONNX-converted by the
        // community (willwade/mms-tts-multilingual-models-onnx on Hugging Face) and
        // self-hosted here the same way the STT models below are. Character-tokenized like
        // bn, no espeak-ng-data. IMPORTANT: MMS-TTS is CC-BY-NC 4.0 (non-commercial) -- the
        // one non-permissively-licensed model in this project; see docs/metrics.md.
        "mr" to TtsVoice(
            archiveUrl = "https://github.com/bansal1806/I-Tantra/releases/download/mms-tts-v1/mms-tts-mr.tar.bz2",
            modelFileName = "model.onnx",
            hasEspeakData = false,
        ),
        "kn" to TtsVoice(
            archiveUrl = "https://github.com/bansal1806/I-Tantra/releases/download/mms-tts-v1/mms-tts-kn.tar.bz2",
            modelFileName = "model.onnx",
            hasEspeakData = false,
        ),
        "te" to TtsVoice(
            archiveUrl = "https://github.com/bansal1806/I-Tantra/releases/download/mms-tts-v1/mms-tts-te.tar.bz2",
            modelFileName = "model.onnx",
            hasEspeakData = false,
        ),
        "ta" to TtsVoice(
            archiveUrl = "https://github.com/bansal1806/I-Tantra/releases/download/mms-tts-v1/mms-tts-ta.tar.bz2",
            modelFileName = "model.onnx",
            hasEspeakData = false,
        ),
        "or" to TtsVoice(
            archiveUrl = "https://github.com/bansal1806/I-Tantra/releases/download/mms-tts-v1/mms-tts-or.tar.bz2",
            modelFileName = "model.onnx",
            hasEspeakData = false,
        ),
    )

    // Self-hosted, pre-patched (scripts/patch_stt_metadata.py already applied) IndicConformer
    // STT models -- every downloadable language uses the same file names inside its archive
    // (model.int8.onnx, tokens.txt), so no per-language metadata beyond the URL is needed.
    private val DOWNLOADABLE_STT_URLS = mapOf(
        "en" to "https://github.com/bansal1806/I-Tantra/releases/download/stt-models-v1/stt-en.tar.bz2",
        "ml" to "https://github.com/bansal1806/I-Tantra/releases/download/stt-models-v1/stt-ml.tar.bz2",
        "gu" to "https://github.com/bansal1806/I-Tantra/releases/download/stt-models-v1/stt-gu.tar.bz2",
        "bn" to "https://github.com/bansal1806/I-Tantra/releases/download/stt-models-v1/stt-bn.tar.bz2",
        "mr" to "https://github.com/bansal1806/I-Tantra/releases/download/stt-models-v1/stt-mr.tar.bz2",
        "kn" to "https://github.com/bansal1806/I-Tantra/releases/download/stt-models-v1/stt-kn.tar.bz2",
        "te" to "https://github.com/bansal1806/I-Tantra/releases/download/stt-models-v1/stt-te.tar.bz2",
        "ta" to "https://github.com/bansal1806/I-Tantra/releases/download/stt-models-v1/stt-ta.tar.bz2",
        "or" to "https://github.com/bansal1806/I-Tantra/releases/download/stt-models-v1/stt-or.tar.bz2",
    )

    /** True if [lang]'s TTS voice comes from this download path (as opposed to bundled). */
    fun isDownloadable(lang: String): Boolean = lang in DOWNLOADABLE_TTS

    /** True if [lang]'s STT model comes from this download path (as opposed to bundled). */
    fun isSttDownloadable(lang: String): Boolean = lang in DOWNLOADABLE_STT_URLS

    private fun ttsDir(context: Context, lang: String): File =
        File(context.getExternalFilesDir(null), "models/tts/$lang")

    private fun sttDir(context: Context, lang: String): File =
        File(context.getExternalFilesDir(null), "models/stt/$lang")

    /** True if [lang] needs no download (bundled) or has already been fetched -- both TTS
     *  and STT, since [SherpaEngine.init] needs both ready before it can load a language. */
    fun isReady(context: Context, lang: String): Boolean {
        val ttsVoice = DOWNLOADABLE_TTS[lang]
        val ttsOk = ttsVoice == null || File(ttsDir(context, lang), ttsVoice.modelFileName).exists()
        val sttOk = !isSttDownloadable(lang) || sttModelFile(context, lang).exists()
        return ttsOk && sttOk
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

    fun sttModelFile(context: Context, lang: String): File =
        File(sttDir(context, lang), "model.int8.onnx")

    fun sttTokensFile(context: Context, lang: String): File =
        File(sttDir(context, lang), "tokens.txt")

    /**
     * Downloads and extracts everything [lang] needs that isn't bundled -- its STT model (if
     * downloadable) then its TTS voice (if downloadable), in that order. Blocking -- call off
     * the main thread. [onProgress] receives 0..100 across the *whole* fetch (both archives
     * combined, weighted by download bytes), not per-archive, so the caller can show one
     * smooth progress number instead of two disjoint ones.
     */
    fun download(context: Context, lang: String, onProgress: (Int) -> Unit) {
        val sttUrl = DOWNLOADABLE_STT_URLS[lang]
        val ttsVoice = DOWNLOADABLE_TTS[lang]

        // Both legs report progress against a shared 0..100 range, split by each archive's
        // share of an (approximate, hardcoded) total -- exact bytes aren't known until each
        // connection opens, but STT archives are consistently ~2.5x TTS archives here, close
        // enough for a progress bar (not a byte-accurate metric).
        val sttWeight = if (sttUrl != null) 70 else 0
        val ttsWeight = if (ttsVoice != null) 100 - sttWeight else 0

        if (sttUrl != null) {
            val dir = sttDir(context, lang)
            downloadAndExtract(dir, sttUrl) { pct -> onProgress(pct * sttWeight / 100) }
            Log.i(TAG, "Downloaded and extracted STT model for lang=$lang")
        }
        if (ttsVoice != null) {
            val dir = ttsDir(context, lang)
            downloadAndExtract(dir, ttsVoice.archiveUrl) { pct ->
                onProgress(sttWeight + pct * ttsWeight / 100)
            }
            Log.i(TAG, "Downloaded and extracted TTS voice for lang=$lang")
        }
        onProgress(100)
    }

    /** Shared by both STT and TTS: fetch [url] into [dir]/_archive.tar.bz2, then extract it
     *  flat into [dir], stripping the one folder level every archive (sherpa-onnx's own
     *  releases and scripts/package_stt_for_release.py's output alike) nests entries under. */
    private fun downloadAndExtract(dir: File, url: String, onProgress: (Int) -> Unit) {
        dir.mkdirs()
        val archiveFile = File(dir, "_archive.tar.bz2")

        val connection = URL(url).openConnection() as HttpURLConnection
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
    }
}
