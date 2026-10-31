package com.rfwatch.core.cs

/**
 * Platform-neutral view of one BLE advertisement. The :app layer builds this from an
 * Android `ScanResult` (manufacturer-specific data, 16-bit service data and service
 * UUIDs); keeping it as plain maps lets the parser be unit-tested from hex fixtures.
 */
data class BleAdvertisement(
    /** Company identifier → manufacturer-specific bytes (company ID already stripped). */
    val manufacturerData: Map<Int, ByteArray>,
    /** 16-bit service UUID → service data bytes. */
    val serviceData16: Map<Int, ByteArray>,
    /** Advertised 16-bit service UUIDs. */
    val serviceUuids16: Set<Int>,
    val address: String,
    val rssi: Int,
    val timestampMs: Long,
)

enum class TrackerType {
    APPLE_FIND_MY,            // AirTag / Apple device in offline-finding mode
    APPLE_FIND_MY_ACCESSORY,  // 3rd-party "Works with Apple Find My" accessory
    SAMSUNG_SMARTTAG,
    TILE,
    CHIPOLO,
    GOOGLE_FIND_MY,           // Google Find My Device / "Find Hub" network
    DULT_GENERIC,             // DULT-conformant accessory we can't attribute to a vendor
}

enum class TrackerState { NEARBY, SEPARATED, UNKNOWN }

/** Coarse battery hint where a vendor exposes one (Apple encodes 2 bits). */
enum class BatteryHint { FULL, MEDIUM, LOW, CRITICAL, UNKNOWN }

data class TrackerMatch(
    val type: TrackerType,
    val state: TrackerState,
    val battery: BatteryHint,
    val registered: Boolean?,
    /** 0..1 how sure we are of the classification. */
    val confidence: Double,
    /** Human-readable note naming the identifying bytes, for the UI and the audit log. */
    val evidence: String,
)

/**
 * Classifies BLE advertisements from consumer item-trackers by their *payload*, never by
 * MAC address — every one of these families rotates its address specifically to defeat
 * MAC-based tracking, so a MAC allowlist would be useless.
 *
 * Identifier sources (verified against the Bluetooth SIG 16-bit UUID member registry and
 * vendor / reverse-engineering references on 2026-10-31):
 *  - Apple Find My offline-finding: manufacturer data under company ID 0x004C, Apple
 *    payload type 0x12; separated vs nearby is distinguished by the Apple sub-payload
 *    length (0x19 = 25 bytes ⇒ separated/lost broadcast). Battery is encoded in the two
 *    high bits of the status byte. Refs: Adam Catley AirTag reverse engineering
 *    (adamcatley.com/AirTag.html); Heinrich et al., "Who Can Find My Devices?" (USENIX);
 *    OpenHaystack / AirGuard. Apple does not publish Continuity officially — treat the
 *    battery bits as best-effort.
 *  - Apple Find My Network accessory service: 16-bit UUID 0xFD44 (SIG → Apple Inc.).
 *  - Samsung SmartTag: 16-bit service UUID 0xFD5A (registered) / 0xFD59 (unregistered)
 *    (SIG → Samsung Electronics). Ref: Yu et al., arXiv:2210.14702; Kismet PR #679.
 *  - Tile: 16-bit service UUIDs 0xFEED, 0xFEEC, 0xFD84 (SIG → Tile, Inc.).
 *  - Chipolo: 16-bit service UUIDs 0xFE33, 0xFE65 (SIG → CHIPOLO d.o.o.).
 *  - Google Find My Device / Find Hub (FMDN): service data under 16-bit UUID 0xFEAA with
 *    FMDN frame type 0x40 (normal) or 0x41 (unwanted-tracking-protection mode). Note the
 *    UUID collides with legacy Eddystone (frame types 0x00/0x10/0x20/0x30), so we require
 *    an FMDN frame byte to avoid false positives. Ref: Google "Find Hub Network Accessory
 *    Specification" (developers.google.com/nearby/fast-pair/specifications/extensions/fmdn).
 *  - DULT: the IETF Detecting Unwanted Location Trackers accessory protocol is the umbrella
 *    the above networks conform to; there is no single DULT 16-bit UUID, so a DULT-generic
 *    match means "looks like a conformant tracker we can't attribute". Ref:
 *    draft-ietf-dult-accessory-protocol.
 */
object TrackerParser {

    const val APPLE_COMPANY_ID = 0x004C
    const val APPLE_TYPE_FIND_MY = 0x12
    const val APPLE_FIND_MY_LEN_SEPARATED = 0x19
    const val APPLE_FINDMY_ACCESSORY_UUID = 0xFD44

    private val SAMSUNG_REGISTERED = 0xFD5A
    private val SAMSUNG_UNREGISTERED = 0xFD59
    private val TILE_UUIDS = setOf(0xFEED, 0xFEEC, 0xFD84)
    private val CHIPOLO_UUIDS = setOf(0xFE33, 0xFE65)
    private const val FMDN_UUID = 0xFEAA
    private const val FMDN_FRAME_NORMAL = 0x40
    private const val FMDN_FRAME_UTP = 0x41

    fun parse(adv: BleAdvertisement): TrackerMatch? {
        // Apple Find My via manufacturer data.
        adv.manufacturerData[APPLE_COMPANY_ID]?.let { data ->
            if (data.isNotEmpty() && (data[0].toInt() and 0xFF) == APPLE_TYPE_FIND_MY) {
                val subLen = if (data.size >= 2) data[1].toInt() and 0xFF else 0
                val status = if (data.size >= 3) data[2].toInt() and 0xFF else 0
                val separated = subLen == APPLE_FIND_MY_LEN_SEPARATED
                return TrackerMatch(
                    type = TrackerType.APPLE_FIND_MY,
                    state = if (separated) TrackerState.SEPARATED else TrackerState.NEARBY,
                    battery = appleBattery(status),
                    registered = null,
                    confidence = if (separated) 0.95 else 0.8,
                    evidence = "Apple 0x004C type 0x12 len=0x${subLen.toString(16)} status=0x${status.toString(16)}",
                )
            }
        }

        // Service-UUID based families.
        when {
            APPLE_FINDMY_ACCESSORY_UUID in adv.serviceUuids16 ||
                APPLE_FINDMY_ACCESSORY_UUID in adv.serviceData16 ->
                return match(TrackerType.APPLE_FIND_MY_ACCESSORY, TrackerState.SEPARATED, 0.9,
                    "service UUID 0xFD44 (Apple Find My Network accessory)")

            SAMSUNG_REGISTERED in adv.serviceData16 || SAMSUNG_REGISTERED in adv.serviceUuids16 ->
                return match(TrackerType.SAMSUNG_SMARTTAG, TrackerState.SEPARATED, 0.9,
                    "service UUID 0xFD5A (Samsung SmartTag, registered)", registered = true)

            SAMSUNG_UNREGISTERED in adv.serviceData16 || SAMSUNG_UNREGISTERED in adv.serviceUuids16 ->
                return match(TrackerType.SAMSUNG_SMARTTAG, TrackerState.UNKNOWN, 0.85,
                    "service UUID 0xFD59 (Samsung SmartTag, unregistered)", registered = false)

            adv.serviceUuids16.any { it in TILE_UUIDS } || adv.serviceData16.keys.any { it in TILE_UUIDS } ->
                return match(TrackerType.TILE, TrackerState.UNKNOWN, 0.9, "Tile service UUID")

            adv.serviceUuids16.any { it in CHIPOLO_UUIDS } || adv.serviceData16.keys.any { it in CHIPOLO_UUIDS } ->
                return match(TrackerType.CHIPOLO, TrackerState.UNKNOWN, 0.9, "Chipolo service UUID")
        }

        // Google FMDN / Find Hub via service data; require an FMDN frame byte.
        adv.serviceData16[FMDN_UUID]?.let { sd ->
            if (sd.isNotEmpty()) {
                val frame = sd[0].toInt() and 0xFF
                if (frame == FMDN_FRAME_NORMAL || frame == FMDN_FRAME_UTP) {
                    return TrackerMatch(
                        type = TrackerType.GOOGLE_FIND_MY,
                        state = if (frame == FMDN_FRAME_UTP) TrackerState.SEPARATED else TrackerState.UNKNOWN,
                        battery = BatteryHint.UNKNOWN,
                        registered = null,
                        confidence = 0.85,
                        evidence = "FMDN 0xFEAA frame=0x${frame.toString(16)}" +
                            if (frame == FMDN_FRAME_UTP) " (unwanted-tracking-protection)" else "",
                    )
                }
            }
        }
        return null
    }

    /**
     * Apple status byte: the two most-significant bits encode a coarse battery level in
     * reverse-engineered implementations (OpenHaystack / AirGuard). Not Apple-documented.
     */
    private fun appleBattery(status: Int): BatteryHint = when ((status ushr 6) and 0x03) {
        0 -> BatteryHint.FULL
        1 -> BatteryHint.MEDIUM
        2 -> BatteryHint.LOW
        else -> BatteryHint.CRITICAL
    }

    private fun match(
        type: TrackerType, state: TrackerState, confidence: Double,
        evidence: String, registered: Boolean? = null,
    ) = TrackerMatch(type, state, BatteryHint.UNKNOWN, registered, confidence, evidence)
}
