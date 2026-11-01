package com.rfwatch.core.cs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How a detected near-ultrasonic tone behaved over the recording. */
enum class ToneKind {
    /** On/off bursts or several tones taking turns — how data beacons (ad/TV/retail tracking) transmit. */
    BEACON_LIKE,
    /** Present nearly all the time at one pitch — usually electronics (chargers, LED drivers, TVs). */
    STEADY,
    /** Seen only briefly — not enough to call. */
    BRIEF,
}

data class UltrasonicTone(
    val freqHz: Double,
    /** Peak level above the band's noise floor. */
    val snrDb: Double,
    /** Share of analysed frames the tone was present in. */
    val presence: Double,
    /** Number of separate on-bursts. */
    val bursts: Int,
    val kind: ToneKind,
)

data class UltrasonicResult(
    val tones: List<UltrasonicTone>,
    val frames: Int,
    val sampleRate: Int,
    val bandLowHz: Double,
    val bandHighHz: Double,
    /** Median level in the ultrasonic band and in the 1–8 kHz speech band (dB, relative). */
    val bandFloorDb: Double,
    val speechFloorDb: Double,
    /** Distinct tones that alternate with each other (FSK-style data). */
    val alternatingGroup: Boolean,
) {
    val beaconLike get() = tones.any { it.kind == ToneKind.BEACON_LIKE }

    /**
     * Phone mics often roll off above ~18 kHz. When the ultrasonic band sits far below the speech
     * band the mic may not hear it at all, so "nothing found" would be meaningless.
     */
    val bandMaybeDeaf get() = speechFloorDb - bandFloorDb > 45.0

    fun summary(): String = buildString {
        append(
            when {
                beaconLike -> "Beacon-like ultrasonic signal found"
                tones.any { it.kind == ToneKind.STEADY } -> "Steady ultrasonic tone only (usually electronics)"
                tones.isNotEmpty() -> "Brief ultrasonic blips only"
                else -> "No ultrasonic tones"
            },
        )
        append(" · ${"%.1f".format(bandLowHz / 1000)}–${"%.1f".format(bandHighHz / 1000)} kHz, ${frames} frames @ ${sampleRate} Hz")
        if (bandMaybeDeaf) append(" · mic may not hear this band well")
        tones.forEach {
            append(" | ${"%.0f".format(it.freqHz)} Hz ${it.kind} +${"%.0f".format(it.snrDb)} dB, ${"%.0f".format(it.presence * 100)}% of time, ${it.bursts} burst(s)")
        }
        if (alternatingGroup) append(" | tones alternate (data-like)")
    }
}

/**
 * Finds near-ultrasonic tones (default 17–22 kHz) in mono PCM audio: Hann-windowed FFT frames
 * with 50 % overlap, a per-frame noise floor (median of the band), narrow peaks at least
 * [MIN_SNR_DB] above it, then tracks each pitch across frames to tell bursty beacon-like signals
 * from steady electrical whine. Pure Kotlin, no Android dependencies.
 */
class UltrasonicDetector(
    private val sampleRate: Int,
    private val fftSize: Int = 4096,
    bandLowHz: Double = 17_000.0,
    bandHighHz: Double = 22_000.0,
) {
    val bandLow = bandLowHz
    val bandHigh = min(bandHighHz, sampleRate / 2.0 - 500.0)

    private val hop = fftSize / 2
    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1)) }
    private val binHz = sampleRate.toDouble() / fftSize
    private val lowBin = (bandLow / binHz).toInt()
    private val highBin = (bandHigh / binHz).toInt()
    private val speechLow = (1_000.0 / binHz).toInt()
    private val speechHigh = (8_000.0 / binHz).toInt()

    /** Per-frame peaks: frame index → list of (bin, snrDb). */
    private val framePeaks = ArrayList<List<Pair<Int, Double>>>()
    private val bandFloors = ArrayList<Double>()
    private val speechFloors = ArrayList<Double>()
    private val buf = FloatArray(fftSize)
    private var filled = 0

    /** Feed samples (−1..1). Can be called repeatedly with consecutive chunks. */
    fun feed(samples: FloatArray, count: Int = samples.size) {
        var i = 0
        while (i < count) {
            val n = min(count - i, fftSize - filled)
            System.arraycopy(samples, i, buf, filled, n)
            filled += n; i += n
            if (filled == fftSize) {
                analyseFrame(buf)
                System.arraycopy(buf, hop, buf, 0, fftSize - hop)
                filled = fftSize - hop
            }
        }
    }

    fun feedPcm16(buf: ShortArray, count: Int) = feed(FloatArray(count) { buf[it] / 32768f })

    private fun analyseFrame(x: FloatArray) {
        val re = DoubleArray(fftSize) { x[it] * window[it] }
        val im = DoubleArray(fftSize)
        fft(re, im)
        val mag = DoubleArray(fftSize / 2) { 10 * log10(re[it] * re[it] + im[it] * im[it] + 1e-20) }
        val band = (lowBin..highBin).map { mag[it] }.sorted()
        val floor = band[band.size / 2]
        bandFloors += floor
        speechFloors += (speechLow..speechHigh).map { mag[it] }.sorted().let { it[it.size / 2] }
        val peaks = ArrayList<Pair<Int, Double>>()
        for (b in (lowBin + 2)..(highBin - 2)) {
            val v = mag[b]
            if (v - floor < MIN_SNR_DB) continue
            if (v < mag[b - 1] || v < mag[b + 1]) continue
            // Narrow: a tone, not broadband noise / a click (which lifts everything nearby).
            val side = max(mag.getOrElse(b - 6) { v }, mag.getOrElse(b + 6) { v })
            if (v - side < NARROW_DB) continue
            peaks += b to (v - floor)
        }
        framePeaks += peaks
    }

    fun result(): UltrasonicResult {
        val frames = framePeaks.size
        // Group peaks into pitch tracks (bins within ±TRACK_BINS of each other).
        data class Track(var centre: Double, val hits: MutableList<Int>, var maxSnr: Double, var sum: Double, var n: Int)
        val tracks = ArrayList<Track>()
        framePeaks.forEachIndexed { f, peaks ->
            peaks.forEach { (bin, snr) ->
                val t = tracks.firstOrNull { abs(it.centre - bin) <= TRACK_BINS }
                if (t == null) tracks += Track(bin.toDouble(), mutableListOf(f), snr, bin.toDouble(), 1)
                else {
                    if (t.hits.last() != f) t.hits += f
                    t.maxSnr = max(t.maxSnr, snr); t.sum += bin; t.n++; t.centre = t.sum / t.n
                }
            }
        }
        val minFrames = max(3, (frames * MIN_PRESENCE).toInt())
        val present = tracks.filter { it.hits.size >= minFrames }
        // A loud tone drags faint flickering side tones along (speaker / amplifier distortion, AGC);
        // those are part of it, not separate beacons. Measured on a ZTE next to a laptop speaker:
        // a +65 dB steady 19 kHz tone came with +19–23 dB spurs within ±0.5 kHz.
        val real = present.filter { t ->
            present.none { o -> o !== t && abs(o.centre - t.centre) * binHz <= SIDEBAND_HZ && o.maxSnr - t.maxSnr >= SIDEBAND_DB }
        }
        val tones = real.map { t ->
            val presence = t.hits.size.toDouble() / max(1, frames)
            var bursts = 1
            for (i in 1 until t.hits.size) if (t.hits[i] - t.hits[i - 1] > GAP_FRAMES) bursts++
            val kind = when {
                presence >= STEADY_PRESENCE && bursts <= 2 -> ToneKind.STEADY
                bursts >= MIN_BURSTS -> ToneKind.BEACON_LIKE
                else -> ToneKind.BRIEF
            }
            UltrasonicTone(t.centre * binHz, t.maxSnr, presence, bursts, kind)
        }.sortedByDescending { it.snrDb }

        // FSK-style: two or more non-steady tones that mostly don't sound in the same frames.
        val nonSteady = real.filter { it.hits.size.toDouble() / max(1, frames) < STEADY_PRESENCE }
        var alternating = false
        for (i in nonSteady.indices) for (j in i + 1 until nonSteady.size) {
            val a = nonSteady[i].hits.toSet(); val b = nonSteady[j].hits.toSet()
            val overlap = a.intersect(b).size.toDouble() / min(a.size, b.size)
            if (overlap < 0.3) alternating = true
        }
        val finalTones = if (alternating) tones.map { if (it.kind == ToneKind.BRIEF || it.kind == ToneKind.BEACON_LIKE) it.copy(kind = ToneKind.BEACON_LIKE) else it } else tones
        return UltrasonicResult(
            finalTones, frames, sampleRate, bandLow, bandHigh,
            bandFloors.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] },
            speechFloors.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] },
            alternating,
        )
    }

    companion object {
        const val MIN_SNR_DB = 15.0
        const val NARROW_DB = 8.0
        const val TRACK_BINS = 3
        /** A tone must be in at least this share of frames (or 3 frames) to count at all. */
        const val MIN_PRESENCE = 0.04
        const val STEADY_PRESENCE = 0.9
        /** Frames of silence that split one burst from the next (~0.17 s at 48 kHz / 4096 / 50 %). */
        const val GAP_FRAMES = 2
        const val MIN_BURSTS = 3
        /** Tones this much weaker than a neighbour within [SIDEBAND_HZ] are treated as its side products. */
        const val SIDEBAND_DB = 25.0
        const val SIDEBAND_HZ = 1_500.0

        /** In-place iterative radix-2 FFT. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ur = re[i + k]; val ui = im[i + k]
                        val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k] = ur + vr; im[i + k] = ui + vi
                        re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                        val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
