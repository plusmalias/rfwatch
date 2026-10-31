package com.rfwatch.app.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.rfwatch.app.BuildConfig
import com.rfwatch.app.data.AlertEntity
import com.rfwatch.app.data.DeviceEntity
import com.rfwatch.app.data.EventEntity
import com.rfwatch.app.data.ObservationEntity
import com.rfwatch.app.data.RfWatchDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a user-triggered evidence report — an HTML document (open it in a browser and "Print →
 * Save as PDF") plus a CSV of raw sightings — and returns a share-sheet intent for it. Files go to
 * the app cache `exports/` folder only; nothing is uploaded unless the user chooses a destination.
 *
 * The report documents what the phone's radios heard, when and where. It does not prove who owns a
 * device or why it was there, and it says so.
 */
object EvidenceExporter {

    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US)
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    suspend fun export(context: Context, database: RfWatchDatabase, deviceId: String?): Intent {
        val device = deviceId?.let { database.deviceDao().byId(it) }
        val sightings = deviceId?.let { database.observationDao().forDevice(it) }.orEmpty()
        val alerts = database.alertDao().list(2_000).filter { deviceId == null || it.deviceId == deviceId }
        val events = database.eventDao().filtered(null).filter { deviceId == null || it.deviceId == deviceId }

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest export on disk
        val base = "rfwatch-evidence-" + (deviceId?.replace(":", "")?.take(12)?.let { "$it-" } ?: "") + stamp.format(Date())
        val html = File(dir, "$base.html").apply { writeText(html(device, sightings, alerts, events)) }
        val files = mutableListOf(html)
        if (sightings.isNotEmpty()) files += File(dir, "$base-sightings.csv").apply { writeText(csv(sightings)) }
        if (alerts.isNotEmpty() || events.isNotEmpty()) files += File(dir, "$base-alerts.csv").apply { writeText(alertCsv(alerts, events)) }

        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.exports", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "RFWatch evidence report" + (device?.let { " — ${label(it)}" } ?: ""))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share evidence report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun label(d: DeviceEntity) = listOfNotNull(d.displayName, d.brand, d.model ?: d.category).distinct().joinToString(" · ").ifBlank { d.id }

    private fun esc(s: String?) = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun osm(lat: Double?, lon: Double?) = if (lat == null || lon == null) "" else
        "<a href=\"https://www.openstreetmap.org/?mlat=%.6f&amp;mlon=%.6f#map=18/%.6f/%.6f\">%.6f, %.6f</a>".format(Locale.US, lat, lon, lat, lon, lat, lon)

    private fun html(device: DeviceEntity?, sightings: List<ObservationEntity>, alerts: List<AlertEntity>, events: List<EventEntity>): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">")
        append("<title>RFWatch evidence report</title><style>body{font-family:sans-serif;max-width:900px;margin:auto;padding:12px;color:#111}")
        append("table{border-collapse:collapse;width:100%;font-size:12px}td,th{border:1px solid #bbb;padding:4px;text-align:left}")
        append("th{background:#eee}.note{color:#555;font-size:12px}h2{margin-top:28px}</style></head><body>")
        append("<h1>RFWatch evidence report</h1>")
        append("<p>Generated ${esc(time.format(Date()))} by RFWatch ${esc(BuildConfig.VERSION_NAME)} on this phone. ")
        append("Times are the phone's local time; positions are the phone's own GPS fixes when it heard each signal.</p>")
        append("<p class=\"note\">This report records radio signals (Bluetooth / Wi-Fi) that this phone passively received, with time and the phone's location. ")
        append("Device types and brands are inferred from advertised identifiers and may be wrong; a signal being near you does not by itself show who owns it or why it was there. ")
        append("Keep the original phone and this file unaltered if you intend to give them to police or a lawyer.</p>")

        if (device != null) {
            append("<h2>Device</h2><table>")
            fun row(k: String, v: String?) { if (!v.isNullOrBlank()) append("<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>") }
            row("Identifier (MAC / BSSID)", device.lastMac ?: device.id)
            row("Advertised name / SSID", device.displayName)
            row("Brand (inferred)", device.brand)
            row("Type (inferred)", listOfNotNull(device.kind, device.model, device.category).distinct().joinToString(" · "))
            row("Radio", device.band)
            row("Classification", device.classification + (device.trackerType?.let { " · $it" } ?: ""))
            row("First seen", time.format(Date(device.firstSeenMs)))
            row("Last seen", time.format(Date(device.lastSeenMs)))
            row("Times heard (stored)", device.seenCount.toString())
            row("Signal range", if (device.rssiMin != null) "${device.rssiMin} … ${device.rssiMax} dBm" else null)
            row("Wi-Fi", listOfNotNull(device.wifiBand, device.wifiSecurity).joinToString(" · "))
            row("Marked by user", listOfNotNull("mine".takeIf { device.isOwnDevice }, "trusted".takeIf { device.isAllowlisted }).joinToString(", "))
            row("Raw radio details", device.radioDetails)
            append("</table>")

            val days = sightings.groupBy { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.timestampMs)) }
            append("<h2>Sightings (${sightings.size}) over ${days.size} day(s)</h2>")
            val places = sightings.filter { it.lat != null }.map { "%.3f,%.3f".format(Locale.US, it.lat, it.lon) }.toSet()
            append("<p>Heard at ${places.size} distinct ~100 m areas. Full list in the attached CSV; first 500 rows below.</p>")
            append("<table><tr><th>Time</th><th>Signal (dBm)</th><th>Phone position</th><th>GPS ±m</th></tr>")
            sightings.take(500).forEach { o ->
                append("<tr><td>${esc(time.format(Date(o.timestampMs)))}</td><td>${o.rssi}</td><td>${osm(o.lat, o.lon)}</td><td>${o.gpsAccuracyM?.toInt() ?: ""}</td></tr>")
            }
            append("</table>")
        }

        append("<h2>Alerts (${alerts.size})</h2><table><tr><th>Time</th><th>Type</th><th>Detail</th><th>Device</th></tr>")
        alerts.forEach { a ->
            append("<tr><td>${esc(time.format(Date(a.timestampMs)))}</td><td>${esc(a.type)}</td><td>${esc(a.message)}</td><td>${esc(a.deviceId)}</td></tr>")
        }
        append("</table><h2>Events (${events.size})</h2><table><tr><th>Time</th><th>Kind</th><th>Detail</th><th>Position</th></tr>")
        events.take(1_000).forEach { e ->
            append("<tr><td>${esc(time.format(Date(e.timestampMs)))}</td><td>${esc(e.kind)}</td><td>${esc(e.detail)}</td><td>${osm(e.lat, e.lon)}</td></tr>")
        }
        append("</table></body></html>")
    }

    private fun csvField(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""

    private fun csv(rows: List<ObservationEntity>) = buildString {
        append("time_local,time_unix_ms,device_id,band,rssi_dbm,phone_lat,phone_lon,gps_accuracy_m\n")
        rows.forEach { o ->
            append(listOf(csvField(time.format(Date(o.timestampMs))), o.timestampMs, csvField(o.deviceId), o.band, o.rssi, o.lat ?: "", o.lon ?: "", o.gpsAccuracyM ?: "").joinToString(","))
            append('\n')
        }
    }

    private fun alertCsv(alerts: List<AlertEntity>, events: List<EventEntity>) = buildString {
        append("source,time_local,time_unix_ms,type,device_id,detail,lat,lon\n")
        alerts.forEach { a -> append(listOf("alert", csvField(time.format(Date(a.timestampMs))), a.timestampMs, a.type, csvField(a.deviceId), csvField(a.message), "", "").joinToString(",")).append('\n') }
        events.forEach { e -> append(listOf("event", csvField(time.format(Date(e.timestampMs))), e.timestampMs, e.kind, csvField(e.deviceId), csvField(e.detail), e.lat ?: "", e.lon ?: "").joinToString(",")).append('\n') }
    }
}
