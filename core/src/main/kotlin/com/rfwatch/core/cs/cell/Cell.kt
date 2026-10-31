package com.rfwatch.core.cs.cell

/** Cellular radio access technology, ordered oldest→newest for downgrade detection. */
enum class RadioType(val generation: Int) {
    GSM(2), CDMA(2), WCDMA(3), LTE(4), NR(5), UNKNOWN(0)
}

/**
 * One observed cell (serving or neighbour) at a point in time. Fields mirror what Android's
 * `CellInfo`/`CellIdentity` exposes to a non-rooted app; any may be null when the modem
 * does not report it.
 *
 * @param areaCode LAC (GSM/WCDMA) or TAC (LTE/NR).
 * @param cellId CID / CI — the global cell identity.
 * @param pci physical cell id (LTE/NR only).
 * @param channel ARFCN / EARFCN / NR-ARFCN (the radio channel).
 */
data class CellObservation(
    val radio: RadioType,
    val mcc: Int?,
    val mnc: Int?,
    val areaCode: Int?,
    val cellId: Long?,
    val pci: Int?,
    val channel: Int?,
    val signalDbm: Int,
    val isServing: Boolean,
    val isRegistered: Boolean,
    val timestampMs: Long,
) {
    /** Identity used for "have I seen this cell before" comparisons. */
    fun key(): String = "${radio.name}:$mcc:$mnc:$areaCode:$cellId"
}

/** A single reason the current serving cell looks suspicious. */
enum class CellFindingType {
    DOWNGRADE_2G,        // forced onto 2G after seeing newer RATs — classic catcher move
    LONE_CELL,           // serving cell with no neighbours where neighbours are normal
    UNKNOWN_CELL,        // serving cell never seen here before (after a learning period)
    AREA_INCONSISTENT,   // same cell id now advertises a different LAC/TAC
    RAPID_RESELECTION,   // serving cell changing far faster than movement explains
    SIGNAL_ANOMALY,      // implausibly strong signal (catcher is physically close)
}

data class CellFinding(val type: CellFindingType, val weight: Double, val detail: String)

enum class CellThreatLevel { NONE, LOW, MEDIUM, HIGH }

data class CellAssessment(
    val level: CellThreatLevel,
    val score: Double,
    val findings: List<CellFinding>,
    val servingSummary: String,
    val learning: Boolean,
)
