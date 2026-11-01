package com.rfwatch.app.repo

import com.rfwatch.core.df.RssiCentroidLocator
import com.rfwatch.core.geo.LocalFrame
import com.rfwatch.core.model.RadioObservation
import kotlin.math.hypot

/**
 * Debug diagnostic (logcat tag RFWatchRadar): why Radar devices do or don't get a dot. For every
 * device id the Radar asked about, reports which gate failed with the current window and what
 * would happen with a longer window and/or only good GPS fixes. Counts and distances only — never
 * coordinates — so the log is safe to share.
 */
object RadarReport {

    enum class Gate { CAP, FEW, SPREAD, ACCURACY, LOCATED }

    data class Row(
        val label: String, val band: String, val n: Int, val spanS: Long, val spreadM: Double,
        val medAccM: Double, val pctPoor: Int, val gate: Gate, val dayGate: Gate, val dayGoodGate: Gate,
    )

    /** Same decision the Radar makes (via [RssiCentroidLocator]). */
    fun gate(obs: List<RadioObservation>): Gate {
        val est = RssiCentroidLocator.estimate(obs) ?: return Gate.FEW
        if (est.directional) return Gate.LOCATED
        return if (est.spreadM < RssiCentroidLocator.MIN_SPREAD_M) Gate.SPREAD else Gate.ACCURACY
    }

    fun spread(geo: List<RadioObservation>): Double {
        if (geo.isEmpty()) return 0.0
        val f = LocalFrame(geo.first().fix!!.lat, geo.first().fix!!.lon)
        val e = geo.map { val fx = it.fix!!; f.toEnu(fx.lat, fx.lon) }
        return hypot(e.maxOf { it.east } - e.minOf { it.east }, e.maxOf { it.north } - e.minOf { it.north })
    }

    fun row(label: String, band: String, capped: Boolean, window: List<RadioObservation>, day: List<RadioObservation>, goodAccM: Double): Row {
        val geo = window.filter { it.fix != null }
        val accs = geo.map { it.fix!!.accuracyM }.sorted()
        return Row(
            label, band, geo.size,
            if (geo.isEmpty()) 0 else (geo.maxOf { it.timestampMs } - geo.minOf { it.timestampMs }) / 1000,
            spread(geo), if (accs.isEmpty()) 0.0 else accs[accs.size / 2],
            if (accs.isEmpty()) 0 else accs.count { it > goodAccM } * 100 / accs.size,
            if (capped) Gate.CAP else gate(window), gate(day), gate(day.filter { (it.fix?.accuracyM ?: 999.0) <= goodAccM }),
        )
    }

    fun lines(rows: List<Row>, trackAcc: List<Double>, goodAccM: Double): List<String> = buildList {
        val sorted = trackAcc.sorted()
        if (sorted.isNotEmpty()) {
            add("TRACK today: ${sorted.size} geo sightings; accuracy median ${sorted[sorted.size / 2].toInt()} m, p90 ${sorted[sorted.size * 9 / 10].toInt()} m; " +
                "${sorted.count { it > goodAccM } * 100 / sorted.size}% worse than ${goodAccM.toInt()} m")
        }
        fun tally(sel: (Row) -> Gate) = Gate.entries.joinToString(" ") { g -> "$g=${rows.count { sel(it) == g }}" }
        add("RADAR ${rows.size} ids | now: ${tally { it.gate }}")
        add("  if whole day: ${tally { it.dayGate }}")
        add("  if whole day + only fixes <= ${goodAccM.toInt()} m: ${tally { it.dayGoodGate }}")
        rows.take(40).forEach {
            add("  ${it.band} '${it.label}' n=${it.n} span=${it.spanS}s spread=${it.spreadM.toInt()}m medAcc=${it.medAccM.toInt()}m poor=${it.pctPoor}% now=${it.gate} day=${it.dayGate} dayGood=${it.dayGoodGate}")
        }
    }
}
