package com.rfwatch.core.id

/**
 * Weak kind hints from advertised names, SSIDs and registered company names. Names and
 * SSIDs are user-settable and spoofable, so these rank below any payload evidence. Order
 * matters: more specific tokens (e.g. "galaxy buds") are checked before broad ones ("galaxy").
 */
internal object NameHints {

    private val BLE_TOKENS: List<Pair<DeviceKind, List<String>>> = listOf(
        DeviceKind.TRACKER to listOf("airtag", "smarttag", "chipolo", "tile "),
        DeviceKind.AUDIO to listOf(
            "airpods", "buds", "beats", "jbl", "bose", "wh-1000", "wf-1000", "wh-", "wf-", "headphone", "headset",
            "earbud", "speaker", "soundbar", "sonos", "jabra", "sennheiser", "marshall", "soundcore", "skullcandy",
            "beoplay", "audio", "boombox",
        ),
        DeviceKind.WEARABLE to listOf(
            "watch", "fitbit", "garmin", "forerunner", "fenix", "venu", "amazfit", "mi band", "smart band", "whoop",
            "oura", "polar", "suunto", "charge 5", "charge 6", "versa", "inspire",
        ),
        DeviceKind.HEALTH to listOf("scale", "oximeter", "thermometer", "glucose", "libre", "dexcom", "omron", "withings", "hrm"),
        DeviceKind.INPUT to listOf(
            "keyboard", "mouse", "mx master", "mx keys", "controller", "gamepad", "joy-con", "dualsense", "dualshock", "remote",
        ),
        DeviceKind.COMPUTER to listOf("macbook", "imac", "mac mini", "mac studio", "desktop-", "laptop-", "thinkpad", "chromebook"),
        DeviceKind.TV_MEDIA to listOf(
            "[tv]", "tv ", " tv", "bravia", "roku", "chromecast", "fire tv", "firetv", "apple tv", "shield", "xbox",
            "playstation", "ps5", "ps4", "webos", "hisense", "projector",
        ),
        DeviceKind.VEHICLE to listOf(
            "tesla", "model 3", "model y", "my car", "carplay", "toyota", "mazda", "ford", "holden", "hyundai", "kia ",
            "subaru", "nissan", "mitsubishi", "honda", "volkswagen", "vw ", "bmw", "audi", "mercedes", "isuzu",
            "hilux", "ranger", "obdii", "obd2", "elm327",
        ),
        DeviceKind.SMART_HOME to listOf(
            "hue", "lifx", "nanoleaf", "govee", "tuya", "smart plug", "bulb", "lamp", "light", "lock", "echo", "alexa",
            "nest", "ecobee", "tado", "switchbot", "yeelight", "tapo", "kasa", "shelly", "sonoff", "esp32", "esp_",
            "tasmota", "printer", "roomba", "roborock", "ecovacs", "thermostat", "sensor",
        ),
        DeviceKind.BEACON to listOf("beacon", "estimote", "kontakt"),
        DeviceKind.PHONE to listOf(
            "iphone", "ipad", "galaxy", "pixel", "oneplus", "redmi", "xiaomi", "oppo", "vivo", "moto ", "nokia", "phone",
        ),
    )

    fun ble(name: String): Fingerprint? = match(name)?.let { (kind, token) ->
        Fingerprint(kind, evidence = "advertised name contains \"$token\"")
    }

    private val WIFI_TOKENS: List<Pair<DeviceKind, List<String>>> = listOf(
        DeviceKind.HOTSPOT to listOf(
            "iphone", "ipad", "androidap", "android ap", "galaxy", "pixel", "oneplus", "redmi", "oppo", "vivo",
            "huawei", "'s phone", "hotspot", "mobile wifi", "mifi",
        ),
        DeviceKind.VEHICLE to listOf(
            "tesla", "carplay", "android auto", "toyota", "mazda", "ford", "holden", "hyundai", "kia", "subaru",
            "nissan", "mitsubishi", "honda", "vw_", "volkswagen", "bmw", "audi", "mercedes", "isuzu", "my car",
        ),
        DeviceKind.TV_MEDIA to listOf(
            "chromecast", "roku", "firetv", "fire tv", "[tv]", "bravia", "samsung tv", "lg_tv", "lgwebostv", "webos",
            "xbox", "playstation", "hisense", "tcl", "projector",
        ),
        DeviceKind.SMART_HOME to listOf(
            "esp_", "esp-", "esp32", "tasmota", "shelly", "sonoff", "tuya", "smartlife", "smart life", "hue", "nest",
            "echo", "hp-print", "print", "epson", "canon", "brother", "tapo", "kasa", "meross", "wemo", "myq",
            "dyson", "ecovacs", "roborock", "roomba", "irobot", "daikin", "fridge", "washer", "aircon",
        ),
    )

    /** Wi-Fi SSID hints; [locallyAdministered] BSSIDs are typical of phone hotspots. */
    fun wifi(ssid: String, locallyAdministered: Boolean): Fingerprint? {
        val lower = ssid.lowercase()
        for ((kind, tokens) in WIFI_TOKENS) {
            val token = tokens.firstOrNull { it in lower } ?: continue
            return Fingerprint(kind, evidence = "SSID contains \"$token\"")
        }
        if (locallyAdministered && "'s " in lower) {
            return Fingerprint(DeviceKind.HOTSPOT, evidence = "personal SSID on a randomised BSSID")
        }
        return null
    }

    private val COMPANY_TOKENS: List<Pair<DeviceKind, List<String>>> = listOf(
        DeviceKind.WEARABLE to listOf("garmin", "fitbit", "polar electro", "suunto", "huami", "whoop", "oura", "zepp", "amazfit"),
        DeviceKind.AUDIO to listOf(
            "bose", "sennheiser", "harman", "jabra", "gn audio", "gn hearing", "bang & olufsen", "skullcandy",
            "sonos", "plantronics", "poly", "shure", "audio-technica", "beats",
        ),
        DeviceKind.VEHICLE to listOf(
            "tesla", "toyota", "ford motor", "general motors", "bmw", "volkswagen", "hyundai", "kia", "mercedes",
            "daimler", "honda", "nissan", "mazda", "volvo car", "subaru", "porsche", "audi", "stellantis",
        ),
        DeviceKind.INPUT to listOf("logitech", "razer", "8bitdo"),
        DeviceKind.HEALTH to listOf("omron", "withings", "dexcom", "abbott", "medtronic", "roche", "insulet", "oticon", "phonak", "sonova", "widex", "resmed"),
        DeviceKind.SMART_HOME to listOf(
            "signify", "philips lighting", "nanoleaf", "lifx", "tuya", "ecobee", "wyze", "govee", "switchbot",
            "shenzhen", "espressif", "nordic semiconductor", "silicon laboratories", "telink", "realtek", "beken",
            "bouffalo", "amazon", "ikea", "yale", "assa abloy", "august", "irobot", "dyson",
        ),
        DeviceKind.TV_MEDIA to listOf("roku", "nintendo", "sony interactive"),
    )

    /** Kind from a registered company name — used only when nothing better is advertised. */
    fun company(company: String): Fingerprint? {
        val lower = company.lowercase()
        for ((kind, tokens) in COMPANY_TOKENS) {
            if (tokens.any { it in lower }) return Fingerprint(kind, evidence = "manufacturer $company")
        }
        return null
    }

    private fun match(name: String): Pair<DeviceKind, String>? {
        val lower = name.lowercase()
        for ((kind, tokens) in BLE_TOKENS) {
            tokens.firstOrNull { it in lower }?.let { return kind to it }
        }
        return null
    }
}
