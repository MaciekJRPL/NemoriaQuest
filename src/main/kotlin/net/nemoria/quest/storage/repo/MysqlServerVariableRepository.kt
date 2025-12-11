package net.nemoria.quest.storage.repo

import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.data.repo.ServerVariableRepository

class MysqlServerVariableRepository(private val dataSource: HikariDataSource) : ServerVariableRepository {
    override fun loadAll(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT `key`, `value` FROM server_variable").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        map[rs.getString("key")] = rs.getString("value")
                    }
                }
            }
        }
        return map
    }

    override fun save(key: String, value: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO server_variable(`key`, `value`)
                VALUES (?, ?) AS new
                ON DUPLICATE KEY UPDATE `value` = new.`value`
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, key)
                ps.setString(2, value)
                ps.executeUpdate()
            }
        }
    }
}
