package com.rfwatch.core.id

/**
 * Finds human-readable text inside binary advert payloads ("acme.BeaconID", a device name
 * spilled into manufacturer data). Random bytes produce short printable runs often (any byte
 * in 0x20..0x7E is ~37 % likely), so a run must be ≥ [MIN_RUN] characters and look like words:
 * at least four letters and no more than two punctuation characters.
 */
object PayloadText {

    const val MIN_RUN = 6

    fun find(bytes: ByteArray): List<String> {
        val out = ArrayList<String>(1)
        val run = StringBuilder()
        fun flush() {
            if (run.length >= MIN_RUN && looksLikeText(run)) out += run.toString().trim()
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
        }
        flush()
        return out
    }

    private fun looksLikeText(s: CharSequence): Boolean {
        val letters = s.count { it.isLetter() }
        val punct = s.count { !it.isLetterOrDigit() && it != ' ' && it != '.' && it != '-' && it != '_' && it != '\'' }
        return letters >= 4 && punct <= 2 && letters * 2 >= s.length
    }
}
