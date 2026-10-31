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
        val fp = classify(adv, name)
        return if (fp.brand != null) fp else fp.copy(brand = brandOf(adv))
    }

    private fun classify(adv: BleAdvertisement, name: String?): Fingerprint {
        adv.manufacturerData[APPLE]?.let { apple(it) }?.let { return it.copy(brand = "Apple") }
        adv.manufacturerData[MICROSOFT]?.let { microsoft(it) }?.let { return it.copy(brand = "Microsoft") }
        adv.manufacturerData[SAMSUNG]?.let { samsung(it) }?.let { return it }
        beacon(adv)?.let { return it }
        fastPair(adv)?.let { return it }
        adv.appearance?.let { appearance(it) }?.let { return it }
        services(adv.serviceUuids16)?.let { return it }
        name?.let { NameHints.ble(it) }?.let { return it }
        adv.manufacturerData.keys.firstNotNullOfOrNull { id ->
            VendorDb.company(id)?.let { NameHints.company(it) }
        }?.let { return it }
        memberServiceCompany(adv.serviceUuids16)?.let { company ->
            NameHints.company(company)?.let { return it.copy(brand = VendorDb.shortName(company)) }
        }
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

    private fun samsung(d: ByteArray): Fingerprint? {
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

    private fun apple(data: ByteArray): Fingerprint? {
        val messages = HashMap<Int, ByteArray>()
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            val end = (i + 2 + len).coerceAtMost(data.size)
            messages.putIfAbsent(type, data.copyOfRange(i + 2, end))
            i += 2 + len
        }
        messages[0x07]?.let { p ->
            val model = if (p.size >= 3) ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF) else null
            val name = model?.let { DeviceDb.appleAudioModel(it)?.name ?: APPLE_AUDIO_MODELS[it] }
            return Fingerprint(
                DeviceKind.AUDIO, name ?: "Apple/Beats audio",
                "Apple Continuity proximity pairing" + (model?.let { " model 0x%04X".format(it) } ?: ""),
            )
        }
        if (0x0B in messages) return Fingerprint(DeviceKind.WEARABLE, "Apple Watch", "Apple Continuity watch (magic switch)")
        if (0x09 in messages) return Fingerprint(DeviceKind.TV_MEDIA, "AirPlay receiver", "Apple Continuity AirPlay target")
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
        return null // e.g. only 0x12 (Find My) — the tracker parser owns that
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
