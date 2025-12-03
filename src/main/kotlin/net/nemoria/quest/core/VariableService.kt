package net.nemoria.quest.core

import net.nemoria.quest.data.repo.ServerVariableRepository
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class VariableService(
    private val plugin: JavaPlugin,
    private val serverRepo: ServerVariableRepository
) {
    private val globalVars: MutableMap<String, String> = mutableMapOf()
    private val defaultUserVars: MutableMap<String, String> = mutableMapOf()
    private val defaultServerVars: MutableMap<String, String> = mutableMapOf()
    private val serverVars: MutableMap<String, String> = mutableMapOf()

    fun load() {
        loadYaml(File(plugin.dataFolder, "global_variables.yml"), globalVars)
        loadYaml(File(plugin.dataFolder, "default_variables.yml"), defaultUserVars)
        loadYaml(File(plugin.dataFolder, "server_variables.yml"), defaultServerVars)
        serverVars.clear()
        serverVars.putAll(serverRepo.loadAll())
        // seed missing server vars from defaults
        defaultServerVars.forEach { (k, v) ->
            val key = norm(k)
            if (!serverVars.containsKey(key)) {
                serverVars[key] = v
                serverRepo.save(key, v)
            }
        }
    }

    fun global(name: String): String? = globalVars[norm(name)]

    fun defaultUser(name: String): String? = defaultUserVars[norm(name)]

    fun server(name: String): String? = serverVars[norm(name)] ?: defaultServerVars[norm(name)]

    fun setServer(name: String, value: String) {
        val key = norm(name)
        serverVars[key] = value
        serverRepo.save(key, value)
    }

    private fun norm(name: String): String = name.lowercase()

    private fun loadYaml(file: File, target: MutableMap<String, String>) {
        if (!file.exists()) return
        val cfg = YamlConfiguration.loadConfiguration(file)
        cfg.getKeys(false).forEach { key ->
            val value = cfg.getString(key) ?: return@forEach
            target[norm(key)] = value
        }
    }
}
