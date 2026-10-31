package com.rfwatch.app.repo

import com.rfwatch.core.cs.TrackerMatch
import com.rfwatch.core.cs.TrackerType
import com.rfwatch.core.id.DeviceKind
import com.rfwatch.core.id.Fingerprint
import com.rfwatch.core.model.Band
import com.rfwatch.core.rules.DeviceFacts
import com.rfwatch.core.rules.RuleCategory
import com.rfwatch.core.rules.RuleHit

/** Conservative labels from identifiers visible in radio advertisements. */
internal data class DeviceIdentity(
    val brand: String?,
    val category: String,
    val kind: DeviceKind = DeviceKind.UNKNOWN,
    val model: String? = null,
)

internal object DeviceIdentityClassifier {
    /**
     * Rule hits (law enforcement, trackers, cameras, fleet) take precedence; otherwise the
     * payload [fingerprint] from :core decides the kind. Brand prefers tracker/payload evidence
     * over the registered company / OUI.
     */
    fun classify(
        band: Band, facts: DeviceFacts, match: TrackerMatch?, hits: List<RuleHit>,
        fingerprint: Fingerprint? = null,
    ): DeviceIdentity {
        val taserHit = hits.any { it.ruleId == "taser" }
        val axonHit = taserHit || hits.any { it.ruleId == "axon" }
        val lawHit = hits.firstOrNull { it.category == RuleCategory.LAW_ENFORCEMENT }
        val brand = if (axonHit) "Axon" else match?.type?.brand() ?: fingerprint?.brand ?: serviceBrand(facts) ?: advertiserBrand(facts)
        val fpKind = fingerprint?.kind?.takeIf { it != DeviceKind.UNKNOWN }
        val kind = when {
            axonHit || lawHit != null -> DeviceKind.LAW_ENFORCEMENT
            match != null -> DeviceKind.TRACKER
            hits.any { it.category == RuleCategory.CAMERA_IOT } -> DeviceKind.CAMERA_IOT
            hits.any { it.category == RuleCategory.FLEET } -> DeviceKind.FLEET
            fpKind != null -> fpKind
            band == Band.WIFI -> DeviceKind.NETWORK
            else -> DeviceKind.UNKNOWN
        }
        val category = when {
            taserHit -> "Possible TASER signature"
            axonHit -> "Possible Axon device signature"
            match != null -> "Bluetooth item tracker"
            hits.any { it.category == RuleCategory.CAMERA_IOT } -> "Camera / IoT candidate"
            hits.any { it.category == RuleCategory.FLEET } -> "Fleet / vehicle signature"
            lawHit != null -> "Law-enforcement signature · ${lawHit.label}"
            fpKind != null && fpKind != DeviceKind.NETWORK -> fpKind.label
            band == Band.WIFI -> "Wi-Fi access point / device"
            band == Band.BLE -> "Bluetooth device"
            else -> "Radio device"
        }
        return DeviceIdentity(brand, category, kind, fingerprint?.model)
    }

    private fun TrackerType.brand(): String? = when (this) {
        TrackerType.APPLE_FIND_MY, TrackerType.APPLE_FIND_MY_ACCESSORY -> "Apple"
        TrackerType.SAMSUNG_SMARTTAG -> "Samsung"
        TrackerType.TILE -> "Tile"
        TrackerType.CHIPOLO -> "Chipolo"
        TrackerType.GOOGLE_FIND_MY -> "Google"
        TrackerType.DULT_GENERIC -> null
    }

    private fun serviceBrand(facts: DeviceFacts): String? = when {
        0xFD44 in facts.serviceUuids -> "Apple"
        0xFD59 in facts.serviceUuids || 0xFD5A in facts.serviceUuids -> "Samsung"
        setOf(0xFEED, 0xFEEC, 0xFD84).any { it in facts.serviceUuids } -> "Tile"
        setOf(0xFE33, 0xFE65).any { it in facts.serviceUuids } -> "Chipolo"
        0xFEAA in facts.serviceUuids -> "Google"
        else -> null
    }

    /** Manufacturer IDs follow Bluetooth SIG assigned numbers; names are advertiser-provided hints. */
    private fun advertiserBrand(facts: DeviceFacts): String? {
        val companyBrand = facts.companyIds.firstNotNullOfOrNull { COMPANY_BRANDS[it] }
        if (companyBrand != null) return companyBrand
        val label = facts.name ?: facts.ssid ?: return null
        return NAME_BRANDS.firstOrNull { (token, _) -> label.contains(token, ignoreCase = true) }?.second
    }

    private val COMPANY_BRANDS = mapOf(
        0x004C to "Apple",   // Apple Inc.
        0x0075 to "Samsung", // Samsung Electronics
        0x00E0 to "Google",   // Google LLC
    )

    // Name/SSID-based labels are intentionally broad: SSIDs and BLE names can be spoofed.
    private val NAME_BRANDS = listOf(
        "apple" to "Apple", "iphone" to "Apple", "airtag" to "Apple",
        "samsung" to "Samsung", "smarttag" to "Samsung",
        "google" to "Google", "pixel" to "Google", "nest" to "Google",
        "tile" to "Tile", "chipolo" to "Chipolo",
        "amazon" to "Amazon", "ring" to "Ring", "eufy" to "Eufy", "arlo" to "Arlo",
        "tp-link" to "TP-Link", "tplink" to "TP-Link", "xiaomi" to "Xiaomi",
        "huawei" to "Huawei", "ubiquiti" to "Ubiquiti", "unifi" to "Ubiquiti",
    )
}
