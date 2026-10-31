package com.rfwatch.core.id

/**
 * What a drone's broadcast Remote ID says about it. Every field is optional: drones send the
 * message types in rotation, so a full picture builds up over several adverts ([merge]).
 */
data class RemoteIdInfo(
    val serial: String? = null,
    val idType: Int? = null,
    val uaType: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitudeM: Double? = null,
    val heightAglM: Double? = null,
    val speedMps: Double? = null,
    val headingDeg: Double? = null,
    val operatorLat: Double? = null,
    val operatorLon: Double? = null,
    val operatorId: String? = null,
    val description: String? = null,
) {
    fun merge(newer: RemoteIdInfo) = RemoteIdInfo(
        serial = newer.serial ?: serial, idType = newer.idType ?: idType, uaType = newer.uaType ?: uaType,
        lat = newer.lat ?: lat, lon = newer.lon ?: lon, altitudeM = newer.altitudeM ?: altitudeM,
        heightAglM = newer.heightAglM ?: heightAglM, speedMps = newer.speedMps ?: speedMps,
        headingDeg = newer.headingDeg ?: headingDeg, operatorLat = newer.operatorLat ?: operatorLat,
        operatorLon = newer.operatorLon ?: operatorLon, operatorId = newer.operatorId ?: operatorId,
        description = newer.description ?: description,
    )
}

/**
 * Decoder for ASTM F3411 / ASD-STAN prEN 4709-002 "Open Drone ID" broadcast Remote ID.
 *
 * Transports handled:
 *  - Bluetooth legacy advertising: 16-bit service data UUID 0xFFFA (ASTM International);
 *    payload = app code 0x0D, message counter, then one 25-byte message (or a message pack).
 *  - Wi-Fi beacon vendor-specific element: OUI FA:0B:BC, OUI type 0x0D, counter, message pack.
 *
 * Message layout (25 bytes, little-endian): byte 0 = message type (high nibble) / protocol
 * version (low nibble). Types: 0 Basic ID, 1 Location/Vector, 3 Self-ID, 4 System, 5 Operator ID,
 * 0xF message pack. Field encodings follow the opendroneid-core-c reference implementation.
 */
object RemoteIdParser {

    const val BLE_SERVICE_UUID = 0xFFFA
    private const val APP_CODE = 0x0D
    private const val MSG_SIZE = 25
    val WIFI_OUI = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte())
    /** DJI's proprietary DroneID vendor element (presence only; not decoded). */
    val DJI_OUI = byteArrayOf(0x26, 0x37, 0x12)

    private val UA_TYPES = listOf(
        "Unspecified", "Aeroplane", "Multirotor / helicopter", "Gyroplane", "Hybrid VTOL", "Ornithopter",
        "Glider", "Kite", "Free balloon", "Captive balloon", "Airship", "Parachute", "Rocket",
        "Tethered aircraft", "Ground obstacle", "Other",
    )

    /** Parse BLE service data under UUID 0xFFFA. */
    fun parseBleServiceData(data: ByteArray): RemoteIdInfo? {
        if (data.size < 2 + MSG_SIZE || (data[0].toInt() and 0xFF) != APP_CODE) return null
        return parseMessage(data, 2)
    }

    /** Parse a Wi-Fi vendor-specific element body (starting with the OUI). */
    fun parseWifiVendorElement(body: ByteArray): RemoteIdInfo? {
        if (body.size < 5 + 3 || !body.copyOfRange(0, 3).contentEquals(WIFI_OUI) || (body[3].toInt() and 0xFF) != APP_CODE) return null
        return parseMessage(body, 5)
    }

    /** Parse one message (or a pack) starting at [offset]. */
    fun parseMessage(b: ByteArray, offset: Int): RemoteIdInfo? {
        if (b.size - offset < 2) return null
        return when ((b[offset].toInt() and 0xF0) ushr 4) {
            0xF -> {
                val size = b[offset + 1].toInt() and 0xFF
                val count = b.getOrNull(offset + 2)?.toInt()?.and(0xFF) ?: return null
                if (size != MSG_SIZE) return null
                var info: RemoteIdInfo? = null
                for (i in 0 until count) {
                    val start = offset + 3 + i * MSG_SIZE
                    if (start + MSG_SIZE > b.size) break
                    single(b, start)?.let { info = info?.merge(it) ?: it }
                }
                info
            }
            else -> if (b.size - offset >= MSG_SIZE) single(b, offset) else null
        }
    }

    private fun single(b: ByteArray, o: Int): RemoteIdInfo? = when ((b[o].toInt() and 0xF0) ushr 4) {
        0 -> {
            val idType = (b[o + 1].toInt() and 0xF0) ushr 4
            val ua = b[o + 1].toInt() and 0x0F
            RemoteIdInfo(serial = ascii(b, o + 2, 20), idType = idType, uaType = UA_TYPES.getOrNull(ua))
        }
        1 -> {
            val flags = b[o + 1].toInt() and 0xFF
            val ewDirection = (flags and 0x02) != 0
            val speedMult = (flags and 0x01) != 0
            val dir = (b[o + 2].toInt() and 0xFF) + if (ewDirection) 180 else 0
            val rawSpeed = b[o + 3].toInt() and 0xFF
            val speed = if (rawSpeed == 255) null else if (speedMult) rawSpeed * 0.75 + 255 * 0.25 else rawSpeed * 0.25
            val lat = int32(b, o + 5) * 1e-7
            val lon = int32(b, o + 9) * 1e-7
            val valid = !(lat == 0.0 && lon == 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0
            RemoteIdInfo(
                lat = if (valid) lat else null, lon = if (valid) lon else null,
                altitudeM = altitude(b, o + 15), heightAglM = altitude(b, o + 17),
                speedMps = speed, headingDeg = if (dir in 0..359) dir.toDouble() else null,
            )
        }
        3 -> RemoteIdInfo(description = ascii(b, o + 2, 23))
        4 -> {
            val lat = int32(b, o + 2) * 1e-7
            val lon = int32(b, o + 6) * 1e-7
            val valid = !(lat == 0.0 && lon == 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0
            RemoteIdInfo(operatorLat = if (valid) lat else null, operatorLon = if (valid) lon else null)
        }
        5 -> RemoteIdInfo(operatorId = ascii(b, o + 2, 20))
        else -> null
    }

    private fun int32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or (b[i + 3].toInt() shl 24)

    /** Altitude encoding: uint16 × 0.5 − 1000 m; 0 means unknown. */
    private fun altitude(b: ByteArray, i: Int): Double? {
        val raw = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        return if (raw == 0) null else raw * 0.5 - 1000.0
    }

    private fun ascii(b: ByteArray, i: Int, len: Int): String? =
        String(b.copyOfRange(i, minOf(i + len, b.size)), Charsets.US_ASCII).trim('\u0000', ' ').ifBlank { null }
}
