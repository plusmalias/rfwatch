package com.rfwatch.core.cs

/** A known stalkerware / watchware family from the bundled indicator list. */
data class StalkerwareFamily(val name: String, val type: String, val aliases: String)

/**
 * Bundled indicators from AssoEchap/stalkerware-indicators (CC BY 4.0) — the list used by
 * Coalition Against Stalkerware members: package names and APK signing-certificate SHA-1s.
 */
object StalkerwareDb {
    private val packages by lazy {
        load("spyware_packages.tsv") { c -> c[0] to StalkerwareFamily(c[1], c[2], "") } +
            load("packages.tsv") { c -> c[0] to StalkerwareFamily(c[1], c[2], c.getOrElse(3) { "" }) }
    }
    private val certs by lazy {
        load("sample_certificates.tsv") { c -> c[0].uppercase() to StalkerwareFamily(c[1], c[2], "") } +
            load("certificates.tsv") { c -> c[0].uppercase() to StalkerwareFamily(c[1], c[2], "") }
    }
    private val apkHashes by lazy { load("apk_sha256.tsv") { c -> c[0].lowercase() to StalkerwareFamily(c[1], c[2], c.getOrElse(3) { "" }) } }

    // Curated family lists (packages.tsv / certificates.tsv) are added last so their names win.
    fun byPackage(pkg: String): StalkerwareFamily? = packages[pkg]
    fun byCertSha1(sha1Hex: String): StalkerwareFamily? = certs[sha1Hex.uppercase().filter { it.isLetterOrDigit() }]
    /** Exact APK file match (stalkerware samples + mercenary-spyware samples from MVT indicators). */
    fun byApkSha256(sha256Hex: String): StalkerwareFamily? = apkHashes[sha256Hex.lowercase()]
    val familyCount: Int get() = (packages.values + certs.values + apkHashes.values).map { it.name }.toSet().size
    val packageCount: Int get() = packages.size
    val certificateCount: Int get() = certs.size
    val apkHashCount: Int get() = apkHashes.size

    private fun <V> load(file: String, parse: (List<String>) -> Pair<String, V>): Map<String, V> {
        val out = HashMap<String, V>()
        StalkerwareDb::class.java.getResourceAsStream("/stalkerware/$file")?.bufferedReader()?.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                runCatching { parse(line.split('\t')) }.getOrNull()?.let { (k, v) -> out.putIfAbsent(k, v) }
            }
        }
        return out
    }
}

/** Neutral facts about one installed app, gathered by the Android layer. */
data class AppFacts(
    val packageName: String,
    val label: String,
    val certSha1: List<String> = emptyList(),
    /** SHA-256 of the installed base APK (null if not computed, e.g. system apps). */
    val apkSha256: String? = null,
    val isSystem: Boolean = false,
    val installer: String? = null,
    val hasLauncherIcon: Boolean = true,
    val accessibilityEnabled: Boolean = false,
    val deviceAdminActive: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val grantedPermissions: Set<String> = emptySet(),
    val firstInstallMs: Long = 0,
    val lastUpdateMs: Long = 0,
)

enum class AppRisk { CRITICAL, HIGH, MEDIUM, LOW }

data class AppAssessment(val facts: AppFacts, val score: Int, val risk: AppRisk, val family: StalkerwareFamily?, val reasons: List<String>)

/**
 * Scores installed apps for stalkerware. A package or signing certificate on the indicator list is
 * CRITICAL outright. Otherwise risk comes from the combination stalkerware needs to work — reading
 * the screen (Accessibility), resisting removal (device admin), reading notifications, hiding its
 * icon, being sideloaded, and holding SMS / call-log / microphone / location access — each point
 * explained. Legitimate apps (password managers, launchers) can score MEDIUM; judge the reasons.
 */
object StalkerwareScorer {

    private val SYSTEM_SOUNDING = Regex(
        "^(system|sys|android|google|update|service|sync|wi-?fi|settings|device|security|battery|framework|backup|cloud)" +
            "|(service|services|update|updater|sync|framework|manager|helper|core)$",
        RegexOption.IGNORE_CASE,
    )
    private val STORES = setOf("com.android.vending", "com.amazon.venezia", "com.huawei.appmarket", "com.sec.android.app.samsungapps", "com.xiaomi.market", "org.fdroid.fdroid")

    private val PERMISSION_POINTS = listOf(
        "android.permission.READ_SMS" to (10 to "reads SMS messages"),
        "android.permission.RECEIVE_SMS" to (5 to "intercepts incoming SMS"),
        "android.permission.READ_CALL_LOG" to (10 to "reads the call log"),
        "android.permission.PROCESS_OUTGOING_CALLS" to (5 to "sees outgoing calls"),
        "android.permission.RECORD_AUDIO" to (5 to "can record audio"),
        "android.permission.CAMERA" to (3 to "can use the camera"),
        "android.permission.ACCESS_BACKGROUND_LOCATION" to (10 to "tracks location in the background"),
        "android.permission.READ_CONTACTS" to (3 to "reads contacts"),
        "android.permission.PACKAGE_USAGE_STATS" to (8 to "monitors which apps you use"),
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to (0 to "declares an accessibility service"),
        "android.permission.SYSTEM_ALERT_WINDOW" to (3 to "can draw over other apps"),
        "android.permission.READ_PHONE_STATE" to (2 to "reads phone/SIM state"),
    )

    fun assess(f: AppFacts, nowMs: Long): AppAssessment {
        val reasons = ArrayList<String>()
        var score = 0
        fun add(p: Int, why: String) { score += p; reasons += "+$p $why" }

        val family = StalkerwareDb.byPackage(f.packageName)?.also {
            add(100, "package ${f.packageName} is a known ${it.type} app: ${it.name}" + if (it.aliases.isNotBlank()) " (also sold as ${it.aliases})" else "")
        } ?: f.apkSha256?.let { h -> StalkerwareDb.byApkSha256(h)?.also {
            add(100, "APK file SHA-256 $h exactly matches a known ${it.type} sample: ${it.name}" + if (it.aliases.isNotBlank()) " (indicator source: ${it.aliases})" else "")
        } } ?: f.certSha1.firstNotNullOfOrNull { sha -> StalkerwareDb.byCertSha1(sha)?.also {
            add(100, "signed with certificate $sha belonging to known ${it.type}: ${it.name}")
        } }

        if (f.accessibilityEnabled) add(30, "Accessibility service is ON — it can read everything on screen and what you type")
        if (f.deviceAdminActive) add(25, "is an active device administrator — harder to uninstall, can lock/wipe the phone")
        if (f.notificationListenerEnabled) add(20, "reads all your notifications (messages, 2FA codes)")
        if (!f.hasLauncherIcon && !f.isSystem) add(20, "has no app-drawer icon (hidden from you)")
        if (!f.isSystem && (f.installer == null || f.installer !in STORES)) {
            add(10, "installed outside an app store (installer: ${f.installer ?: "unknown / sideloaded"})")
        }
        if (SYSTEM_SOUNDING.containsMatchIn(f.label) && !f.isSystem) add(15, "name \"${f.label}\" imitates a system component")
        PERMISSION_POINTS.forEach { (perm, pr) -> if (perm in f.grantedPermissions && pr.first > 0) add(pr.first, pr.second) }
        if (f.firstInstallMs > 0 && nowMs - f.firstInstallMs < 30L * 24 * 3600_000) add(5, "installed in the last 30 days")

        val risk = when {
            family != null -> AppRisk.CRITICAL
            score >= 70 -> AppRisk.HIGH
            score >= 40 -> AppRisk.MEDIUM
            else -> AppRisk.LOW
        }
        return AppAssessment(f, score, risk, family, reasons)
    }
}
