package com.itantra.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * The wire format: what actually crosses the link between two phones. This is the whole
 * point of iTantra -- a spoken sentence becomes this many bytes instead of a voice
 * recording. Deliberately tiny:
 *
 *   [1B version][1B lang][1B priority][2B text length][UTF-8 text bytes]
 *
 * 5 bytes of header + however long the sentence is in UTF-8 -- typically tens of bytes for
 * a spoken sentence, vs. kilobytes for even a well-compressed voice note. `priority` carries
 * both the M3 alert side-channel and (PRIORITY_MUTE_START/STOP) phone mode's echo-avoidance
 * signaling -- both content-free concerns riding the same tiny header instead of a second
 * channel.
 */
data class BitrateComparison(
    val frameBytes: Int,
    val equivalentVoiceNoteBytes: Int,
    val compressionRatio: Float,
)

data class Frame(
    val lang: String,
    val priority: Int = PRIORITY_NORMAL,
    val text: String,
) {
    /** Total bytes this frame takes on the wire, including the header -- the number the
     *  bitrate showcase (M3) will put next to an equivalent voice-note size. */
    val byteSize: Int
        get() = HEADER_SIZE + text.toByteArray(StandardCharsets.UTF_8).size

    /**
     * M3's "how small is this really" showcase: what actually crossed the wire (this frame)
     * vs. what the same [durationSeconds] of speech would have cost as a compressed voice
     * note at a realistic Opus bitrate (16kbps -- squarely in the ~6-24kbps range voice
     * apps like WhatsApp actually use; see docs/architecture.md). This is the number the
     * whole project is about.
     */
    fun bitrateComparison(durationSeconds: Float): BitrateComparison {
        val equivalentBytes = (durationSeconds * OPUS_VOICE_BITRATE_BPS / 8f)
            .toInt()
            .coerceAtLeast(1)
        return BitrateComparison(
            frameBytes = byteSize,
            equivalentVoiceNoteBytes = equivalentBytes,
            compressionRatio = equivalentBytes.toFloat() / byteSize,
        )
    }

    fun writeTo(out: DataOutputStream) {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        out.writeByte(VERSION)
        out.writeByte(langToCode(lang))
        out.writeByte(priority)
        out.writeShort(textBytes.size)
        out.write(textBytes)
        out.flush()
    }

    companion object {
        const val VERSION = 1
        const val HEADER_SIZE = 5 // version + lang + priority + 2-byte length
        const val PRIORITY_NORMAL = 0
        const val PRIORITY_ALERT = 1

        // Control frames (empty text), not content: phone mode's mic runs continuously, so
        // without coordination each phone transcribes and re-sends the *other* phone's own
        // TTS playback -- a real cross-device acoustic feedback loop found via on-device
        // testing (a two-word reply looped and audibly degraded, "what are you doing" ->
        // "are you doing" -> "you doing", until the call was manually ended). MUTE_START/STOP
        // let the phone about to play something tell the peer to pause listening around it.
        const val PRIORITY_MUTE_START = 2
        const val PRIORITY_MUTE_STOP = 3

        const val OPUS_VOICE_BITRATE_BPS = 16000

        private fun langToCode(lang: String): Int = when (lang) {
            "hi" -> 0
            "en" -> 1
            else -> 0xFF
        }

        private fun codeToLang(code: Int): String = when (code) {
            0 -> "hi"
            1 -> "en"
            else -> "?"
        }

        /** Blocks until one full frame has arrived, or throws on disconnect/malformed input. */
        @Throws(IOException::class)
        fun readFrom(inp: DataInputStream): Frame {
            val version = inp.readUnsignedByte()
            if (version != VERSION) {
                throw IOException("Unsupported frame version $version")
            }
            val lang = codeToLang(inp.readUnsignedByte())
            val priority = inp.readUnsignedByte()
            val textLen = inp.readUnsignedShort()
            val textBytes = ByteArray(textLen)
            inp.readFully(textBytes)
            return Frame(lang = lang, priority = priority, text = String(textBytes, StandardCharsets.UTF_8))
        }
    }
}
