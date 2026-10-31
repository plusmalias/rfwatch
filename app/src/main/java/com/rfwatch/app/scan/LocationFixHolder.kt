package com.rfwatch.app.scan

import com.rfwatch.core.model.GpsFix

/** Thread-safe holder for the most recent GPS fix, shared across scan collectors. */
class LocationFixHolder {
    @Volatile var current: GpsFix? = null
        private set

    fun update(fix: GpsFix) { current = fix }
}
