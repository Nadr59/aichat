package com.example.aichat.repository.agents

import android.util.Base64

object PngSillyTavernExtractor {

    private val PNG_SIG = byteArrayOf(
        137.toByte(), 80, 78, 71, 13, 10, 26, 10
    )

    fun extractCharaJson(pngBytes: ByteArray): String? {
        if (pngBytes.size < 8) return null
        if (!pngBytes.copyOfRange(0, 8).contentEquals(PNG_SIG)) return null

        var i = 8
        while (i + 12 <= pngBytes.size) {
            val length = readIntBE(pngBytes, i)
            val type = String(pngBytes, i + 4, 4, Charsets.ISO_8859_1)
            val dataStart = i + 8
            val dataEnd = dataStart + length
            if (dataEnd + 4 > pngBytes.size) break

            if (type == "tEXt") {
                val chunk = pngBytes.copyOfRange(dataStart, dataEnd)
                val zero = chunk.indexOf(0)
                if (zero > 0) {
                    val key = String(chunk, 0, zero, Charsets.ISO_8859_1)
                    val value = String(chunk, zero + 1, chunk.size - (zero + 1), Charsets.ISO_8859_1)

                    // الأكثر شيوعاً في ST
                    if (key.equals("chara", ignoreCase = true)) {
                        val decoded = runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull()
                        if (decoded != null) return String(decoded, Charsets.UTF_8)
                    }
                }
            }

            i = dataEnd + 4 // CRC
            if (type == "IEND") break
        }

        return null
    }

    private fun readIntBE(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)
    }
}
