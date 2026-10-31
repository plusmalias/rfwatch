package com.rfwatch.core.id

/**
 * Offline vendor lookup from two bundled registries (resources under `/vendors`):
 *  - `company_ids.txt` — Bluetooth SIG assigned company identifiers (manufacturer-specific
 *    data), one `HHHHName` line each (4 hex digits, then the name);
 *  - `oui.txt` — IEEE MA-L OUI assignments, one `HHHHHHName` line each.
 *
 * Both are loaded lazily on first use into sorted primitive arrays (≈40k OUIs) rather than
 * boxed maps, to stay light on budget phones. Lookups are thread-safe after loading.
 */
object VendorDb {

    private class Table(val keys: IntArray, val names: Array<String>) {
        fun get(key: Int): String? {
            val i = keys.binarySearch(key)
            return if (i >= 0) names[i] else null
        }
    }

    private val companies by lazy { load("/vendors/company_ids.txt", 4) }
    private val ouis by lazy { load("/vendors/oui.txt", 6) }

    /** Registered Bluetooth SIG company name for a manufacturer-data company ID, shortened. */
    fun company(companyId: Int): String? = companies.get(companyId)?.let(::shortName)

    /**
     * Vendor for a MAC/BSSID from its OUI, shortened. Returns null for locally administered
     * addresses (bit 1 of the first octet): randomised/private addresses carry no vendor.
     */
    fun ouiVendor(mac: String): String? {
        val hex = mac.filter { it.isLetterOrDigit() }
        if (hex.length < 6) return null
        val oui = hex.substring(0, 6).toIntOrNull(16) ?: return null
        if (isLocallyAdministered(oui ushr 16)) return null
        return ouis.get(oui)?.let(::shortName)
    }

    fun isLocallyAdministered(firstOctet: Int): Boolean = (firstOctet and 0x02) != 0

    private val SUFFIXES = listOf(
        "co., ltd.", "co.,ltd.", "co.,ltd", "co. ltd.", "co. ltd", "co ltd", "co., ltd", "ltd.", "ltd",
        "inc.", "inc", "incorporated", "corporation", "corp.", "corp", "llc", "l.l.c.", "gmbh", "ag",
        "s.a.", "sa", "b.v.", "bv", "ab", "oy", "a/s", "as", "limited", "plc", "pty", "pty.", "s.r.l.",
        "electronics", "technologies", "technology", "international", "communications", "co.", "co",
    )

    /** "Samsung Electronics Co. Ltd." → "Samsung", "Apple, Inc." → "Apple". */
    fun shortName(raw: String): String {
        var s = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            val trimmed = s.trimEnd(',', ' ', '.')
            for (suffix in SUFFIXES) {
                if (trimmed.length > suffix.length + 1 &&
                    trimmed.endsWith(suffix, ignoreCase = true) &&
                    trimmed[trimmed.length - suffix.length - 1].let { it == ' ' || it == ',' }
                ) {
                    s = trimmed.dropLast(suffix.length)
                    changed = true
                    break
                }
            }
            if (!changed) s = trimmed
        }
        return s.ifBlank { raw.trim() }
    }

    private fun load(resource: String, keyLength: Int): Table {
        val keys = ArrayList<Int>()
        val names = ArrayList<String>()
        val interned = HashMap<String, String>()
        VendorDb::class.java.getResourceAsStream(resource)?.bufferedReader()?.useLines { lines ->
            lines.forEach { line ->
                if (line.length <= keyLength) return@forEach
                val key = line.substring(0, keyLength).toIntOrNull(16) ?: return@forEach
                val name = line.substring(keyLength).trim()
                if (name.isEmpty()) return@forEach
                keys += key
                names += interned.getOrPut(name) { name }
            }
        }
        val order = keys.indices.sortedBy { keys[it] }
        return Table(
            IntArray(order.size) { keys[order[it]] },
            Array(order.size) { names[order[it]] },
        )
    }
}
