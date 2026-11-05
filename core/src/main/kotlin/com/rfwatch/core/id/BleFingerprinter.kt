package com.rfwatch.core.id

import com.rfwatch.core.cs.BleAdvertisement

/**
 * Infers a [DeviceKind] for a BLE advertiser from its payload, strongest evidence first:
 *
 *  1. Vendor payloads — Apple Continuity (company 0x004C), Microsoft CDP / Swift Pair
 *     (0x0006), Google Fast Pair (service data 0xFE2C), iBeacon / AltBeacon / Eddystone.
 *  2. The GAP Appearance value (AD 0x19; Bluetooth SIG Assigned Numbers §2.6).
 *  3. Standard GATT service UUIDs (heart rate, HID, LE Audio, …).
 *  4. The advertised device name (user-settable — weakest, may be spoofed).
 *  5. The manufacturer's registered company name (e.g. Garmin → wearable).
 *
 * Sources: Apple Continuity type/length layout per Martin et al., "Handoff All Your Privacy"
 * (PETS 2019) and the furiousMAC/continuity dissectors; Apple does not document Continuity,
 * so Apple models are best-effort. Microsoft CDP beacon layout per the [MS-CDP] open
 * specification (scenario 0x01, low 5 bits of byte 1 = device type). Fast Pair per Google's
 * Fast Pair spec (service data under 0xFE2C, 3-byte model ID while discoverable).
 */
object BleFingerprinter {

    private const val APPLE = 0x004C
    private const val MICROSOFT = 0x0006
    private const val SAMSUNG = 0x0075

    fun fingerprint(adv: BleAdvertisement, name: String?): Fingerprint {
        var fp = classify(adv, name ?: adv.localName)
        if (fp.brand == null) fp = fp.copy(brand = brandOf(adv))
        // Readable text hidden in the binary payload ("acme.BeaconID", a firmware tag) is evidence.
        val text = (adv.manufacturerData.values + adv.serviceData16.values).flatMap { PayloadText.find(it) }
            .filter { t -> fp.name?.contains(t) != true }.distinct().take(3)
        if (text.isNotEmpty()) fp = fp.copy(evidence = fp.evidence + " · text in payload: " + text.joinToString { "\"$it\"" })
        if (fp.name == null && name == null) adv.localName?.let { fp = fp.copy(name = it) }
        if (fp.model == null && fp.kind == DeviceKind.UNKNOWN && fp.brand == null && adv.manufacturerData.isEmpty() &&
            adv.serviceData16.isEmpty() && adv.serviceUuids16.isEmpty() && adv.appearance == null && name == null && adv.localName == null
        ) fp = fp.copy(model = "BLE device (no payload)", evidence = "advert carries only flags/TX power — nothing identifying")
        return fp
    }

    /** Flags with BR/EDR support (bit 2 clear) and LE discoverable: a dual-mode phone/tablet/computer radio. */
    private fun dualMode(adv: BleAdvertisement): Boolean = adv.flags?.let { (it and 0x04) == 0 && (it and 0x03) != 0 } ?: false

    private fun classify(adv: BleAdvertisement, name: String?): Fingerprint {
        adv.serviceData16[RemoteIdParser.BLE_SERVICE_UUID]?.let { data ->
            val info = RemoteIdParser.parseBleServiceData(data)
            return Fingerprint(
                DeviceKind.DRONE, "Drone (Remote ID)" + (info?.serial?.let { " · $it" } ?: ""),
                "ASTM F3411 Remote ID broadcast (service 0xFFFA)",
            )
        }
        adv.manufacturerData[APPLE]?.let { apple(it, adv) }?.let { return it.copy(brand = "Apple") }
        adv.manufacturerData[MICROSOFT]?.let { microsoft(it) }?.let { return it.copy(brand = "Microsoft") }
        samsung(adv)?.let { return it }
        beacon(adv)?.let { return it }
        fastPair(adv)?.let { return it }
        adv.appearance?.let { appearance(it) }?.let { return it }
        services(adv.serviceUuids16)?.let { return it }
        name?.let { NameHints.ble(it) }?.let { return it }
        companyModel(adv)?.let { return it }
        adv.manufacturerData.keys.firstNotNullOfOrNull { id ->
            VendorDb.company(id)?.let { NameHints.company(it) }
        }?.let { return it }
        memberServiceCompany(adv.serviceUuids16)?.let { company ->
            NameHints.company(company)?.let { return it.copy(brand = VendorDb.shortName(company)) }
        }
        google(adv)?.let { return it }
        return Fingerprint(DeviceKind.UNKNOWN, evidence = "no identifying payload")
    }

    /** Brand from the registered company ID, else the owner of an advertised member service UUID. */
    private fun brandOf(adv: BleAdvertisement): String? =
        adv.manufacturerData.keys.firstNotNullOfOrNull { VendorDb.company(it) }
            ?: memberServiceCompany(adv.serviceUuids16)?.let(VendorDb::shortName)

    private fun memberServiceCompany(uuids: Set<Int>): String? =
        uuids.firstNotNullOfOrNull { u -> DeviceDb.uuid16(u)?.takeIf { !it.isSigService }?.name }

    // ---- Samsung EasySetup (Galaxy Buds / Watch pairing adverts; community-documented) ----

    private val BUDS_PREFIX = byteArrayOf(0x42, 0x09, 0x81.toByte(), 0x02, 0x14, 0x15, 0x03, 0x21, 0x01, 0x09)
    private val WATCH_PREFIX = byteArrayOf(0x00, 0x01, 0x00, 0x02, 0x00, 0x01, 0x01, 0xFF.toByte(), 0x00, 0x00, 0x43)

    private fun samsung(adv: BleAdvertisement): Fingerprint? {
        val records = adv.manufacturerRecords.filter { it.first == SAMSUNG }.map { it.second }
            .ifEmpty { listOfNotNull(adv.manufacturerData[SAMSUNG]) }
        if (records.isEmpty()) return null
        samsungFrame(records.first())?.let { return it }
        // 0x01 0x12 frames carry the owner's device name, continued in a second 0x01 0x12 record
        // ("Jo's Ga" + "laxy A52s"). Community-observed layout: 17 header bytes, then name bytes.
        val first = records.first()
        if (first.size > 17 && (first[0].toInt() and 0xFF) == 0x01 && (first[1].toInt() and 0xFF) == 0x12) {
            val tail = records.drop(1).filter { it.size > 2 && (it[0].toInt() and 0xFF) == 0x01 && (it[1].toInt() and 0xFF) == 0x12 }
                .joinToString("") { printable(it.copyOfRange(2, it.size)) ?: "" }
            val head = printable(first.copyOfRange(17, first.size))
            val deviceName = ((head ?: "") + tail).trim().takeIf { it.length >= 3 }
            if (deviceName != null) {
                val hint = NameHints.ble(deviceName)
                return Fingerprint(
                    hint?.kind?.takeIf { it != DeviceKind.UNKNOWN } ?: DeviceKind.PHONE, "Samsung Galaxy device",
                    "Samsung 0x0075 name frame (01 12) carries the device name \"$deviceName\"", "Samsung", name = deviceName,
                )
            }
        }
        val type = if (first.size >= 2) "%02X %02X".format(first[0].toInt() and 0xFF, first[1].toInt() and 0xFF) else "?"
        return Fingerprint(
            if (dualMode(adv)) DeviceKind.PHONE else DeviceKind.UNKNOWN, "Samsung Galaxy device",
            "Samsung 0x0075 frame type $type (Galaxy / SmartThings; exact product not encoded)" +
                if (dualMode(adv)) " · dual-mode radio flags" else "",
            "Samsung",
        )
    }

    /** Whole byte range as text if every byte is printable ASCII/UTF-8 (trailing zero padding ignored). */
    private fun printable(b: ByteArray): String? {
        val end = b.indexOfLast { it.toInt() != 0 } + 1
        if (end == 0) return ""
        val body = b.copyOfRange(0, end)
        if (body.any { (it.toInt() and 0xFF) < 0x20 || (it.toInt() and 0xFF) == 0x7F }) return null
        return body.decodeToString()
    }

    private fun samsungFrame(d: ByteArray): Fingerprint? {
        if (d.size >= 14 && d.copyOfRange(0, BUDS_PREFIX.size).contentEquals(BUDS_PREFIX)) {
            val model = ((d[10].toInt() and 0xFF) shl 16) or ((d[11].toInt() and 0xFF) shl 8) or (d[13].toInt() and 0xFF)
            val m = DeviceDb.samsungBudsModel(model)
            return Fingerprint(DeviceKind.AUDIO, m?.name ?: "Galaxy Buds", "Samsung EasySetup buds model %06X".format(model), "Samsung")
        }
        if (d.size >= 12 && d.copyOfRange(0, WATCH_PREFIX.size).contentEquals(WATCH_PREFIX)) {
            val model = d[11].toInt() and 0xFF
            val m = DeviceDb.samsungWatchModel(model)
            return Fingerprint(DeviceKind.WEARABLE, m?.name ?: "Galaxy Watch", "Samsung EasySetup watch model %02X".format(model), "Samsung")
        }
        return null
    }

    // ---- Apple Continuity: a sequence of [type][length][payload] messages ----

    private fun ipv4(p: ByteArray, at: Int): String? {
        if (p.size < at + 4) return null
        val o = (0 until 4).map { p[at + it].toInt() and 0xFF }
        return if (o.all { it == 0 }) null else o.joinToString(".")
    }

    /** Proximity-pairing battery nibbles (0–10 = ×10 %, 15 = unknown); layout per OpenPods / furiousMAC. */
    private fun podBattery(p: ByteArray): String? {
        if (p.size < 6) return null
        fun pct(n: Int) = if (n in 0..10) "${n * 10}%" else null
        val pods = listOfNotNull(pct((p[4].toInt() ushr 4) and 0x0F), pct(p[4].toInt() and 0x0F))
        val case = pct(p[5].toInt() and 0x0F)
        if (pods.isEmpty() && case == null) return null
        return "battery " + listOfNotNull(pods.takeIf { it.isNotEmpty() }?.joinToString("/", prefix = "pods "), case?.let { "case $it" }).joinToString(", ")
    }

    private val APPLE_TYPE_NAMES = mapOf(
        0x01 to "overflow area", 0x02 to "iBeacon", 0x03 to "AirPrint", 0x05 to "AirDrop", 0x06 to "HomeKit",
        0x07 to "Proximity Pairing", 0x08 to "Hey Siri", 0x09 to "AirPlay Target", 0x0A to "AirPlay Source",
        0x0B to "Magic Switch", 0x0C to "Handoff", 0x0D to "Tethering Target", 0x0E to "Tethering Source",
        0x0F to "Nearby Action", 0x10 to "Nearby Info", 0x12 to "Find My",
    )

    /** Apple Continuity message names (furiousMAC/continuity), shared with [AdvDecoder]. */
    fun appleTypeName(type: Int): String = APPLE_TYPE_NAMES[type] ?: "type 0x%02X".format(type)

    /** TLV messages of an Apple Continuity payload, first occurrence of each type. */
    fun appleMessages(data: ByteArray): LinkedHashMap<Int, ByteArray> {
        val messages = LinkedHashMap<Int, ByteArray>()
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            val end = (i + 2 + len).coerceAtMost(data.size)
            messages.putIfAbsent(type, data.copyOfRange(i + 2, end))
            i += 2 + len
        }
        return messages
    }

    private fun apple(data: ByteArray, adv: BleAdvertisement): Fingerprint? {
        val messages = appleMessages(data)
        messages[0x07]?.let { p ->
            val model = if (p.size >= 3) ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF) else null
            val name = model?.let { DeviceDb.appleAudioModel(it)?.name ?: APPLE_AUDIO_MODELS[it] }
            // Battery bytes only follow the full 25-byte form; the short form is encrypted.
            val battery = if (p.size >= 25 && name != null) podBattery(p) else null
            return Fingerprint(
                DeviceKind.AUDIO, name ?: "Apple/Beats audio",
                "Apple Continuity proximity pairing" + (model?.let { " model 0x%04X".format(it) } ?: "") +
                    (battery?.let { " · $it" } ?: ""),
            )
        }
        if (0x0B in messages) return Fingerprint(DeviceKind.WEARABLE, "Apple Watch", "Apple Continuity watch (magic switch)")
        messages[0x09]?.let { p ->
            // AirPlay target: flags, seed, then the receiver's IPv4 address.
            val ip = ipv4(p, 2)
            return Fingerprint(DeviceKind.TV_MEDIA, "AirPlay receiver", "Apple Continuity AirPlay target" + (ip?.let { " · local IP $it" } ?: ""))
        }
        messages[0x03]?.let { p ->
            // AirPrint: address type, resource path, security, TCP port (2), IPv4.
            val port = if (p.size >= 5) ((p[3].toInt() and 0xFF) shl 8) or (p[4].toInt() and 0xFF) else null
            val ip = ipv4(p, 5)
            return Fingerprint(
                DeviceKind.SMART_HOME, "AirPrint printer",
                "Apple Continuity AirPrint" + (ip?.let { " · printer at $it" + (port?.let { pt -> ":$pt" } ?: "") } ?: ""),
            )
        }
        if (0x02 in messages) return ibeacon(messages.getValue(0x02))
        if (0x0E in messages) return Fingerprint(DeviceKind.PHONE, "iPhone / iPad (hotspot)", "Apple Continuity tethering source")
        messages[0x0F]?.takeIf { it.size >= 2 }?.let { p ->
            val action = p[1].toInt() and 0xFF
            DeviceDb.appleNearbyAction(action)?.let { m ->
                return Fingerprint(m.kind, "Apple device — ${m.name}", "Apple Continuity nearby action 0x%02X".format(action))
            }
        }
        val personal = listOf(0x10 to "nearby info", 0x0C to "handoff", 0x0F to "nearby action", 0x05 to "AirDrop", 0x0A to "AirPlay source", 0x0D to "tethering target")
            .firstOrNull { it.first in messages }
        if (personal != null) {
            return Fingerprint(DeviceKind.PHONE, "iPhone / iPad / Mac", "Apple Continuity ${personal.second}")
        }
        messages[0x12]?.let { p ->
            // Find My offline-finding broadcast (AirTag, or an Apple device / accessory on the Find My network).
            val state = if (p.size >= 25) "separated from its owner" else "owner nearby"
            return Fingerprint(DeviceKind.TRACKER, "Apple Find My broadcast ($state)", "Apple Continuity Find My message 0x12, ${p.size}-byte payload")
        }
        if (messages.isEmpty()) return null
        val types = messages.keys.joinToString { t -> APPLE_TYPE_NAMES[t]?.let { "0x%02X (%s)".format(t, it) } ?: "type 0x%02X".format(t) }
        // Undocumented types (0x16, 0x1C, Hey Siri …): Apple for sure; the radio flags tell a
        // dual-mode iPhone/iPad/Mac apart from LE-only accessories.
        return if (dualMode(adv)) Fingerprint(
            DeviceKind.PHONE, "iPhone / iPad / Mac",
            "Apple Continuity $types · dual-mode radio flags 0x%02X (phone/tablet/computer class)".format(adv.flags),
        ) else Fingerprint(DeviceKind.UNKNOWN, "Apple device", "Apple Continuity $types")
    }

    private fun ibeacon(p: ByteArray): Fingerprint {
        val major = if (p.size >= 19) ((p[16].toInt() and 0xFF) shl 8) or (p[17].toInt() and 0xFF) else null
        return Fingerprint(DeviceKind.BEACON, "iBeacon", "Apple iBeacon frame" + (major?.let { " · major $it" } ?: ""))
    }

    /** Proximity-pairing model IDs (big-endian as they appear in the payload). Best-effort. */
    private val APPLE_AUDIO_MODELS = mapOf(
        0x0220 to "AirPods", 0x0F20 to "AirPods (2nd gen)", 0x1320 to "AirPods (3rd gen)",
        0x0E20 to "AirPods Pro", 0x1420 to "AirPods Pro (2nd gen)", 0x2420 to "AirPods Pro (2nd gen, USB-C)",
        0x0A20 to "AirPods Max", 0x0320 to "Powerbeats3", 0x0B20 to "Powerbeats Pro",
        0x0C20 to "Beats Solo Pro", 0x1120 to "Beats Studio Buds", 0x1020 to "Beats Flex",
        0x0520 to "BeatsX", 0x0620 to "Beats Solo3", 0x0920 to "Beats Studio3",
        0x1720 to "Beats Studio Pro", 0x1220 to "Beats Fit Pro", 0x1620 to "Beats Studio Buds+",
    )

    // ---- Microsoft ----

    private fun microsoft(data: ByteArray): Fingerprint? {
        if (data.isEmpty()) return null
        return when (data[0].toInt() and 0xFF) {
            0x01 -> { // Connected Devices Platform beacon
                if (data.size < 2) return null
                val type = data[1].toInt() and 0x1F
                val (kind, model) = CDP_TYPES[type] ?: return Fingerprint(
                    DeviceKind.COMPUTER, "Windows device", "Microsoft CDP beacon type $type",
                )
                Fingerprint(kind, model, "Microsoft CDP beacon")
            }
            0x03 -> Fingerprint(DeviceKind.UNKNOWN, "Swift Pair accessory (pairing mode)", "Microsoft Swift Pair")
            else -> null
        }
    }

    private val CDP_TYPES = mapOf(
        1 to (DeviceKind.TV_MEDIA to "Xbox"),
        6 to (DeviceKind.PHONE to "iPhone"),
        7 to (DeviceKind.PHONE to "iPad"),
        8 to (DeviceKind.PHONE to "Android device"),
        9 to (DeviceKind.COMPUTER to "Windows desktop"),
        11 to (DeviceKind.PHONE to "Windows phone"),
        12 to (DeviceKind.COMPUTER to "Linux device"),
        13 to (DeviceKind.SMART_HOME to "Windows IoT"),
        14 to (DeviceKind.COMPUTER to "Surface Hub"),
        15 to (DeviceKind.COMPUTER to "Windows laptop"),
        16 to (DeviceKind.PHONE to "Windows tablet"),
    )

    // ---- Beacons / Fast Pair / services ----

    private fun beacon(adv: BleAdvertisement): Fingerprint? {
        adv.manufacturerData.values.firstOrNull { it.size >= 2 && (it[0].toInt() and 0xFF) == 0xBE && (it[1].toInt() and 0xFF) == 0xAC }
            ?.let { return Fingerprint(DeviceKind.BEACON, "AltBeacon", "AltBeacon frame") }
        // iBeacon layout (02 15 + UUID/major/minor/TX) under a non-Apple company: generic beacon
        // modules, and cheap clones that write Apple's ID byte-swapped (0x4C00).
        adv.manufacturerData.entries.firstOrNull { (_, d) -> d.size >= 23 && (d[0].toInt() and 0xFF) == 0x02 && (d[1].toInt() and 0xFF) == 0x15 }
            ?.let { (company, d) ->
                val major = ((d[18].toInt() and 0xFF) shl 8) or (d[19].toInt() and 0xFF)
                val minor = ((d[20].toInt() and 0xFF) shl 8) or (d[21].toInt() and 0xFF)
                val maker = if (company == 0x4C00) null else VendorDb.company(company)
                return Fingerprint(
                    DeviceKind.BEACON,
                    if (company == 0x4C00) "iBeacon clone" else "iBeacon-format beacon",
                    (if (company == 0x4C00) "iBeacon frame with Apple's company ID byte-swapped (0x4C00) — non-Apple clone"
                    else "iBeacon frame under company 0x%04X".format(company) + (maker?.let { " ($it)" } ?: "")) +
                        " · major $major minor $minor",
                    maker,
                )
            }
        adv.serviceData16[0xFEAA]?.let { frame ->
            val f = frame.firstOrNull()?.toInt()?.and(0xFF) ?: return null
            val label = when (f) {
                0x00 -> "Eddystone-UID"; 0x10 -> "Eddystone-URL"; 0x20 -> "Eddystone-TLM"; 0x30 -> "Eddystone-EID"
                else -> return null // 0x40/0x41 = Google Find My Device: the tracker parser owns that
            }
            return Fingerprint(DeviceKind.BEACON, label, "Eddystone frame 0x%02X".format(f))
        }
        if (0xFE9A in adv.serviceUuids16) return Fingerprint(DeviceKind.BEACON, "Estimote beacon", "service 0xFE9A")
        return null
    }

    private fun fastPair(adv: BleAdvertisement): Fingerprint? {
        val data = adv.serviceData16[0xFE2C] ?: return if (0xFE2C in adv.serviceUuids16)
            Fingerprint(DeviceKind.AUDIO, "Fast Pair accessory", "Google Fast Pair service") else null
        val modelId = if (data.size == 3)
            ((data[0].toInt() and 0xFF) shl 16) or ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        else null
        modelId?.let { DeviceDb.fastPairModel(it) }?.let { m ->
            return Fingerprint(m.kind, m.name, "Google Fast Pair model %06X".format(modelId), m.brand)
        }
        // Fast Pair is overwhelmingly earbuds/headphones/speakers; other kinds advertise an Appearance.
        return Fingerprint(
            DeviceKind.AUDIO, "Fast Pair accessory" + (modelId?.let { " (model %06X)".format(it) } ?: ""),
            "Google Fast Pair service data" + (if (modelId != null) " — discoverable" else ""),
        )
    }

    /**
     * Google member-service frames (0xFEF3, 0xFCF1, 0xFE9F) are sent by Android phones and Google
     * hardware alike; Google does not publish their layout, so only the owner is claimed.
     */
    private fun google(adv: BleAdvertisement): Fingerprint? {
        val uuid = GOOGLE_SERVICES.firstOrNull { it in adv.serviceUuids16 || it in adv.serviceData16 } ?: return null
        return Fingerprint(
            if (dualMode(adv)) DeviceKind.PHONE else DeviceKind.UNKNOWN, "Google / Android device",
            "Google service 0x%04X (Android / Google hardware; layout unpublished)".format(uuid) +
                if (dualMode(adv)) " · dual-mode radio flags" else "",
            "Google",
        )
    }

    private val GOOGLE_SERVICES = listOf(0xFEF3, 0xFCF1, 0xFE9F)

    /** Companies whose adverts identify a product line even without a decoded model. */
    private fun companyModel(adv: BleAdvertisement): Fingerprint? {
        val hpService = HP_SERVICES.firstOrNull { it in adv.serviceUuids16 || it in adv.serviceData16 }
        if (0x0065 in adv.manufacturerData || hpService != null) return Fingerprint(
            DeviceKind.SMART_HOME, "HP printer / PC",
            "HP company 0x0065" + (hpService?.let { " · HP service 0x%04X".format(it) } ?: "") + " (HP Smart setup beacon)", "HP",
        )
        adv.manufacturerData.keys.firstNotNullOfOrNull { COMPANY_MODELS[it] }?.let { (kind, model) ->
            val id = adv.manufacturerData.keys.first { it in COMPANY_MODELS }
            return Fingerprint(kind, model, "company 0x%04X".format(id) + (VendorDb.company(id)?.let { " ($it)" } ?: ""))
        }
        return null
    }

    private val HP_SERVICES = listOf(0xFDF7, 0xFE78, 0xFCA4)

    private val COMPANY_MODELS = mapOf(
        0x02E1 to (DeviceKind.SMART_HOME to "Victron solar / battery device"),
        0x0087 to (DeviceKind.WEARABLE to "Garmin wearable"),
        0x0157 to (DeviceKind.WEARABLE to "Huami / Amazfit wearable"),
        0x012D to (DeviceKind.UNKNOWN to "Sony device"),
        0x0499 to (DeviceKind.SMART_HOME to "Ruuvi sensor tag"),
        0x038F to (DeviceKind.SMART_HOME to "Xiaomi device"),
        0x0969 to (DeviceKind.SMART_HOME to "Woan / SwitchBot device"),
        0x0822 to (DeviceKind.UNKNOWN to "Adafruit maker board"),
    )

    private fun services(uuids: Set<Int>): Fingerprint? {
        for ((uuid, hit) in SERVICE_HINTS) if (uuid in uuids) return Fingerprint(hit.first, hit.second, "service 0x%04X".format(uuid))
        return null
    }

    private val SERVICE_HINTS = listOf(
        0x1812 to (DeviceKind.INPUT to "HID device"),
        0x180D to (DeviceKind.HEALTH to "Heart-rate sensor"),
        0x1810 to (DeviceKind.HEALTH to "Blood-pressure monitor"),
        0x1808 to (DeviceKind.HEALTH to "Glucose meter"),
        0x1809 to (DeviceKind.HEALTH to "Thermometer"),
        0x181D to (DeviceKind.HEALTH to "Weight scale"),
        0x1822 to (DeviceKind.HEALTH to "Pulse oximeter"),
        0x183A to (DeviceKind.HEALTH to "Insulin delivery"),
        0x1816 to (DeviceKind.HEALTH to "Cycling sensor"),
        0x1814 to (DeviceKind.HEALTH to "Running sensor"),
        0x1826 to (DeviceKind.HEALTH to "Fitness machine"),
        0x184E to (DeviceKind.AUDIO to "LE Audio device"),
        0x1850 to (DeviceKind.AUDIO to "LE Audio device"),
        0x1844 to (DeviceKind.AUDIO to "Audio device (volume control)"),
        0xFE07 to (DeviceKind.AUDIO to "Sonos"),
        0xFEBE to (DeviceKind.AUDIO to "Bose"),
        0xFE03 to (DeviceKind.SMART_HOME to "Amazon / Alexa device"),
        0xFD6F to (DeviceKind.PHONE to "Phone (exposure notifications)"),
    )

    // ---- GAP Appearance: category = value >> 6 ----

    private fun appearance(value: Int): Fingerprint? {
        val category = value ushr 6
        val hit = APPEARANCE[category] ?: return null
        val sub = DeviceDb.appearance(value)?.subcategory
        return Fingerprint(hit.first, if (sub != null) "${hit.second} · $sub" else hit.second, "GAP appearance 0x%04X".format(value))
    }

    private val APPEARANCE = mapOf(
        1 to (DeviceKind.PHONE to "Phone"), 2 to (DeviceKind.COMPUTER to "Computer"),
        3 to (DeviceKind.WEARABLE to "Watch"), 4 to (DeviceKind.SMART_HOME to "Clock"),
        5 to (DeviceKind.TV_MEDIA to "Display"), 6 to (DeviceKind.INPUT to "Remote control"),
        7 to (DeviceKind.WEARABLE to "Eyeglasses"), 8 to (DeviceKind.TRACKER to "Tag"),
        9 to (DeviceKind.TRACKER to "Keyring tag"), 10 to (DeviceKind.TV_MEDIA to "Media player"),
        11 to (DeviceKind.INPUT to "Barcode scanner"), 12 to (DeviceKind.HEALTH to "Thermometer"),
        13 to (DeviceKind.HEALTH to "Heart-rate sensor"), 14 to (DeviceKind.HEALTH to "Blood-pressure monitor"),
        15 to (DeviceKind.INPUT to "Keyboard / mouse / gamepad"), 16 to (DeviceKind.HEALTH to "Glucose meter"),
        17 to (DeviceKind.HEALTH to "Running sensor"), 18 to (DeviceKind.HEALTH to "Cycling sensor"),
        19 to (DeviceKind.SMART_HOME to "Control device"), 20 to (DeviceKind.NETWORK to "Network device"),
        21 to (DeviceKind.SMART_HOME to "Sensor"), 22 to (DeviceKind.SMART_HOME to "Light fixture"),
        23 to (DeviceKind.SMART_HOME to "Fan"), 24 to (DeviceKind.SMART_HOME to "HVAC"),
        25 to (DeviceKind.SMART_HOME to "Air conditioner"), 26 to (DeviceKind.SMART_HOME to "Humidifier"),
        27 to (DeviceKind.SMART_HOME to "Heater"), 28 to (DeviceKind.SMART_HOME to "Access control / lock"),
        29 to (DeviceKind.SMART_HOME to "Motorised device"), 30 to (DeviceKind.SMART_HOME to "Power device"),
        31 to (DeviceKind.SMART_HOME to "Light source"), 32 to (DeviceKind.SMART_HOME to "Window covering"),
        33 to (DeviceKind.AUDIO to "Audio sink"), 34 to (DeviceKind.AUDIO to "Audio source"),
        35 to (DeviceKind.VEHICLE to "Motorised vehicle"), 36 to (DeviceKind.SMART_HOME to "Domestic appliance"),
        37 to (DeviceKind.AUDIO to "Wearable audio"), 38 to (DeviceKind.VEHICLE to "Aircraft / drone"),
        39 to (DeviceKind.TV_MEDIA to "AV equipment"), 40 to (DeviceKind.TV_MEDIA to "Display equipment"),
        41 to (DeviceKind.HEALTH to "Hearing aid"), 42 to (DeviceKind.INPUT to "Gaming controller"),
        43 to (DeviceKind.TV_MEDIA to "Signage"), 49 to (DeviceKind.HEALTH to "Pulse oximeter"),
        50 to (DeviceKind.HEALTH to "Weight scale"), 51 to (DeviceKind.VEHICLE to "Personal mobility"),
        52 to (DeviceKind.HEALTH to "Continuous glucose monitor"), 53 to (DeviceKind.HEALTH to "Insulin pump"),
        54 to (DeviceKind.HEALTH to "Medication delivery"), 55 to (DeviceKind.HEALTH to "Spirometer"),
        81 to (DeviceKind.HEALTH to "Outdoor sports activity"),
    )
}
