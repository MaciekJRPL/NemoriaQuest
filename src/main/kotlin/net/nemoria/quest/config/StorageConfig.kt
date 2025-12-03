package net.nemoria.quest.config

enum class BackendType { SQLITE }

data class StorageConfig(
    val backend: BackendType,
    val sqliteFile: String,
    val maxPoolSize: Int,
    val minIdle: Int,
    val connectionTimeoutMs: Long
)
