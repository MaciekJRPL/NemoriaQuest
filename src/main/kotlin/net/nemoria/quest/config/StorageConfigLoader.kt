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
        val backend = BackendType.valueOf((cfg.getString("backend") ?: "SQLITE").uppercase())
        val sqliteFile = cfg.getString("sqlite.file") ?: "plugins/NemoriaQuest/nemoriaquest.db"
        val mysqlHost = cfg.getString("mysql.host") ?: "localhost"
        val mysqlPort = cfg.getInt("mysql.port", 3306)
        val mysqlDb = cfg.getString("mysql.database") ?: "nemoriaquest"
        val mysqlUser = cfg.getString("mysql.user") ?: "root"
        val mysqlPass = cfg.getString("mysql.password") ?: ""
        val mysqlUseSSL = cfg.getBoolean("mysql.useSSL", false)
        val maxPool = cfg.getInt("pool.maximumPoolSize", 5)
        val minIdle = cfg.getInt("pool.minimumIdle", 1)
        val timeout = cfg.getLong("pool.connectionTimeoutMs", 10_000)
        return StorageConfig(
            backend = backend,
            sqliteFile = sqliteFile,
            mysqlHost = mysqlHost,
            mysqlPort = mysqlPort,
            mysqlDatabase = mysqlDb,
            mysqlUser = mysqlUser,
            mysqlPassword = mysqlPass,
            mysqlUseSSL = mysqlUseSSL,
            maxPoolSize = maxPool,
            minIdle = minIdle,
            connectionTimeoutMs = timeout
        )
    }
}
