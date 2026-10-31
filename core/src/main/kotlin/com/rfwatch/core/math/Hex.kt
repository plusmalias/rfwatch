package com.rfwatch.core.math

/** Upper-case hex without per-byte String.format (called for every BLE advert). */
object Hex {
    private val DIGITS = "0123456789ABCDEF".toCharArray()

    fun upper(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(out)
    }
}
