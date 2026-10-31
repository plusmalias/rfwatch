package com.rfwatch.app.scan

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import com.rfwatch.core.cs.cell.CellObservation
import com.rfwatch.core.cs.cell.RadioType

/**
 * Reads the serving and neighbouring cells from [TelephonyManager] and maps them to the
 * Android-free [CellObservation] that the unit-tested [com.rfwatch.core.cs.cell.StingrayDetector]
 * consumes. Requires READ_PHONE_STATE and ACCESS_FINE_LOCATION (cell identity fields are
 * location-gated on Android 10+). Non-rooted: this is all a normal app can see.
 */
class CellScanner(context: Context) {

    private val tm = context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @SuppressLint("MissingPermission") // caller gates on READ_PHONE_STATE + ACCESS_FINE_LOCATION
    fun scanOnce(nowMs: Long = System.currentTimeMillis()): List<CellObservation> {
        val infos = runCatching { tm.allCellInfo }.getOrNull() ?: return emptyList()
        return infos.mapNotNull { it.toObservation(nowMs) }
    }

    private fun CellInfo.toObservation(nowMs: Long): CellObservation? = when (this) {
        is CellInfoLte -> {
            val id = cellIdentity
            val ss = cellSignalStrength
            CellObservation(
                radio = RadioType.LTE,
                mcc = mccOf(id.mccString, @Suppress("DEPRECATION") id.mcc),
                mnc = mncOf(id.mncString, @Suppress("DEPRECATION") id.mnc),
                areaCode = id.tac.nullIfUnavailable(),
                cellId = id.ci.toLong().nullIfUnavailable(),
                pci = id.pci.nullIfUnavailable(),
                channel = id.earfcn.nullIfUnavailable(),
                signalDbm = ss.dbm,
                isServing = isRegistered,
                isRegistered = isRegistered,
                timestampMs = nowMs,
            )
        }
        is CellInfoGsm -> {
            val id = cellIdentity
            CellObservation(
                radio = RadioType.GSM,
                mcc = mccOf(id.mccString, @Suppress("DEPRECATION") id.mcc),
                mnc = mncOf(id.mncString, @Suppress("DEPRECATION") id.mnc),
                areaCode = id.lac.nullIfUnavailable(),
                cellId = id.cid.toLong().nullIfUnavailable(),
                pci = null,
                channel = id.arfcn.nullIfUnavailable(),
                signalDbm = cellSignalStrength.dbm,
                isServing = isRegistered,
                isRegistered = isRegistered,
                timestampMs = nowMs,
            )
        }
        is CellInfoWcdma -> {
            val id = cellIdentity
            CellObservation(
                radio = RadioType.WCDMA,
                mcc = mccOf(id.mccString, @Suppress("DEPRECATION") id.mcc),
                mnc = mncOf(id.mncString, @Suppress("DEPRECATION") id.mnc),
                areaCode = id.lac.nullIfUnavailable(),
                cellId = id.cid.toLong().nullIfUnavailable(),
                pci = id.psc.nullIfUnavailable(),
                channel = id.uarfcn.nullIfUnavailable(),
                signalDbm = cellSignalStrength.dbm,
                isServing = isRegistered,
                isRegistered = isRegistered,
                timestampMs = nowMs,
            )
        }
        else -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this is CellInfoNr) {
                val id = cellIdentity as? CellIdentityNr ?: return null
                val ss = cellSignalStrength as? CellSignalStrengthNr
                CellObservation(
                    radio = RadioType.NR,
                    mcc = id.mccString?.toIntOrNull(),
                    mnc = id.mncString?.toIntOrNull(),
                    areaCode = id.tac.nullIfUnavailable(),
                    cellId = id.nci.nullIfUnavailable(),
                    pci = id.pci.nullIfUnavailable(),
                    channel = id.nrarfcn.nullIfUnavailable(),
                    signalDbm = ss?.dbm ?: cellSignalStrength.dbm,
                    isServing = isRegistered,
                    isRegistered = isRegistered,
                    timestampMs = nowMs,
                )
            } else null
        }
    }

    private fun mccOf(str: String?, legacy: Int): Int? =
        str?.toIntOrNull() ?: legacy.nullIfUnavailable()

    private fun mncOf(str: String?, legacy: Int): Int? =
        str?.toIntOrNull() ?: legacy.nullIfUnavailable()

    // CellInfo reports Integer.MAX_VALUE / Long.MAX_VALUE for unknown fields.
    private fun Int.nullIfUnavailable(): Int? = if (this == Int.MAX_VALUE) null else this
    private fun Long.nullIfUnavailable(): Long? = if (this == Long.MAX_VALUE) null else this
}
