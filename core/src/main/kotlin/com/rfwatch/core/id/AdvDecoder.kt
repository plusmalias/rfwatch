package com.rfwatch.core.id

import com.rfwatch.core.math.Hex

/**
 * Turns a raw advertising payload into one labelled line per AD structure: what the bytes
 * are (Bluetooth Core Spec Supplement Part A types), who registered the company / service ID
 * (Bluetooth SIG assigned numbers), and what the vendor frame says when it is a known one.
 * This is the "read the hex" step done for the user; it decodes, it does not judge.
 */
object AdvDecoder {

    data class AdLine(val hex: String, val text: String)

    fun describe(bytes: ByteArray): List<AdLine> {
        val out = ArrayList<AdLine>()
        var i = 0
        while (i < bytes.size) {
            val len = bytes[i].toInt() and 0xFF
            if (len == 0 || i + 1 >= bytes.size) break
            val end = (i + 1 + len).coerceAtMost(bytes.size)
            val type = bytes[i + 1].toInt() and 0xFF
            val data = bytes.copyOfRange(i + 2, end)
            out += AdLine(Hex.upper(bytes.copyOfRange(i, end)), label(type, data))
            i += len + 1
        }
        return out
    }

    private fun u16(d: ByteArray, at: Int) = (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8)

    private fun uuidName(u: Int): String {
        val n = DeviceDb.uuid16(u)?.name
        return "0x%04X".format(u) + (n?.let { " $it" } ?: "")
    }

    private fun label(type: Int, d: ByteArray): String = when (type) {
        0x01 -> {
            val f = d.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            val parts = buildList {
                if (f and 0x01 != 0) add("LE Limited Discoverable")
                if (f and 0x02 != 0) add("LE General Discoverable")
                add(if (f and 0x04 != 0) "LE-only (no BR/EDR)" else "BR/EDR capable — phone / tablet / computer class radio")
            }
            "Flags 0x%02X — %s".format(f, parts.joinToString(" · "))
        }
        0x02, 0x03, 0x14 -> {
            val what = when (type) { 0x02 -> "Incomplete 16-bit services"; 0x03 -> "16-bit services"; else -> "Solicited 16-bit services" }
            "$what: " + (0 until d.size / 2).joinToString { uuidName(u16(d, it * 2)) }
        }
        0x06, 0x07 -> "128-bit service UUID" + (if (d.size > 16) "s" else "") + " (vendor-specific)"
        0x08, 0x09 -> (if (type == 0x08) "Short name" else "Name") + " \"${d.decodeToString().trimEnd('\u0000')}\""
        0x0A -> "TX power ${d.firstOrNull()?.toInt() ?: 0} dBm (lets distance be estimated from RSSI)"
        0x16 -> if (d.size < 2) "Service data (empty)" else {
            val u = u16(d, 0)
            val body = d.copyOfRange(2, d.size)
            "Service data · ${uuidName(u)} · ${body.size} bytes" + serviceNote(u, body)
        }
        0x19 -> if (d.size < 2) "Appearance" else {
            val v = u16(d, 0)
            val a = DeviceDb.appearance(v)
            "Appearance 0x%04X".format(v) + (a?.let { " — ${it.category}" + (it.subcategory?.let { s -> " · $s" } ?: "") } ?: "")
        }
        0xFF -> if (d.size < 2) "Manufacturer data (empty)" else {
            val company = u16(d, 0)
            val body = d.copyOfRange(2, d.size)
            val who = VendorDb.company(company) ?: if (company == 0x4C00) "Apple ID byte-swapped (clone)" else "unregistered company"
            "Manufacturer data · $who (0x%04X) · ${body.size} bytes".format(company) + vendorNote(company, body)
        }
        else -> "AD type 0x%02X · ${d.size} bytes".format(type)
    }

    private fun serviceNote(uuid: Int, body: ByteArray): String = when (uuid) {
        0xFE2C -> if (body.size == 3) " — Google Fast Pair model %06X".format(
            ((body[0].toInt() and 0xFF) shl 16) or ((body[1].toInt() and 0xFF) shl 8) or (body[2].toInt() and 0xFF),
        ) + (DeviceDb.fastPairModel(((body[0].toInt() and 0xFF) shl 16) or ((body[1].toInt() and 0xFF) shl 8) or (body[2].toInt() and 0xFF))?.let { " (${it.name})" } ?: "")
        else " — Google Fast Pair (not discoverable)"
        0xFEAA -> when (body.firstOrNull()?.toInt()?.and(0xFF)) {
            0x00 -> " — Eddystone-UID"; 0x10 -> " — Eddystone-URL"; 0x20 -> " — Eddystone-TLM"; 0x30 -> " — Eddystone-EID"
            0x40, 0x41 -> " — Google Find My Device network tracker frame"; else -> ""
        }
        0xFFFA -> " — ASTM Remote ID (drone)"
        0xFD5A, 0xFD59 -> " — Samsung SmartTag"
        0xFD44 -> " — Apple Find My network accessory"
        else -> PayloadText.find(body).takeIf { it.isNotEmpty() }?.let { " — text \"${it.joinToString("\", \"")}\"" } ?: ""
    }

    private fun vendorNote(company: Int, body: ByteArray): String {
        val note = when (company) {
            0x004C -> " — Apple Continuity: " + BleFingerprinter.appleMessages(body).keys.joinToString { BleFingerprinter.appleTypeName(it) }
            0x0006 -> when (body.firstOrNull()?.toInt()?.and(0xFF)) {
                0x01 -> " — Microsoft CDP beacon, device type ${if (body.size > 1) body[1].toInt() and 0x1F else "?"}"
                0x03 -> " — Microsoft Swift Pair (pairing mode)"
                else -> ""
            }
            0x0075 -> if (body.size >= 2) " — Samsung frame %02X %02X".format(body[0].toInt() and 0xFF, body[1].toInt() and 0xFF) else ""
            else -> if (body.size >= 2 && (body[0].toInt() and 0xFF) == 0x02 && (body[1].toInt() and 0xFF) == 0x15) " — iBeacon layout" else ""
        }
        val text = PayloadText.find(body).takeIf { it.isNotEmpty() }?.let { " — text \"${it.joinToString("\", \"")}\"" } ?: ""
        return note + text
    }
}
