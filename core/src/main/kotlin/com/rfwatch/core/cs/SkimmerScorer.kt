package com.rfwatch.core.cs

import com.rfwatch.core.id.VendorDb

enum class SkimmerLevel { HIGH, MEDIUM, LOW }

data class SkimmerAssessment(
    val address: String,
    val name: String?,
    val score: Int,
    val level: SkimmerLevel,
    /** Each line is a signed point contribution with the reason, e.g. "+45 name 'HC-05' …". */
    val reasons: List<String>,
    val vendor: String?,
)

/**
 * Scores a Classic-Bluetooth device found during an on-demand check at a fuel pump / ATM for
 * how likely it is to be a card skimmer's Bluetooth module.
 *
 * Basis: the Bluetana study (Bhaskar et al., USENIX Security 2019) of skimmers recovered by
 * U.S. inspectors found they overwhelmingly use cheap Classic-Bluetooth serial modules (HC-05 /
 * HC-06 family, RN-42) left with factory-default names, an uncategorised device class, and
 * address blocks typical of those module factories — and that *name alone* gives false positives
 * (hobby electronics, OBD dongles). So several independent signals are combined and every point is
 * explained. HIGH means "inspect this pump / ATM and tell staff"; it is not proof.
 */
object SkimmerScorer {

    private val MODULE_NAMES = listOf(
        Regex("^hc-0[3-6]\\b", RegexOption.IGNORE_CASE) to "factory default of an HC-03/04/05/06 serial module",
        Regex("^linvor$", RegexOption.IGNORE_CASE) to "factory default of early HC-06 modules",
        Regex("^bt0[45](-a)?$", RegexOption.IGNORE_CASE) to "factory default of BT04/BT05 serial modules",
        Regex("^jdy-3[0-4]", RegexOption.IGNORE_CASE) to "factory default of JDY-30/31 serial modules",
        Regex("^rnbt-[0-9a-f]{4}$", RegexOption.IGNORE_CASE) to "factory default of a Roving Networks RN-41/42 module",
        Regex("^(spp-ca|cc41-a|bolutek|zs-040|at-09)$", RegexOption.IGNORE_CASE) to "factory default of a generic serial (SPP) module",
    )
    private val GENERIC_SERIAL = Regex("(serial|spp|uart|bt-?module)", RegexOption.IGNORE_CASE)
    private val BENIGN_NAMES = Regex("(obd|elm327|vlink|vgate|car|printer|pos|scale|thermal|speaker|buds|watch|phone|tv)", RegexOption.IGNORE_CASE)

    private val MODULE_MAKER_OUIS = mapOf("98d331" to "Shenzhen Bolutek (HC-05/06 module maker)", "000666" to "Roving Networks (RN-41/42 module maker)")
    private val CLONE_PREFIXES = listOf(Regex("^98d33[2-9]"), Regex("^001403"), Regex("^002113"), Regex("^201[3-7][0-9a-f]{2}"))

    /** Major device classes from the Bluetooth Class of Device (bits 8–12). */
    private const val MAJOR_UNCATEGORIZED = 0x1F
    private const val MAJOR_MISC = 0x00
    private val EXCULPATORY_MAJORS = mapOf(0x01 to "computer", 0x02 to "phone", 0x04 to "audio/video", 0x05 to "keyboard/mouse", 0x07 to "wearable")

    /**
     * @param deviceClass the raw Class-of-Device value (null if unknown).
     * @param classicOnly true when the device was found by Classic discovery and is not a BLE device.
     */
    fun score(address: String, name: String?, deviceClass: Int?, rssi: Int, classicOnly: Boolean): SkimmerAssessment {
        val reasons = ArrayList<String>()
        var score = 0
        fun add(points: Int, why: String) { score += points; reasons += (if (points >= 0) "+$points " else "$points ") + why }

        val trimmed = name?.trim().orEmpty()
        val moduleName = MODULE_NAMES.firstOrNull { it.first.containsMatchIn(trimmed) }
        when {
            moduleName != null -> add(45, "name \"$trimmed\" is the ${moduleName.second}, as found in documented skimmers")
            trimmed.isEmpty() -> add(10, "no advertised name (many skimmers hide or blank it)")
            GENERIC_SERIAL.containsMatchIn(trimmed) -> add(25, "name \"$trimmed\" suggests a generic serial Bluetooth module")
        }
        if (trimmed.isNotEmpty() && BENIGN_NAMES.containsMatchIn(trimmed) && moduleName == null) {
            add(-30, "name \"$trimmed\" looks like an ordinary product (OBD dongle, printer, audio, phone…)")
        }

        if (deviceClass != null) {
            val major = (deviceClass shr 8) and 0x1F
            when {
                major == MAJOR_UNCATEGORIZED || (major == MAJOR_MISC && deviceClass and 0x1FFC == 0) ->
                    add(20, "device class is uncategorised (0x%06X) — typical of bare serial modules".format(deviceClass))
                major in EXCULPATORY_MAJORS -> add(-40, "device class says it is a ${EXCULPATORY_MAJORS[major]} (0x%06X)".format(deviceClass))
            }
        }

        val oui = address.filter { it.isLetterOrDigit() }.lowercase().take(6)
        val vendor = VendorDb.ouiVendor(address)
        MODULE_MAKER_OUIS[oui]?.let { add(20, "address prefix ${address.take(8)} is registered to $it") }
            ?: if (CLONE_PREFIXES.any { it.containsMatchIn(oui) }) {
                add(15, "address prefix ${address.take(8)} is a block commonly used by HC-05/06 clone modules (not registered to the module maker)")
            } else Unit
        if (vendor != null && oui !in MODULE_MAKER_OUIS) reasons += "±0 address registered to $vendor"

        if (classicOnly) add(5, "Classic-only Bluetooth (serial modules don't use BLE)")
        if (rssi >= -60) add(10, "very strong signal ($rssi dBm) — within a metre or two of you")
        else if (rssi >= -72) add(5, "strong signal ($rssi dBm) — close by")

        val level = when {
            score >= 70 -> SkimmerLevel.HIGH
            score >= 45 -> SkimmerLevel.MEDIUM
            else -> SkimmerLevel.LOW
        }
        return SkimmerAssessment(address, name, score.coerceIn(0, 100), level, reasons, vendor)
    }
}
