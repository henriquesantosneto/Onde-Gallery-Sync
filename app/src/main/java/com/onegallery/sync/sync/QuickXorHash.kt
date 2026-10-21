package com.onegallery.sync.sync

import java.io.InputStream
import kotlin.math.min

/**
 * Hash proprietário do OneDrive (Graph `file.hashes.quickXorHash`).
 * Portado de github.com/rclone/rclone/backend/onedrive/quickxorhash (BSD Zero Clause).
 */
internal object QuickXorHash {
    private const val DIGEST_SIZE = 20
    private const val DATA_SIZE = 11 * (8 * DIGEST_SIZE) // shift * widthInBits

    fun digest(input: InputStream): ByteArray {
        val hasher = Hasher()
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            hasher.update(buffer, read)
        }
        return hasher.finish()
    }

    private class Hasher {
        private val data = ByteArray(DATA_SIZE)
        private var size: Long = 0

        fun update(p: ByteArray, len: Int) {
            var i = 0
            val lastRemain = (size % DATA_SIZE).toInt()
            if (lastRemain != 0) {
                i += xorBytesInto(data, lastRemain, p, 0, len)
            }
            if (i != len) {
                while (len - i >= DATA_SIZE) {
                    i += xorBytesInto(data, 0, p, i, len)
                }
                xorBytesInto(data, 0, p, i, len)
            }
            size += len.toLong()
        }

        private fun xorBytesInto(
            data: ByteArray,
            dataOffset: Int,
            p: ByteArray,
            pStart: Int,
            pLen: Int,
        ): Int {
            val available = min(data.size - dataOffset, pLen - pStart)
            if (available <= 0) return 0
            for (j in 0 until available) {
                data[dataOffset + j] =
                    (data[dataOffset + j].toInt() xor p[pStart + j].toInt()).toByte()
            }
            return available
        }

        fun finish(): ByteArray {
            val h = ByteArray(DIGEST_SIZE + 1)
            for (i in data.indices) {
                val shift = (i * 11) % 160
                val shiftBytes = shift / 8
                val shiftBits = shift % 8
                val shifted = (data[i].toInt() and 0xFF) shl shiftBits
                h[shiftBytes] = (h[shiftBytes].toInt() xor (shifted and 0xFF)).toByte()
                h[shiftBytes + 1] = (h[shiftBytes + 1].toInt() xor ((shifted shr 8) and 0xFF)).toByte()
            }
            h[0] = (h[0].toInt() xor h[DIGEST_SIZE].toInt()).toByte()
            var d = size
            for (byteIndex in 0 until 8) {
                h[DIGEST_SIZE - 8 + byteIndex] =
                    (h[DIGEST_SIZE - 8 + byteIndex].toInt() xor ((d shr (8 * byteIndex)) and 0xFF).toInt())
                        .toByte()
            }
            return h.copyOf(DIGEST_SIZE)
        }
    }
}
