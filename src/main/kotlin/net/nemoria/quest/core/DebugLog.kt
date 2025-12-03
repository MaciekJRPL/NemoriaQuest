package net.nemoria.quest.core

object DebugLog {
    @Volatile
    var enabled: Boolean = false

    fun log(message: String) {
        if (!enabled) return
        runCatching { Services.plugin.logger.info("[DEBUG] $message") }
    }
}
