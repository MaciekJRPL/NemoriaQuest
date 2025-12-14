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
        DebugLog.logToFile("debug-session", "run1", "VARS", "VariableService.kt:17", "load entry", mapOf())
        loadYaml(File(plugin.dataFolder, "global_variables.yml"), globalVars)
        loadYaml(File(plugin.dataFolder, "default_variables.yml"), defaultUserVars)
        loadYaml(File(plugin.dataFolder, "server_variables.yml"), defaultServerVars)
        DebugLog.logToFile("debug-session", "run1", "VARS", "VariableService.kt:21", "load yaml loaded", mapOf("globalVarsCount" to globalVars.size, "defaultUserVarsCount" to defaultUserVars.size, "defaultServerVarsCount" to defaultServerVars.size))
        serverVars.clear()
        serverVars.putAll(serverRepo.loadAll())
        DebugLog.logToFile("debug-session", "run1", "VARS", "VariableService.kt:23", "load server vars loaded", mapOf("serverVarsCount" to serverVars.size))
        // seed missing server vars from defaults
        var seeded = 0
        defaultServerVars.forEach { (k, v) ->
            val key = norm(k)
            if (!serverVars.containsKey(key)) {
                serverVars[key] = v
                serverRepo.save(key, v)
                seeded++
            }
        }
        DebugLog.logToFile("debug-session", "run1", "VARS", "VariableService.kt:31", "load completed", mapOf("seededCount" to seeded))
    }

    fun global(name: String): String? = globalVars[norm(name)]

    fun defaultUser(name: String): String? = defaultUserVars[norm(name)]

    fun server(name: String): String? = serverVars[norm(name)] ?: defaultServerVars[norm(name)]

    fun setServer(name: String, value: String) {
        DebugLog.logToFile("debug-session", "run1", "VARS", "VariableService.kt:39", "setServer", mapOf("name" to name, "value" to value, "key" to norm(name)))
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
