package net.nemoria.quest.config

import net.nemoria.quest.core.Services
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class ScoreboardConfigLoader(private val plugin: org.bukkit.plugin.java.JavaPlugin) {
    fun load(): ScoreboardConfig {
        val file = File(plugin.dataFolder, "scoreboard.yml")
        if (!file.exists()) {
            plugin.saveResource("scoreboard.yml", false)
        }
        val cfg = YamlConfiguration.loadConfiguration(file)
        return ScoreboardConfig(
            enabled = cfg.getBoolean("enabled", true),
            title = cfg.getString("title") ?: "<primary>NemoriaQuest",
            emptyLines = cfg.getStringList("empty_lines").ifEmpty { listOf("<secondary>Nothing to display") },
            activeLines = cfg.getStringList("active_lines").ifEmpty { listOf("<primary>{quest_name}", "<secondary>{objective_detail}") },
            maxTitleLength = cfg.getInt("max_title_length", 32),
            maxLineLength = cfg.getInt("max_line_length", 40)
        )
    }
}
