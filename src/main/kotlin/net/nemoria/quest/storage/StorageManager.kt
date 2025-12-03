package net.nemoria.quest.storage

import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.data.repo.QuestModelRepository
import net.nemoria.quest.data.repo.UserDataRepository
import net.nemoria.quest.data.repo.ServerVariableRepository
import net.nemoria.quest.storage.repo.SqliteQuestModelRepository
import net.nemoria.quest.storage.repo.SqliteUserDataRepository
import net.nemoria.quest.storage.repo.SqliteServerVariableRepository
import java.sql.Connection

class StorageManager(private val dataSource: HikariDataSource) {
    val userRepo: UserDataRepository
    val questModelRepo: QuestModelRepository
    val serverVarRepo: ServerVariableRepository

    init {
        migrate()
        userRepo = SqliteUserDataRepository(dataSource)
        questModelRepo = SqliteQuestModelRepository(dataSource)
        serverVarRepo = SqliteServerVariableRepository(dataSource)
    }

    fun close() {
        dataSource.close()
    }

    private fun migrate() {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            createUserTable(conn)
            createQuestModelTable(conn)
            conn.commit()
        }
    }

    private fun createUserTable(conn: Connection) {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS user_data (
                    uuid TEXT PRIMARY KEY,
                    active TEXT NOT NULL,
                    completed TEXT NOT NULL,
                    progress TEXT NOT NULL,
                    user_vars TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
        // Ensure legacy tables get new column
        addColumnIfMissing(conn, "user_data", "progress", "TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(conn, "user_data", "user_vars", "TEXT NOT NULL DEFAULT ''")
    }

    private fun createQuestModelTable(conn: Connection) {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS quest_model (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT,
                    display_name TEXT,
                    description_lines TEXT,
                    progress_notify TEXT,
                    status_items TEXT,
                    requirements TEXT,
                    objectives TEXT,
                    rewards TEXT,
                    time_limit TEXT,
                    variables TEXT,
                    branches TEXT,
                    main_branch TEXT,
                    end_objects TEXT,
                    saving TEXT,
                    concurrency TEXT,
                    players TEXT,
                    start_conditions TEXT,
                    completion TEXT,
                    activators TEXT
                )
                """.trimIndent()
            )
        }
        addColumnIfMissing(conn, "quest_model", "description", "TEXT")
        addColumnIfMissing(conn, "quest_model", "display_name", "TEXT")
        addColumnIfMissing(conn, "quest_model", "description_lines", "TEXT")
        addColumnIfMissing(conn, "quest_model", "progress_notify", "TEXT")
        addColumnIfMissing(conn, "quest_model", "status_items", "TEXT")
        addColumnIfMissing(conn, "quest_model", "requirements", "TEXT")
        addColumnIfMissing(conn, "quest_model", "objectives", "TEXT")
        addColumnIfMissing(conn, "quest_model", "rewards", "TEXT")
        addColumnIfMissing(conn, "quest_model", "time_limit", "TEXT")
        addColumnIfMissing(conn, "quest_model", "variables", "TEXT")
        addColumnIfMissing(conn, "quest_model", "branches", "TEXT")
        addColumnIfMissing(conn, "quest_model", "main_branch", "TEXT")
        addColumnIfMissing(conn, "quest_model", "end_objects", "TEXT")
        addColumnIfMissing(conn, "quest_model", "saving", "TEXT")
        addColumnIfMissing(conn, "quest_model", "concurrency", "TEXT")
        addColumnIfMissing(conn, "quest_model", "players", "TEXT")
        addColumnIfMissing(conn, "quest_model", "start_conditions", "TEXT")
        addColumnIfMissing(conn, "quest_model", "completion", "TEXT")
        addColumnIfMissing(conn, "quest_model", "activators", "TEXT")

        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS server_variable (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private fun addColumnIfMissing(conn: Connection, table: String, column: String, ddl: String) {
        conn.prepareStatement("PRAGMA table_info($table)").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    if (rs.getString("name").equals(column, ignoreCase = true)) return
                }
            }
        }
        conn.createStatement().use { st ->
            st.executeUpdate("ALTER TABLE $table ADD COLUMN $column $ddl")
        }
    }
}
