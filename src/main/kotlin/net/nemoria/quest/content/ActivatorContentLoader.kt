package net.nemoria.quest.content

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

object ActivatorContentLoader {
    data class CitizensNpcActivatorSpec(
        val npcs: Set<Int>,
        val particles: Map<String, String> = emptyMap(),
        val particlesVerticalOffset: Double = 0.0,
        val sneakClickCancel: Boolean = false,
        val requiredGuiQuests: Int? = null
    )

    fun loadCitizensNpcActivatorSpecs(dir: File): Map<String, CitizensNpcActivatorSpec> {
        if (!dir.exists() || !dir.isDirectory) return emptyMap()
        val files = dir.listFiles { f -> f.isFile && (f.extension.equals("yml", true) || f.extension.equals("yaml", true)) } ?: return emptyMap()
        val result = mutableMapOf<String, CitizensNpcActivatorSpec>()
        for (file in files) {
            val cfg = runCatching { YamlConfiguration.loadConfiguration(file) }.getOrNull() ?: continue
            val type = cfg.getString("type")?.trim()?.uppercase() ?: continue
            if (type != "CITIZENS_NPCS") continue
            val id = cfg.getString("id")?.trim()?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            val raw = cfg.get("npcs")
            val npcs = when (raw) {
                is List<*> -> raw.mapNotNull { it?.toString()?.toIntOrNull() }.toSet()
                is String -> raw.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
                else -> cfg.getIntegerList("npcs").map { it.toInt() }.toSet()
            }.filter { it >= 0 }.toSet()
            if (npcs.isEmpty()) continue

            val particlesSec = cfg.getConfigurationSection("particles")
            val particles = particlesSec?.getKeys(false)?.associate { key ->
                key.uppercase() to (particlesSec.getString(key)?.trim().orEmpty())
            }?.filterValues { it.isNotBlank() } ?: emptyMap()

            val particlesVerticalOffset = cfg.getConfigurationSection("particles_location")?.let { sec ->
                if (sec.contains("vertical_offset")) sec.getDouble("vertical_offset") else 0.0
            } ?: 0.0

            val sneakClickCancel = cfg.getBoolean("sneak_click_cancel", false)
            val requiredGuiQuests = if (cfg.contains("required_gui_quests")) cfg.getInt("required_gui_quests") else null

            result[id] = CitizensNpcActivatorSpec(
                npcs = npcs,
                particles = particles,
                particlesVerticalOffset = particlesVerticalOffset,
                sneakClickCancel = sneakClickCancel,
                requiredGuiQuests = requiredGuiQuests
            )
        }
        return result
    }

    fun loadCitizensNpcActivators(dir: File): Map<String, Set<Int>> {
        return loadCitizensNpcActivatorSpecs(dir).mapValues { it.value.npcs }
    }
}
