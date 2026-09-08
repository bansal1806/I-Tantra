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
 * a spoken sentence, vs. kilobytes for even a well-compressed voice note. `priority` is
 * reserved as 0 (normal) for now; M3 uses it for the alert side-channel.
 */
data class Frame(
    val lang: String,
    val priority: Int = PRIORITY_NORMAL,
    val text: String,
) {
    /** Total bytes this frame takes on the wire, including the header -- the number the
     *  bitrate showcase (M3) will put next to an equivalent voice-note size. */
    val byteSize: Int
        get() = HEADER_SIZE + text.toByteArray(StandardCharsets.UTF_8).size

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
