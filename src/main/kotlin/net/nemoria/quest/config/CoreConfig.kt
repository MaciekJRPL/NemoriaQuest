package net.nemoria.quest.config

data class CoreConfig(
    val multiVersion: List<String>,
    val locale: String,
    val loggingLevel: String,
    val configVersion: String,
    val debugEnabled: Boolean = false,
    val debugToLog: Boolean = false,
    val scoreboardEnabled: Boolean = true
)
