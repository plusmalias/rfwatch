package com.rfwatch.core.cs

/**
 * Parses a raw BLE advertising-data byte stream (a sequence of
 * length/type/value AD structures, per the Bluetooth Core Spec, Supplement Part A) into
 * the neutral [BleAdvertisement]. Android exposes both pre-parsed accessors and the raw
 * `scanRecord.bytes`; parsing the raw bytes here keeps the logic testable from fixtures
 * and independent of Android's parser quirks.
 *
 * Multi-byte identifiers in AD data are little-endian.
 */
object AdvParser {

    // AD types we care about (Core Spec Supplement, Part A, §1).
    private const val AD_INCOMPLETE_16 = 0x02
    private const val AD_COMPLETE_16 = 0x03
    private const val AD_SERVICE_SOLICITATION_16 = 0x14
    private const val AD_SERVICE_DATA_16 = 0x16
    private const val AD_MANUFACTURER = 0xFF

    fun fromRawAdvBytes(bytes: ByteArray, address: String, rssi: Int, timestampMs: Long): BleAdvertisement {
        val manufacturer = HashMap<Int, ByteArray>()
        val serviceData = HashMap<Int, ByteArray>()
        val serviceUuids = HashSet<Int>()

        var i = 0
        while (i < bytes.size) {
            val len = bytes[i].toInt() and 0xFF
            if (len == 0) break
            if (i + 1 >= bytes.size) break // need at least a type byte
            val type = bytes[i + 1].toInt() and 0xFF
            val dataStart = i + 2
            val dataEnd = (i + 1 + len).coerceAtMost(bytes.size)
            if (dataStart > dataEnd) break
            val data = bytes.copyOfRange(dataStart, dataEnd)
            when (type) {
                AD_MANUFACTURER -> if (data.size >= 2) {
                    val company = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    manufacturer[company] = data.copyOfRange(2, data.size)
                }
                AD_SERVICE_DATA_16 -> if (data.size >= 2) {
                    val uuid = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    serviceData[uuid] = data.copyOfRange(2, data.size)
                    serviceUuids.add(uuid)
                }
                AD_COMPLETE_16, AD_INCOMPLETE_16, AD_SERVICE_SOLICITATION_16 -> {
                    var j = 0
                    while (j + 1 < data.size) {
                        serviceUuids.add((data[j].toInt() and 0xFF) or ((data[j + 1].toInt() and 0xFF) shl 8))
                        j += 2
                    }
                }
            }
            i += len + 1
        }
        return BleAdvertisement(manufacturer, serviceData, serviceUuids, address, rssi, timestampMs)
    }

    /** Parse a hex string ("1cff4c00…", whitespace allowed) into bytes. */
    fun hex(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }
}
