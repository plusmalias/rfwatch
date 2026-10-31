package com.rfwatch.core.alerts

import com.rfwatch.core.rules.Threat

/** One law-enforcement signature currently (or recently) heard during an encounter. */
data class LawSignature(
    val deviceId: String,
    val label: String,
    val threat: Threat,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val lastRssi: Int,
    val maxRssi: Int,
)

/** Snapshot of the current encounter for the live notification and UI. */
data class LawEncounter(
    val startedMs: Long,
    val lastSeenMs: Long,
    val signatures: List<LawSignature>,
    val active: Boolean,
) {
    val strongest: LawSignature? get() = signatures.maxByOrNull { it.lastRssi }
    val hasPriority: Boolean get() = signatures.any { it.threat == Threat.PRIORITY }
}

/** What the app should do after a law-enforcement hit or a periodic tick. */
enum class LawAction {
    /** Nothing new: no notification change needed. */
    NONE,
    /** Update the live notification silently (count / strength / last-seen changed). */
    UPDATE,
    /** Alert loudly: a new encounter started, or a stronger class of signature appeared. */
    ALERT,
    /** The encounter has gone quiet: finalise the notification and log it. */
    ENDED,
}

/**
 * Groups law-enforcement signature hits into *encounters*, so that a single police vehicle
 * carrying several radios (body cameras, a holster sensor, a router, a handheld radio) produces
 * one loud alert and one live, updating notification instead of an alert per device.
 *
 * Policy:
 *  - The first hit with no active encounter starts one → [LawAction.ALERT].
 *  - Within an encounter, a newly heard PRIORITY-tier signature (e.g. an Axon camera after only a
 *    vehicle router) re-alerts, at most once per [realertCooldownMs] → [LawAction.ALERT].
 *  - Other hits refresh the live notification at most every [updateIntervalMs] → [LawAction.UPDATE].
 *  - After [quietEndMs] with no hits, [tick] ends the encounter once → [LawAction.ENDED].
 *
 * Signatures are heuristic (vendor identifiers); callers must word alerts as "possible".
 */
class LawEncounterTracker(
    private val quietEndMs: Long = 120_000L,
    private val realertCooldownMs: Long = 60_000L,
    private val updateIntervalMs: Long = 5_000L,
) {
    private val signatures = LinkedHashMap<String, LawSignature>()
    private var startedMs = 0L
    private var lastHitMs = 0L
    private var lastAlertMs = 0L
    private var lastUpdateMs = 0L
    private var active = false
    private var hasEncounter = false

    @Synchronized
    fun onHit(deviceId: String, label: String, threat: Threat, rssi: Int, nowMs: Long): LawAction {
        val isNewEncounter = !active
        if (isNewEncounter) {
            signatures.clear()
            startedMs = nowMs
            active = true
            hasEncounter = true
        }
        lastHitMs = nowMs
        val previous = signatures[deviceId]
        val newlyHeard = previous == null
        signatures[deviceId] = LawSignature(
            deviceId, label, threat,
            firstSeenMs = previous?.firstSeenMs ?: nowMs,
            lastSeenMs = nowMs,
            lastRssi = rssi,
            maxRssi = maxOf(previous?.maxRssi ?: rssi, rssi),
        )
        val escalation = newlyHeard && threat == Threat.PRIORITY &&
            signatures.values.none { it.deviceId != deviceId && it.threat == Threat.PRIORITY }
        return when {
            isNewEncounter -> { lastAlertMs = nowMs; lastUpdateMs = nowMs; LawAction.ALERT }
            escalation && nowMs - lastAlertMs >= realertCooldownMs -> { lastAlertMs = nowMs; lastUpdateMs = nowMs; LawAction.ALERT }
            newlyHeard || nowMs - lastUpdateMs >= updateIntervalMs -> { lastUpdateMs = nowMs; LawAction.UPDATE }
            else -> LawAction.NONE
        }
    }

    /** Call periodically; ends the encounter after [quietEndMs] without hits. */
    @Synchronized
    fun tick(nowMs: Long): LawAction {
        if (!active) return LawAction.NONE
        if (nowMs - lastHitMs >= quietEndMs) {
            active = false
            return LawAction.ENDED
        }
        return if (nowMs - lastUpdateMs >= updateIntervalMs) { lastUpdateMs = nowMs; LawAction.UPDATE } else LawAction.NONE
    }

    @Synchronized
    fun snapshot(): LawEncounter? = if (!hasEncounter) null else
        LawEncounter(startedMs, lastHitMs, signatures.values.sortedByDescending { it.lastRssi }, active)

    @Synchronized
    fun reset() {
        signatures.clear(); startedMs = 0L; lastHitMs = 0L; lastAlertMs = 0L; lastUpdateMs = 0L; active = false; hasEncounter = false
    }
}
