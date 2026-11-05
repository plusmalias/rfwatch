package com.rfwatch.core.id

/** Wi-Fi Protected Setup attributes a beacon / probe response volunteers (Wi-Fi Simple Config spec). */
data class WpsInfo(
    val manufacturer: String? = null,
    val modelName: String? = null,
    val modelNumber: String? = null,
    val deviceName: String? = null,
    /** Primary device type category ("Camera", "Printer", "Network infrastructure", …). */
    val category: String? = null,
    val categoryId: Int? = null,
)

data class WifiIeInfo(
    val wps: WpsInfo? = null,
    /** Vendors named by vendor-specific elements (chipset / platform: "Broadcom", "Apple", …). */
    val vendors: List<String> = emptyList(),
)

/**
 * Decodes the information elements Android exposes on a scan result (API 30+). Element 221 is
 * vendor-specific: OUI 00:50:F2 type 4 is WPS, whose TLVs (big-endian type, length) often carry
 * the manufacturer, model and a device name — routers, printers, cameras and TVs fill them in.
 * Other 221 OUIs identify the radio chipset or platform vendor.
 */
object WifiIes {

    private const val VENDOR_SPECIFIC = 221

    private val VENDOR_OUIS = mapOf(
        0x001018 to "Broadcom", 0x00904C to "Broadcom", 0x00037F to "Qualcomm Atheros", 0x8CFDF0 to "Qualcomm",
        0x000CE7 to "MediaTek", 0x000C43 to "Ralink / MediaTek", 0x00E04C to "Realtek", 0x0017F2 to "Apple",
        0x18FE34 to "Espressif", 0x00146C to "Netgear", 0x000B86 to "Aruba", 0x004096 to "Cisco",
        0x001392 to "Ruckus", 0x506F9A to "Wi-Fi Alliance", 0x001A11 to "Google", 0x0000F0 to "Samsung",
        0xF4F5D8 to "Google", 0x00A0C6 to "Qualcomm", 0x001374 to "Atheros",
    )

    private val CATEGORIES = mapOf(
        1 to "Computer", 2 to "Input device", 3 to "Printer / scanner", 4 to "Camera", 5 to "Storage",
        6 to "Network infrastructure", 7 to "Display", 8 to "Multimedia device", 9 to "Gaming device",
        10 to "Telephone", 11 to "Audio device", 12 to "Docking device",
    )

    fun parse(elements: List<Pair<Int, ByteArray>>): WifiIeInfo {
        var wps: WpsInfo? = null
        val vendors = LinkedHashSet<String>()
        for ((id, body) in elements) {
            if (id != VENDOR_SPECIFIC || body.size < 4) continue
            val oui = ((body[0].toInt() and 0xFF) shl 16) or ((body[1].toInt() and 0xFF) shl 8) or (body[2].toInt() and 0xFF)
            if (oui == 0x0050F2) {
                if ((body[3].toInt() and 0xFF) == 0x04) wps = parseWps(body, 4) ?: wps
                continue // other 00:50:F2 types are WMM/WPA — Microsoft-defined, not a vendor hint
            }
            VENDOR_OUIS[oui]?.let { vendors += it }
        }
        return WifiIeInfo(wps, vendors.toList())
    }

    private fun parseWps(b: ByteArray, start: Int): WpsInfo? {
        var info = WpsInfo()
        var any = false
        var i = start
        while (i + 4 <= b.size) {
            val type = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (i + 4 + len > b.size) break // truncated / malformed: keep what parsed cleanly
            val v = b.copyOfRange(i + 4, i + 4 + len)
            fun text() = v.decodeToString().trim { it <= ' ' }.takeIf { s -> s.isNotEmpty() && s.all { it.code >= 0x20 } }
            when (type) {
                0x1021 -> info = info.copy(manufacturer = text())
                0x1023 -> info = info.copy(modelName = text())
                0x1024 -> info = info.copy(modelNumber = text())
                0x1011 -> info = info.copy(deviceName = text())
                0x1054 -> if (len >= 2) {
                    val cat = ((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)
                    info = info.copy(categoryId = cat, category = CATEGORIES[cat])
                }
            }
            any = true
            i += 4 + len
        }
        return info.takeIf { any }
    }

    /** "NETGEAR R7000", "ACME CamPro CP-200": brand + model name + number, without repeats. */
    fun modelLabel(w: WpsInfo): String? {
        val brand = w.manufacturer?.let(VendorDb::shortName)
        val parts = ArrayList<String>()
        brand?.let { parts += it }
        w.modelName?.takeIf { m -> brand == null || !m.equals(brand, true) }?.let { m ->
            parts += if (brand != null && m.startsWith(brand, true)) m.substring(brand.length).trim() else m
        }
        w.modelNumber?.takeIf { n -> parts.none { it.contains(n, true) } }?.let { parts += it }
        return parts.filter { it.isNotBlank() }.joinToString(" ").takeIf { parts.size >= 2 || (parts.size == 1 && w.modelName != null) }
    }

    fun kindFor(w: WpsInfo): DeviceKind? = when (w.categoryId) {
        1, 5 -> DeviceKind.COMPUTER
        2 -> DeviceKind.INPUT
        3 -> DeviceKind.SMART_HOME
        4 -> DeviceKind.CAMERA_IOT
        7, 8, 9 -> DeviceKind.TV_MEDIA
        10 -> DeviceKind.PHONE
        11 -> DeviceKind.AUDIO
        else -> null
    }
}
