package com.rfwatch.core.id

/**
 * Bundled device-model tables (resources under `/devices`, generated from the Bluetooth SIG
 * assigned numbers and community-collected model IDs — see each file's header). Loaded lazily
 * on first use; every lookup returns null when a table or entry is missing.
 */
object DeviceDb {

    data class Model(val kind: DeviceKind, val brand: String?, val name: String)
    data class Uuid16(val isSigService: Boolean, val name: String)
    data class Appearance(val category: String, val subcategory: String?)

    private val fastPair by lazy { table("fastpair.tsv") { c -> c[0].toInt(16) to Model(kind(c[1]), c[2].ifBlank { null }, c[3]) } }
    private val applePp by lazy { table("apple_pp.tsv") { c -> c[0].toInt(16) to Model(kind(c[1]), "Apple", c[2]) } }
    private val appleActions by lazy { table("apple_actions.tsv") { c -> c[0].toInt(16) to Model(kind(c[1]), "Apple", c[2]) } }
    private val samsungBuds by lazy { table("samsung.tsv") { c -> if (c[0] == "B") c[1].toInt(16) to Model(kind(c[2]), "Samsung", c[3]) else null } }
    private val samsungWatch by lazy { table("samsung.tsv") { c -> if (c[0] == "W") c[1].toInt(16) to Model(kind(c[2]), "Samsung", c[3]) else null } }
    private val uuid16 by lazy { table("uuid16.tsv") { c -> c[0].toInt(16) to Uuid16(c[1] == "S", c[2]) } }
    private val appearance by lazy { table("appearance.tsv") { c -> c[0].toInt(16) to Appearance(c[1], c.getOrNull(2)?.ifBlank { null }) } }

    fun fastPairModel(modelId: Int): Model? = fastPair[modelId]
    fun appleAudioModel(model: Int): Model? = applePp[model]
    fun appleNearbyAction(action: Int): Model? = appleActions[action]
    fun samsungBudsModel(model: Int): Model? = samsungBuds[model]
    fun samsungWatchModel(model: Int): Model? = samsungWatch[model]
    fun uuid16(uuid: Int): Uuid16? = uuid16[uuid]
    fun appearance(value: Int): Appearance? = appearance[value] ?: appearance[value and 0xFFC0]

    private fun kind(name: String): DeviceKind = runCatching { DeviceKind.valueOf(name) }.getOrDefault(DeviceKind.UNKNOWN)

    private fun <V> table(file: String, parse: (List<String>) -> Pair<Int, V>?): Map<Int, V> {
        val out = HashMap<Int, V>()
        DeviceDb::class.java.getResourceAsStream("/devices/$file")?.bufferedReader()?.useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                runCatching { parse(line.split('\t')) }.getOrNull()?.let { (k, v) -> out.putIfAbsent(k, v) }
            }
        }
        return out
    }
}
