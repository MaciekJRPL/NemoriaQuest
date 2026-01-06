package net.nemoria.quest.storage.repo

import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.data.repo.UserDataRepository
import net.nemoria.quest.data.user.*
import net.nemoria.quest.core.DebugLog
import java.sql.ResultSet
import java.util.UUID
import com.google.gson.Gson

class SqliteUserDataRepository(private val dataSource: HikariDataSource) : UserDataRepository {
    private val gson = Gson()

    override fun load(uuid: UUID): UserData {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteUserDataRepository.kt:15", "load entry", mapOf("uuid" to uuid.toString()))
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT active, completed, progress, user_vars, cooldowns, pools, actionbar_enabled, title_enabled FROM user_data WHERE uuid = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val progressRaw = rs.getString("progress")
                        val userVarsRaw = rs.getString("user_vars")
                        val active = rs.getString("active").toSetMutable()
                        val completed = rs.getString("completed").toSetMutable()
                        val progress = parseProgress(progressRaw)
                        val userVars = parseMap(userVarsRaw)
                        val cooldowns = parseCooldowns(rs.getString("cooldowns"))
                        val pools = parsePools(rs.getString("pools"))
                        val actionbarEnabled = rs.getInt("actionbar_enabled") != 0
                        val titleEnabled = rs.getInt("title_enabled") != 0
                        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteUserDataRepository.kt:23", "load found", mapOf("uuid" to uuid.toString(), "activeCount" to active.size, "completedCount" to completed.size, "progressCount" to progress.size, "userVarsCount" to userVars.size, "cooldownsCount" to cooldowns.size))
                        return UserData(
                            uuid = uuid,
                            activeQuests = active,
                            completedQuests = completed,
                            progress = progress,
                            userVariables = userVars,
                            cooldowns = cooldowns,
                            questPools = pools,
                            actionbarEnabled = actionbarEnabled,
                            titleEnabled = titleEnabled
                        )
                    }
                }
            }
            // Not found -> insert empty
            DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteUserDataRepository.kt:35", "load not found - creating empty", mapOf("uuid" to uuid.toString()))
            val empty = UserData(uuid)
            save(empty)
            return empty
        }
    }

    override fun save(data: UserData) {
        DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteUserDataRepository.kt:41", "save entry", mapOf("uuid" to data.uuid.toString(), "activeCount" to data.activeQuests.size, "completedCount" to data.completedQuests.size, "progressCount" to data.progress.size))
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO user_data(uuid, active, completed, progress, user_vars, cooldowns, pools, actionbar_enabled, title_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET active = excluded.active, completed = excluded.completed, progress = excluded.progress, user_vars = excluded.user_vars, cooldowns = excluded.cooldowns, pools = excluded.pools, actionbar_enabled = excluded.actionbar_enabled, title_enabled = excluded.title_enabled
                """.trimIndent()
            ).use { ps ->
                UserDataRepositoryQueries.bindUser(ps, gson, data)
                val rows = ps.executeUpdate()
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteUserDataRepository.kt:56", "save completed", mapOf("uuid" to data.uuid.toString(), "rowsAffected" to rows))
            }
        }
    }

    private fun String.toSetMutable(): MutableSet<String> =
        if (isBlank()) mutableSetOf() else split(";").filter { it.isNotBlank() }.toMutableSet()

    private fun parseProgress(raw: String?): MutableMap<String, QuestProgress> {
        DebugLog.logToFile("debug-session", "run1", "E", "SqliteUserDataRepository.kt:63", "parseProgress entry", mapOf("rawIsNull" to (raw == null), "rawIsBlank" to raw.isNullOrBlank(), "rawLength" to (raw?.length ?: 0)))
        return UserDataRepositoryQueries.parseProgress(raw, gson) { ex ->
            DebugLog.logToFile("debug-session", "run1", "E", "SqliteUserDataRepository.kt:66", "parseProgress JSON error", mapOf("errorType" to ex.javaClass.simpleName, "errorMessage" to (ex.message?.take(100) ?: "null")))
        }
    }

    private fun parseMap(raw: String?): MutableMap<String, String> {
        return UserDataRepositoryQueries.parseStringMap(raw, gson)
    }

    private fun parseCooldowns(raw: String?): MutableMap<String, QuestCooldown> {
        return UserDataRepositoryQueries.parseCooldowns(raw, gson)
    }

    private fun parsePools(raw: String?): QuestPoolsState {
        return UserDataRepositoryQueries.parsePools(raw, gson)
    }
}
