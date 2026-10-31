package com.rfwatch.core.cs.cell

/**
 * Heuristic IMSI-catcher ("stingray") detector working only from the cell information a
 * **non-rooted** Android app can read (`TelephonyManager` / `CellInfo`): serving + neighbour
 * cells, radio type, area codes, cell ids and signal strength.
 *
 * HONEST LIMITS — state these in the UI:
 *  - This cannot see the baseband or layer-3 signalling that SnoopSnitch (root + Qualcomm)
 *    uses, so it cannot observe silent SMS, cipher/authentication downgrades or paging.
 *  - It is a set of behavioural heuristics. Legitimate events — roaming, travelling onto a
 *    new tower, a genuine 2G fallback in a coverage hole, a femtocell — can trip them, and
 *    a careful catcher that mimics the real network can evade them. No app reliably detects
 *    every catcher; treat HIGH as "worth looking into", not proof.
 *
 * Heuristics (each contributes a weight; the sum maps to a threat level):
 *  - DOWNGRADE_2G: now camped on GSM after this session has seen LTE/NR here.
 *  - LONE_CELL: serving cell reports no neighbours where neighbours are normally present.
 *  - UNKNOWN_CELL: serving cell never seen before, once past the learning window.
 *  - AREA_INCONSISTENT: a known cell id suddenly advertises a different LAC/TAC.
 *  - RAPID_RESELECTION: serving cell changes far more often than movement explains.
 *  - SIGNAL_ANOMALY: serving signal implausibly strong (the catcher is in the room/car).
 */
class StingrayDetector(
    private val learningScans: Int = 20,
    private val strongSignalDbm: Int = -51,
    private val reselectionWindowMs: Long = 60_000,
    private val reselectionLimit: Int = 4,
) {
    private val knownCells = HashSet<String>()
    private val areaByCellId = HashMap<Long, Int>()
    private var highestGenSeen = 0
    private var scanCount = 0
    private var lastServingKey: String? = null
    private val reselectionTimes = ArrayDeque<Long>()
    // Rolling record of neighbour counts to learn what "normal" looks like here.
    private val neighborHistory = ArrayDeque<Int>()

    fun analyze(scan: List<CellObservation>, nowMs: Long): CellAssessment {
        scanCount++
        val learning = scanCount <= learningScans
        val serving = scan.firstOrNull { it.isServing }
            ?: return CellAssessment(CellThreatLevel.NONE, 0.0, emptyList(), "no serving cell", learning)
        val neighbors = scan.filter { !it.isServing }
        val findings = ArrayList<CellFinding>()

        // Track reselection cadence.
        val servingKey = serving.key()
        if (lastServingKey != null && lastServingKey != servingKey) {
            reselectionTimes.addLast(nowMs)
        }
        while (reselectionTimes.isNotEmpty() && nowMs - reselectionTimes.first() > reselectionWindowMs) {
            reselectionTimes.removeFirst()
        }

        // 1. 2G downgrade.
        if (serving.radio.generation == 2 && highestGenSeen >= 4) {
            findings.add(CellFinding(CellFindingType.DOWNGRADE_2G, 0.5,
                "Camped on ${serving.radio.name} after ${genName(highestGenSeen)} was available here"))
        }

        // 2. Lone cell — no neighbours where neighbours are usually present.
        val typicalNeighbors = if (neighborHistory.isEmpty()) 0.0 else neighborHistory.average()
        if (neighbors.isEmpty() && (typicalNeighbors >= 1.0 || serving.signalDbm >= strongSignalDbm)) {
            findings.add(CellFinding(CellFindingType.LONE_CELL, 0.3,
                "Serving cell reports no neighbours (usually ~${"%.1f".format(typicalNeighbors)})"))
        }

        // 3. Unknown cell (only once we've learned the area).
        if (!learning && servingKey !in knownCells) {
            findings.add(CellFinding(CellFindingType.UNKNOWN_CELL, 0.25,
                "Serving cell ${serving.cellId} not seen here before"))
        }

        // 4. Area-code inconsistency for a known cell id.
        val cid = serving.cellId
        val area = serving.areaCode
        if (cid != null && area != null) {
            val prevArea = areaByCellId[cid]
            if (prevArea != null && prevArea != area) {
                findings.add(CellFinding(CellFindingType.AREA_INCONSISTENT, 0.4,
                    "Cell $cid changed area code $prevArea → $area"))
            }
        }

        // 5. Rapid reselection.
        if (reselectionTimes.size >= reselectionLimit) {
            findings.add(CellFinding(CellFindingType.RAPID_RESELECTION, 0.3,
                "${reselectionTimes.size} cell changes in ${reselectionWindowMs / 1000}s"))
        }

        // 6. Signal anomaly.
        if (serving.signalDbm >= strongSignalDbm) {
            findings.add(CellFinding(CellFindingType.SIGNAL_ANOMALY, 0.3,
                "Serving signal ${serving.signalDbm} dBm is implausibly strong"))
        }

        val score = combine(findings)
        val level = when {
            learning -> CellThreatLevel.NONE
            score >= 0.7 -> CellThreatLevel.HIGH
            score >= 0.4 -> CellThreatLevel.MEDIUM
            score > 0.0 -> CellThreatLevel.LOW
            else -> CellThreatLevel.NONE
        }

        // Update learned state AFTER assessing this scan.
        knownCells.add(servingKey)
        neighbors.forEach { knownCells.add(it.key()) }
        if (cid != null && area != null) areaByCellId[cid] = area
        highestGenSeen = maxOf(highestGenSeen, serving.radio.generation,
            neighbors.maxOfOrNull { it.radio.generation } ?: 0)
        neighborHistory.addLast(neighbors.size)
        if (neighborHistory.size > 50) neighborHistory.removeFirst()
        lastServingKey = servingKey

        return CellAssessment(
            level = level,
            score = score,
            findings = findings,
            servingSummary = "${serving.radio.name} cell ${serving.cellId} / area ${serving.areaCode} @ ${serving.signalDbm} dBm, ${neighbors.size} neighbours",
            learning = learning,
        )
    }

    /** Combine finding weights with diminishing returns so one weak signal never reads HIGH. */
    private fun combine(findings: List<CellFinding>): Double {
        var remaining = 1.0
        for (f in findings.sortedByDescending { it.weight }) {
            remaining *= (1.0 - f.weight.coerceIn(0.0, 1.0))
        }
        return 1.0 - remaining
    }

    private fun genName(gen: Int) = when (gen) {
        5 -> "5G"; 4 -> "LTE"; 3 -> "3G"; 2 -> "2G"; else -> "?"
    }
}
