package net.nemoria.quest.config

enum class BackendType { SQLITE, MYSQL }

data class StorageConfig(
    val backend: BackendType,
    val sqliteFile: String,
    val mysqlHost: String,
    val mysqlPort: Int,
    val mysqlDatabase: String,
    val mysqlUser: String,
    val mysqlPassword: String,
    val mysqlUseSSL: Boolean,
    val maxPoolSize: Int,
    val minIdle: Int,
    val connectionTimeoutMs: Long
)
