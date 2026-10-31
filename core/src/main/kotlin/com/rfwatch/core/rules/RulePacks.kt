package com.rfwatch.core.rules

import kotlinx.serialization.json.Json

/**
 * Loads the bundled rule packs. The JSON lives in `core` resources so both the app and
 * the core unit tests load exactly the same packs. Each pack is independently toggleable
 * by the user; [loadBuiltin] returns them all and the caller filters by enabled state.
 */
object RulePacks {

    val BUILTIN_IDS = listOf("law_enforcement", "trackers", "cameras_iot", "fleet", "emergency_services", "attack_tools")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(text: String): RulePack = json.decodeFromString(RulePack.serializer(), text)

    fun loadBuiltin(): List<RulePack> = BUILTIN_IDS.mapNotNull { id ->
        RulePacks::class.java.getResourceAsStream("/rules/$id.json")
            ?.bufferedReader()?.use { it.readText() }
            ?.let { parse(it) }
    }
}
