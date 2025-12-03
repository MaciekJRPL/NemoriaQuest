package net.nemoria.quest.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class StorageConfigLoader(private val plugin: JavaPlugin) {
    fun load(): StorageConfig {
        if (!File(plugin.dataFolder, "storage.yml").exists()) {
            plugin.saveResource("storage.yml", false)
        }
        val file = File(plugin.dataFolder, "storage.yml")
        val cfg = YamlConfiguration.loadConfiguration(file)
        val backend = BackendType.valueOf(cfg.getString("backend", "SQLITE")!!.uppercase())
        val sqliteFile = cfg.getString("sqlite.file", "plugins/NemoriaQuest/nemoriaquest.db")!!
        val maxPool = cfg.getInt("pool.maximumPoolSize", 5)
        val minIdle = cfg.getInt("pool.minimumIdle", 1)
        val timeout = cfg.getLong("pool.connectionTimeoutMs", 10_000)
        return StorageConfig(backend, sqliteFile, maxPool, minIdle, timeout)
    }
}
