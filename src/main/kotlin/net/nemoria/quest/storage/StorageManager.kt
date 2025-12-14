package net.nemoria.quest.storage

import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.config.BackendType
import net.nemoria.quest.core.DebugLog
import net.nemoria.quest.data.repo.PlayerBlockRepository
import net.nemoria.quest.data.repo.QuestModelRepository
import net.nemoria.quest.data.repo.ServerVariableRepository
import net.nemoria.quest.data.repo.UserDataRepository
import net.nemoria.quest.storage.repo.MysqlPlayerBlockRepository
import net.nemoria.quest.storage.repo.MysqlQuestModelRepository
import net.nemoria.quest.storage.repo.MysqlServerVariableRepository
import net.nemoria.quest.storage.repo.MysqlUserDataRepository
import net.nemoria.quest.storage.repo.SqlitePlayerBlockRepository
import net.nemoria.quest.storage.repo.SqliteQuestModelRepository
import net.nemoria.quest.storage.repo.SqliteServerVariableRepository
import net.nemoria.quest.storage.repo.SqliteUserDataRepository
import java.sql.Connection

class StorageManager(
    private val backend: BackendType,
    private val dataSource: HikariDataSource
) {
    val userRepo: UserDataRepository
    val questModelRepo: QuestModelRepository
    val serverVarRepo: ServerVariableRepository
    val playerBlockRepo: PlayerBlockRepository

    init {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:28", "StorageManager init entry", mapOf("backend" to backend.name))
        migrate()
        when (backend) {
            BackendType.SQLITE -> {
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:31", "StorageManager initializing SQLite repos", mapOf())
                userRepo = SqliteUserDataRepository(dataSource)
                questModelRepo = SqliteQuestModelRepository(dataSource)
                serverVarRepo = SqliteServerVariableRepository(dataSource)
                playerBlockRepo = SqlitePlayerBlockRepository(dataSource)
            }
            BackendType.MYSQL -> {
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:37", "StorageManager initializing MySQL repos", mapOf())
                userRepo = MysqlUserDataRepository(dataSource)
                questModelRepo = MysqlQuestModelRepository(dataSource)
                serverVarRepo = MysqlServerVariableRepository(dataSource)
                playerBlockRepo = MysqlPlayerBlockRepository(dataSource)
            }
        }
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:43", "StorageManager init completed", mapOf("backend" to backend.name))
    }

    fun close() {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:46", "StorageManager close entry", mapOf("backend" to backend.name))
        dataSource.close()
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:48", "StorageManager close completed", mapOf())
    }

    private fun migrate() {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:50", "StorageManager migrate entry", mapOf("backend" to backend.name))
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:54", "StorageManager creating tables", mapOf())
                createUserTable(conn)
                createQuestModelTable(conn)
                createServerVariableTable(conn)
                createPlayerBlockTable(conn)
                conn.commit()
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:59", "StorageManager migrate committed", mapOf())
            } catch (ex: Exception) {
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:60", "StorageManager migrate error", mapOf("error" to ex.message, "errorType" to ex.javaClass.simpleName))
                runCatching { conn.rollback() }
                throw ex
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    private fun createUserTable(conn: Connection) {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:68", "createUserTable entry", mapOf("backend" to backend.name))
        val uuidType = if (backend == BackendType.MYSQL) "VARCHAR(36)" else "TEXT"
        val textType = if (backend == BackendType.MYSQL) "LONGTEXT" else "TEXT"
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS user_data (
                    uuid $uuidType PRIMARY KEY,
                    active $textType NOT NULL,
                    completed $textType NOT NULL,
                    progress $textType NOT NULL,
                    user_vars $textType NOT NULL,
                    cooldowns $textType NOT NULL
                )
                """.trimIndent()
            )
        }
        val userColumnDdl = if (backend == BackendType.SQLITE) "TEXT NOT NULL DEFAULT ''" else "$textType NOT NULL DEFAULT ''"
        addColumnIfMissing(conn, "user_data", "progress", userColumnDdl)
        addColumnIfMissing(conn, "user_data", "user_vars", userColumnDdl)
        addColumnIfMissing(conn, "user_data", "cooldowns", userColumnDdl)
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:89", "createUserTable completed", mapOf())
    }

    private fun createQuestModelTable(conn: Connection) {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:91", "createQuestModelTable entry", mapOf("backend" to backend.name))
        val idType = if (backend == BackendType.MYSQL) "VARCHAR(191)" else "TEXT"
        val textType = if (backend == BackendType.MYSQL) "TEXT" else "TEXT"
        val longTextType = if (backend == BackendType.MYSQL) "LONGTEXT" else "TEXT"
        val intType = if (backend == BackendType.MYSQL) "INT" else "INTEGER"
        val doubleType = if (backend == BackendType.MYSQL) "DOUBLE" else "REAL"
        val bigIntType = if (backend == BackendType.MYSQL) "BIGINT" else "INTEGER"
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS quest_model (
                    id $idType PRIMARY KEY,
                    name $textType NOT NULL,
                    description $textType,
                    display_name $textType,
                    description_lines $longTextType,
                    progress_notify $longTextType,
                    status_items $longTextType,
                    requirements $longTextType,
                    objectives $longTextType,
                    rewards $longTextType,
                    time_limit $longTextType,
                    variables $longTextType,
                    branches $longTextType,
                    main_branch $textType,
                    end_objects $longTextType,
                    saving $longTextType,
                    concurrency $longTextType,
                    players $longTextType,
                    start_conditions $longTextType,
                    completion $longTextType,
                    activators $longTextType,
                    activators_dialog $longTextType,
                    activators_dialog_auto_start_distance $doubleType,
                    activators_dialog_reset_delay $bigIntType,
                    activators_dialog_reset_distance $doubleType,
                    activators_dialog_reset_notify $longTextType,
                    description_placeholder $textType,
                    information_message $textType,
                    display_priority $intType,
                    default_status_item $longTextType,
                    permission_start_restriction $textType,
                    permission_start_command_restriction $textType,
                    world_restriction $longTextType,
                    command_restriction $longTextType,
                    cooldown $longTextType
                )
                """.trimIndent()
            )
        }
        addColumnIfMissing(conn, "quest_model", "description", textType)
        addColumnIfMissing(conn, "quest_model", "display_name", textType)
        addColumnIfMissing(conn, "quest_model", "description_lines", longTextType)
        addColumnIfMissing(conn, "quest_model", "progress_notify", longTextType)
        addColumnIfMissing(conn, "quest_model", "status_items", longTextType)
        addColumnIfMissing(conn, "quest_model", "requirements", longTextType)
        addColumnIfMissing(conn, "quest_model", "objectives", longTextType)
        addColumnIfMissing(conn, "quest_model", "rewards", longTextType)
        addColumnIfMissing(conn, "quest_model", "time_limit", longTextType)
        addColumnIfMissing(conn, "quest_model", "variables", longTextType)
        addColumnIfMissing(conn, "quest_model", "branches", longTextType)
        addColumnIfMissing(conn, "quest_model", "main_branch", textType)
        addColumnIfMissing(conn, "quest_model", "end_objects", longTextType)
        addColumnIfMissing(conn, "quest_model", "saving", longTextType)
        addColumnIfMissing(conn, "quest_model", "concurrency", longTextType)
        addColumnIfMissing(conn, "quest_model", "players", longTextType)
        addColumnIfMissing(conn, "quest_model", "start_conditions", longTextType)
        addColumnIfMissing(conn, "quest_model", "completion", longTextType)
        addColumnIfMissing(conn, "quest_model", "activators", longTextType)
        addColumnIfMissing(conn, "quest_model", "activators_dialog", longTextType)
        addColumnIfMissing(conn, "quest_model", "activators_dialog_auto_start_distance", doubleType)
        addColumnIfMissing(conn, "quest_model", "activators_dialog_reset_delay", bigIntType)
        addColumnIfMissing(conn, "quest_model", "activators_dialog_reset_distance", doubleType)
        addColumnIfMissing(conn, "quest_model", "activators_dialog_reset_notify", longTextType)
        addColumnIfMissing(conn, "quest_model", "description_placeholder", textType)
        addColumnIfMissing(conn, "quest_model", "information_message", textType)
        addColumnIfMissing(conn, "quest_model", "display_priority", intType)
        addColumnIfMissing(conn, "quest_model", "default_status_item", longTextType)
        addColumnIfMissing(conn, "quest_model", "permission_start_restriction", textType)
        addColumnIfMissing(conn, "quest_model", "permission_start_command_restriction", textType)
        addColumnIfMissing(conn, "quest_model", "world_restriction", longTextType)
        addColumnIfMissing(conn, "quest_model", "command_restriction", longTextType)
        addColumnIfMissing(conn, "quest_model", "cooldown", longTextType)
    }

    private fun createServerVariableTable(conn: Connection) {
        val keyType = if (backend == BackendType.MYSQL) "VARCHAR(191)" else "TEXT"
        val textType = if (backend == BackendType.MYSQL) "LONGTEXT" else "TEXT"
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS server_variable (
                    key $keyType PRIMARY KEY,
                    value $textType NOT NULL
                )
                """.trimIndent()
            )
        }
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:177", "createServerVariableTable completed", mapOf())
    }

    private fun createPlayerBlockTable(conn: Connection) {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:179", "createPlayerBlockTable entry", mapOf("backend" to backend.name))
        val worldType = if (backend == BackendType.MYSQL) "VARCHAR(191)" else "TEXT"
        val ownerType = if (backend == BackendType.MYSQL) "VARCHAR(36)" else "TEXT"
        val intType = if (backend == BackendType.MYSQL) "INT" else "INTEGER"
        val tsType = if (backend == BackendType.MYSQL) "BIGINT" else "INTEGER"
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS player_blocks (
                    world $worldType NOT NULL,
                    x $intType NOT NULL,
                    y $intType NOT NULL,
                    z $intType NOT NULL,
                    owner $ownerType,
                    ts $tsType NOT NULL,
                    PRIMARY KEY (world, x, y, z)
                )
                """.trimIndent()
            )
        }
        createIndexIfMissing(conn, "player_blocks", "idx_player_blocks_ts", "ts")
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:199", "createPlayerBlockTable completed", mapOf())
    }

    private fun addColumnIfMissing(conn: Connection, table: String, column: String, ddl: String) {
        if (hasColumn(conn, table, column)) {
            return
        }
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:202", "addColumnIfMissing adding column", mapOf("table" to table, "column" to column))
        val tableId = quoteIdentifier(table)
        val columnId = quoteIdentifier(column)
        conn.createStatement().use { st ->
            st.executeUpdate("ALTER TABLE $tableId ADD COLUMN $columnId $ddl")
        }
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "StorageManager.kt:208", "addColumnIfMissing column added", mapOf("table" to table, "column" to column))
    }

    private fun hasColumn(conn: Connection, table: String, column: String): Boolean {
        val meta = conn.metaData
        val schema = runCatching { conn.schema }.getOrNull()
        val tables = identifierVariants(meta, table)
        val columns = identifierVariants(meta, column)
        for (t in tables) {
            for (c in columns) {
                meta.getColumns(conn.catalog, schema, t, c).use { rs ->
                    if (rs.next()) return true
                }
                meta.getColumns(conn.catalog, null, t, c).use { rs ->
                    if (rs.next()) return true
                }
            }
        }
        return false
    }

    private fun createIndexIfMissing(conn: Connection, table: String, index: String, columns: String) {
        val meta = conn.metaData
        val schema = runCatching { conn.schema }.getOrNull()
        meta.getIndexInfo(conn.catalog, schema, table, false, false).use { rs ->
            while (rs.next()) {
                val name = rs.getString("INDEX_NAME") ?: continue
                if (name.equals(index, ignoreCase = true)) return
            }
        }
        val tableId = quoteIdentifier(table)
        val indexId = quoteIdentifier(index)
        val columnList = columns.split(",").joinToString(",") { quoteIdentifier(it.trim()) }
        val ddl = if (backend == BackendType.SQLITE) {
            "CREATE INDEX IF NOT EXISTS $indexId ON $tableId($columnList)"
        } else {
            "CREATE INDEX $indexId ON $tableId($columnList)"
        }
        conn.createStatement().use { st ->
            runCatching { st.executeUpdate(ddl) }.onFailure { ex ->
                // MySQL duplicate index: SQLState 42000 or 42S11, errorCode 1061
                val sqlState = (ex as? java.sql.SQLException)?.sqlState
                val errorCode = (ex as? java.sql.SQLException)?.errorCode
                if (errorCode == 1061 || sqlState == "42S11" || sqlState == "42000") {
                    return
                }
                throw ex
            }
        }
    }

    private fun quoteIdentifier(id: String): String {
        require(id.matches(Regex("^[A-Za-z0-9_]+$"))) { "Invalid identifier: $id" }
        return if (backend == BackendType.MYSQL) "`$id`" else "\"$id\""
    }

    private fun identifierVariants(meta: java.sql.DatabaseMetaData, id: String): Set<String> {
        val variants = mutableSetOf(id, id.uppercase(), id.lowercase())
        if (meta.storesLowerCaseIdentifiers()) variants.add(id.lowercase())
        if (meta.storesUpperCaseIdentifiers()) variants.add(id.uppercase())
        return variants
    }
}
