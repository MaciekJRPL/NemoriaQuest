package net.nemoria.quest.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class GuiConfigLoader(private val plugin: JavaPlugin) {
    fun load(name: String): GuiConfig {
        val file = File(plugin.dataFolder, "gui/$name.yml")
        if (!file.exists()) {
            plugin.saveResource("gui/$name.yml", false)
        }
        val cfg = YamlConfiguration.loadConfiguration(file)
        val guiName = cfg.getString("name", "<gold>NemoriaQuest") ?: "<gold>NemoriaQuest"
        val type = GuiType.fromString(cfg.getString("type"))
        val showStatus = cfg.getStringList("show_status")
        val order = cfg.getBoolean("order_quests", true)
        val sortStatus = cfg.getBoolean("sort_quests_by_status", true)
        return GuiConfig(guiName, type, showStatus, order, sortStatus)
    }
}
