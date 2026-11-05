package com.rfwatch.core.rules

import kotlinx.serialization.Serializable

/** What a matched rule means for the user. Drives alert priority and colour. */
enum class Threat { INFO, NOTABLE, PRIORITY }

/** Which toggleable pack a rule belongs to. */
enum class RuleCategory { LAW_ENFORCEMENT, TRACKER, CAMERA_IOT, FLEET, EMERGENCY_SERVICES, ATTACK_TOOLS, DRONE }

/**
 * A single detection rule. A device matches if ANY of the populated predicates match
 * (logical OR); empty predicates are ignored. All matching is case-insensitive and the
 * OUI/company comparisons are normalised to lowercase hex without separators.
 */
@Serializable
data class Rule(
    val id: String,
    val label: String,
    val threat: Threat = Threat.NOTABLE,
    /** MAC OUI prefixes, e.g. "4419b6" (first 3 octets, no separators). */
    val ouiPrefixes: List<String> = emptyList(),
    /** Regular expressions matched against Wi-Fi SSID (case-insensitive). */
    val ssidPatterns: List<String> = emptyList(),
    /** BLE manufacturer company IDs (decimal). */
    val companyIds: List<Int> = emptyList(),
    /** 16-bit service UUIDs (decimal). */
    val serviceUuids: List<Int> = emptyList(),
    /** Case-insensitive substrings matched against the advertised device name. */
    val nameSubstrings: List<String> = emptyList(),
    val note: String = "",
)

@Serializable
data class RulePack(
    val id: String,
    val name: String,
    val category: RuleCategory,
    val source: String = "",
    val version: Int = 1,
    val rules: List<Rule> = emptyList(),
)

/** A fact about a device to test rules against, assembled by the :app scan layer. */
data class DeviceFacts(
    val mac: String?,
    val ssid: String?,
    val name: String?,
    val companyIds: Set<Int> = emptySet(),
    val serviceUuids: Set<Int> = emptySet(),
    val hiddenSsid: Boolean = false,
    val rssi: Int = -100,
)

data class RuleHit(
    val packId: String,
    val category: RuleCategory,
    val ruleId: String,
    val label: String,
    val threat: Threat,
    val matchedOn: String,
)

/**
 * Evaluates [DeviceFacts] against a set of enabled [RulePack]s. A pack contributes hits
 * only while enabled, so the UI's per-pack toggles simply filter the pack list passed in.
 */
class RuleEngine(private val packs: List<RulePack>) {

    private val nonAlnum = Regex("[^0-9a-z]")

    // SSID patterns are compiled once: evaluate() runs for every scan result, and compiling a
    // Regex per rule per advertisement dominated ingest cost. Invalid patterns never match.
    private val ssidRegexes: Map<String, Regex> = packs.asSequence()
        .flatMap { it.rules.asSequence() }
        .flatMap { it.ssidPatterns.asSequence() }
        .distinct()
        .mapNotNull { p -> runCatching { p to Regex(p, RegexOption.IGNORE_CASE) }.getOrNull() }
        .toMap()

    private fun norm(s: String) = s.lowercase().replace(nonAlnum, "")

    fun evaluate(facts: DeviceFacts): List<RuleHit> {
        val hits = ArrayList<RuleHit>()
        val macNorm = facts.mac?.let { norm(it) }
        for (pack in packs) {
            for (rule in pack.rules) {
                val matchedOn = matchReason(rule, facts, macNorm) ?: continue
                hits.add(RuleHit(pack.id, pack.category, rule.id, rule.label, rule.threat, matchedOn))
            }
        }
        return hits
    }

    private fun matchReason(rule: Rule, facts: DeviceFacts, macNorm: String?): String? {
        if (macNorm != null) {
            rule.ouiPrefixes.firstOrNull { macNorm.startsWith(norm(it)) }
                ?.let { return "OUI $it" }
        }
        facts.ssid?.let { ssid ->
            rule.ssidPatterns.firstOrNull { ssidRegexes[it]?.containsMatchIn(ssid) == true }
                ?.let { return "SSID ~ /$it/" }
        }
        facts.name?.let { name ->
            rule.nameSubstrings.firstOrNull { name.contains(it, ignoreCase = true) }
                ?.let { return "name contains \"$it\"" }
        }
        rule.companyIds.firstOrNull { it in facts.companyIds }?.let { return "company 0x${it.toString(16)}" }
        rule.serviceUuids.firstOrNull { it in facts.serviceUuids }?.let { return "service 0x${it.toString(16)}" }
        return null
    }
}
