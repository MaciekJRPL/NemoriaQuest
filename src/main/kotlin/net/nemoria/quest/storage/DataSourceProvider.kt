package net.nemoria.quest.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.config.BackendType
import net.nemoria.quest.config.StorageConfig

object DataSourceProvider {
    fun create(config: StorageConfig): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            when (config.backend) {
                BackendType.SQLITE -> {
                    jdbcUrl = "jdbc:sqlite:${config.sqliteFile}"
                    driverClassName = "org.sqlite.JDBC"
                }
            }
            maximumPoolSize = config.maxPoolSize
            minimumIdle = config.minIdle
            connectionTimeout = config.connectionTimeoutMs
            poolName = "NemoriaQuestPool"
        }
        return HikariDataSource(hikariConfig)
    }
}
