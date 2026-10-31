package com.rfwatch.core.id

/** Security summary parsed from Android's `ScanResult.capabilities` string. */
enum class WifiSecurity(val label: String) {
    OPEN("Open"), OWE("Enhanced Open (OWE)"), WEP("WEP (insecure)"), WPA("WPA"), WPA2("WPA2"),
    WPA3("WPA3"), WPA2_WPA3("WPA2/WPA3"), ENTERPRISE("Enterprise (802.1X)"), UNKNOWN("Unknown"),
}

/**
 * Infers a [DeviceKind] for a Wi-Fi BSSID from its SSID and address, and summarises its
 * security and band. Without a payload to decode, Wi-Fi identification rests on the SSID
 * (user-settable) and the OUI — so most APs are simply [DeviceKind.NETWORK].
 */
object WifiFingerprinter {

    fun fingerprint(ssid: String?, bssid: String): Fingerprint {
        val fp = classify(ssid, bssid)
        return if (fp.brand != null) fp else fp.copy(brand = VendorDb.ouiVendor(bssid))
    }

    private fun classify(ssid: String?, bssid: String): Fingerprint {
        val firstOctet = bssid.substringBefore(':').toIntOrNull(16)
        val locallyAdministered = firstOctet?.let(VendorDb::isLocallyAdministered) ?: false
        if (ssid != null) {
            if (ssid.startsWith("DIRECT-", ignoreCase = true)) {
                val hint = NameHints.wifi(ssid.removeRange(0, 7), locallyAdministered)
                return Fingerprint(
                    hint?.kind?.takeIf { it != DeviceKind.HOTSPOT } ?: DeviceKind.SMART_HOME,
                    "Wi-Fi Direct device", "Wi-Fi Direct SSID" + (hint?.let { " · ${it.evidence}" } ?: ""),
                )
            }
            NameHints.wifi(ssid, locallyAdministered)?.let { return it }
        }
        return Fingerprint(
            DeviceKind.NETWORK,
            evidence = if (ssid == null) "hidden SSID" else "no device-specific SSID/vendor hint",
        )
    }

    fun security(capabilities: String): WifiSecurity {
        val c = capabilities.uppercase()
        val sae = "SAE" in c
        val psk = "PSK" in c
        return when {
            "EAP" in c -> WifiSecurity.ENTERPRISE
            sae && psk -> WifiSecurity.WPA2_WPA3
            sae -> WifiSecurity.WPA3
            "OWE" in c -> WifiSecurity.OWE
            ("RSN" in c || "WPA2" in c) && psk -> WifiSecurity.WPA2
            "WPA" in c && psk -> WifiSecurity.WPA
            "WEP" in c -> WifiSecurity.WEP
            c.isBlank() || c.replace("[ESS]", "").replace("[WPS]", "").replace("[BSS]", "").isBlank() -> WifiSecurity.OPEN
            else -> WifiSecurity.UNKNOWN
        }
    }

    fun band(frequencyMhz: Int): String? = when (frequencyMhz) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5900 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> null
    }
}
