package net.nemoria.quest.core

import com.google.gson.Gson
import java.io.File
import java.io.FileWriter
import org.bukkit.plugin.java.JavaPlugin

object DebugLog {
    @Volatile
    var enabled: Boolean = false
    @Volatile
    var debugToLogEnabled: Boolean = false
    private val gson = Gson()

    fun log(message: String) {
        if (!enabled) return
        runCatching { 
            if (Services.hasPlugin()) {
                Services.plugin.logger.info("[DEBUG] $message")
            }
        }
    }

    fun logToFile(sessionId: String, runId: String, hypothesisId: String, location: String, message: String, data: Map<String, Any?> = emptyMap(), plugin: JavaPlugin? = null) {
        if (!debugToLogEnabled) return
        runCatching {
            val pluginInstance = plugin ?: if (Services.hasPlugin()) Services.plugin else return@runCatching
            val logFile = File(pluginInstance.dataFolder, "debug.log")
            FileWriter(logFile, true).use { fw ->
                val logEntry = mapOf(
                    "sessionId" to sessionId,
                    "runId" to runId,
                    "hypothesisId" to hypothesisId,
                    "location" to location,
                    "message" to message,
                    "data" to data,
                    "timestamp" to System.currentTimeMillis()
                )
                fw.write(gson.toJson(logEntry) + "\n")
            }
        }
    }
}
