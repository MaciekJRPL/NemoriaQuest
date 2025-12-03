package net.nemoria.quest.core

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader

class I18n(private val locale: String, private val fallback: String = "en_US") {
    private val messages: Map<String, String>
    private val primaryCfg: YamlConfiguration?
    private val fallbackCfg: YamlConfiguration?

    init {
        val primary = loadBundle("texts/$locale/messages.yml")
        val fb = if (locale != fallback) loadBundle("texts/$fallback/messages.yml") else null
        val primaryMap = primary?.let { flatten(it, "") } ?: emptyMap()
        val fbMap = fb?.let { flatten(it, "") } ?: emptyMap()
        messages = fbMap + primaryMap
        primaryCfg = primary
        fallbackCfg = fb
    }

    fun msg(key: String, placeholders: Map<String, String> = emptyMap()): String {
        val raw = messages[key] ?: key
        return placeholders.entries.fold(raw) { acc, e -> acc.replace("{${e.key}}", e.value) }
    }

    fun msgList(key: String): List<String> {
        val primaryList = primaryCfg?.getStringList(key)?.takeIf { it.isNotEmpty() }
        if (primaryList != null) return primaryList
        val fallbackList = fallbackCfg?.getStringList(key)?.takeIf { it.isNotEmpty() }
        return fallbackList ?: emptyList()
    }

    private fun loadBundle(path: String): YamlConfiguration? {
        // 1) Spróbuj z pliku w katalogu danych pluginu (plugins/NemoriaQuest/...).
        val dataFile = File(Services.plugin.dataFolder, path)
        if (dataFile.exists()) {
            return YamlConfiguration.loadConfiguration(dataFile)
        }
        // 2) Jeśli brak pliku zewnętrznego, użyj zasobu z JAR.
        val stream = this::class.java.classLoader.getResourceAsStream(path) ?: return null
        val cfg = YamlConfiguration()
        cfg.load(InputStreamReader(stream, Charsets.UTF_8))
        return cfg
    }

    private fun flatten(cfg: YamlConfiguration, prefix: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (key in cfg.getKeys(false)) {
            val full = if (prefix.isEmpty()) key else "$prefix.$key"
            val section = cfg.getConfigurationSection(key)
            if (section != null) {
                map.putAll(flatten(section, full))
            } else {
                map[full] = cfg.getString(key, full) ?: full
            }
        }
        return map
    }

    // Overload to support recursion on sections
    private fun flatten(section: org.bukkit.configuration.ConfigurationSection, prefix: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (key in section.getKeys(false)) {
            val full = "$prefix.$key"
            val sub = section.getConfigurationSection(key)
            if (sub != null) {
                map.putAll(flatten(sub, full))
            } else {
                map[full] = section.getString(key, full) ?: full
            }
        }
        return map
    }
}
