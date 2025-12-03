package net.nemoria.quest.util

import org.bukkit.plugin.java.JavaPlugin
import java.io.File

object ResourceExporter {
    /**
        Kopiuje wskazane zasoby z jar do folderu pluginu, jeśli nie istnieją.
     */
    fun exportIfMissing(plugin: JavaPlugin, paths: List<String>) {
        paths.forEach { path ->
            val outFile = File(plugin.dataFolder, path)
            if (outFile.exists()) return@forEach
            outFile.parentFile?.mkdirs()
            plugin.saveResource(path, false)
        }
    }
}
