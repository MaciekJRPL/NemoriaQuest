package net.nemoria.quest.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.config.BackendType
import net.nemoria.quest.config.StorageConfig

object DataSourceProvider {
    fun create(config: StorageConfig): HikariDataSource {
        validate(config)
        val hikariConfig = HikariConfig().apply {
            when (config.backend) {
                BackendType.SQLITE -> {
                    jdbcUrl = "jdbc:sqlite:${config.sqliteFile}"
                    driverClassName = "org.sqlite.JDBC"
                }
                BackendType.MYSQL -> {
                    jdbcUrl = "jdbc:mysql://${config.mysqlHost}:${config.mysqlPort}/${config.mysqlDatabase}?useSSL=${config.mysqlUseSSL}&allowPublicKeyRetrieval=true&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8"
                    username = config.mysqlUser
                    password = config.mysqlPassword
                    driverClassName = "com.mysql.cj.jdbc.Driver"
                }
            }
            maximumPoolSize = config.maxPoolSize
            minimumIdle = config.minIdle
            connectionTimeout = config.connectionTimeoutMs
            poolName = "NemoriaQuestPool"
        }
        return HikariDataSource(hikariConfig)
    }

    private fun validate(cfg: StorageConfig) {
        when (cfg.backend) {
            BackendType.SQLITE -> {
                require(cfg.sqliteFile.isNotBlank()) { "sqlite.file nie może być puste" }
            }
            BackendType.MYSQL -> {
                require(cfg.mysqlHost.isNotBlank()) { "mysql.host nie może być puste" }
                require(cfg.mysqlDatabase.isNotBlank()) { "mysql.database nie może być puste" }
                require(cfg.mysqlUser.isNotBlank()) { "mysql.user nie może być puste" }
                require(cfg.mysqlPort > 0) { "mysql.port musi być większe od 0" }
            }
        }
    }
}
