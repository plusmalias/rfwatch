package com.rfwatch.core.id

/**
 * What kind of device a radio most likely is, for filtering and display. A kind is a
 * best-effort label inferred from advertised identifiers — never a claim about who owns the
 * device or what it is being used for. [label] is the user-facing filter/category name.
 */
enum class DeviceKind(val label: String) {
    TRACKER("Tracker"),
    DRONE("Drone"),
    LAW_ENFORCEMENT("Axon / law"),
    EMERGENCY("Ambulance / fire"),
    ATTACK_TOOL("Hacking tool"),
    CAMERA_IOT("Camera / IoT"),
    FLEET("GPS tracker / fleet"),
    VEHICLE("Vehicle"),
    PHONE("Phone / tablet"),
    COMPUTER("Computer"),
    AUDIO("Audio"),
    WEARABLE("Wearable"),
    TV_MEDIA("TV / media"),
    HEALTH("Health / fitness"),
    INPUT("Keyboard / controller"),
    SMART_HOME("Smart home / IoT"),
    BEACON("Beacon"),
    HOTSPOT("Phone hotspot"),
    NETWORK("Router / access point"),
    UNKNOWN("Unidentified"),
}

/**
 * A kind inference plus the human-readable evidence for it. [model] is a more specific
 * best-effort name ("AirPods Pro", "Windows laptop") when the payload carries one.
 */
data class Fingerprint(
    val kind: DeviceKind,
    val model: String? = null,
    val evidence: String,
    /** Vendor implied by the payload itself (e.g. Apple Continuity → Apple), when known. */
    val brand: String? = null,
)
