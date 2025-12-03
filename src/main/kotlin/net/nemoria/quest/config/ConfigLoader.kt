package net.nemoria.quest.config

import org.bukkit.plugin.java.JavaPlugin

class ConfigLoader(private val plugin: JavaPlugin) {
    fun load(): CoreConfig {
        plugin.saveDefaultConfig()
        val cfg = plugin.config
        val multi = cfg.getStringList("core.multi_version").ifEmpty {
            listOf("1.21.8", "1.21.9", "1.21.10")
        }
        val locale = cfg.getString("core.locale", "en_US") ?: "en_US"
        val loggingLevel = cfg.getString("logging.level", "INFO") ?: "INFO"
        val configVersion = cfg.getString("config-version", "1.0") ?: "1.0"
        val debugEnabled = cfg.getBoolean("core.debug", false)
        val scoreboardEnabled = cfg.getBoolean("core.scoreboard_enabled", true)
        return CoreConfig(multi, locale, loggingLevel, configVersion, debugEnabled, scoreboardEnabled)
    }
}
