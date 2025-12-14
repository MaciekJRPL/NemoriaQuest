package net.nemoria.quest.storage.repo

import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.data.repo.ServerVariableRepository

class SqliteServerVariableRepository(private val dataSource: HikariDataSource) : ServerVariableRepository {
    override fun loadAll(): Map<String, String> {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteServerVariableRepository.kt:7", "loadAll entry", mapOf())
        val map = mutableMapOf<String, String>()
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT key, value FROM server_variable").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        map[rs.getString("key")] = rs.getString("value")
                    }
                }
            }
        }
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteServerVariableRepository.kt:18", "loadAll completed", mapOf("count" to map.size))
        return map
    }

    override fun save(key: String, value: String) {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteServerVariableRepository.kt:21", "save entry", mapOf("key" to key, "valueLength" to value.length))
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO server_variable(key, value)
                VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, key)
                ps.setString(2, value)
                val rows = ps.executeUpdate()
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteServerVariableRepository.kt:33", "save completed", mapOf("key" to key, "rowsAffected" to rows))
            }
        }
    }
}
