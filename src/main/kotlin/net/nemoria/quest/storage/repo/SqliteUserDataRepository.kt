package net.nemoria.quest.storage.repo

import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.data.repo.UserDataRepository
import net.nemoria.quest.data.user.*
import net.nemoria.quest.core.DebugLog
import java.sql.ResultSet
import java.util.UUID
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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
                ps.setString(1, data.uuid.toString())
                ps.setString(2, data.activeQuests.joinToString(";"))
                ps.setString(3, data.completedQuests.joinToString(";"))
                ps.setString(4, gson.toJson(data.progress))
                ps.setString(5, gson.toJson(data.userVariables))
                ps.setString(6, gson.toJson(data.cooldowns))
                ps.setString(7, gson.toJson(data.questPools))
                ps.setInt(8, if (data.actionbarEnabled) 1 else 0)
                ps.setInt(9, if (data.titleEnabled) 1 else 0)
                val rows = ps.executeUpdate()
                DebugLog.logToFile("debug-session", "run1", "STORAGE", "SqliteUserDataRepository.kt:56", "save completed", mapOf("uuid" to data.uuid.toString(), "rowsAffected" to rows))
            }
        }
    }

    private fun String.toSetMutable(): MutableSet<String> =
        if (isBlank()) mutableSetOf() else split(";").filter { it.isNotBlank() }.toMutableSet()

    private fun parseProgress(raw: String?): MutableMap<String, QuestProgress> {
        DebugLog.logToFile("debug-session", "run1", "E", "SqliteUserDataRepository.kt:63", "parseProgress entry", mapOf("rawIsNull" to (raw == null), "rawIsBlank" to raw.isNullOrBlank(), "rawLength" to (raw?.length ?: 0)))
        if (raw.isNullOrBlank()) return mutableMapOf()
        val type = object : TypeToken<Map<String, QuestProgress>>() {}.type
        return runCatching { gson.fromJson<Map<String, QuestProgress>>(raw, type).toMutableMap() }.getOrElse { ex ->
            DebugLog.logToFile("debug-session", "run1", "E", "SqliteUserDataRepository.kt:66", "parseProgress JSON error", mapOf("errorType" to ex.javaClass.simpleName, "errorMessage" to (ex.message?.take(100) ?: "null")))
            // legacy fallback: "questId:step"
            val map = mutableMapOf<String, QuestProgress>()
            raw.split(";").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val qp = QuestProgress()
                    qp.objectives[parts[0]] = ObjectiveState(completed = parts[1].toIntOrNull() == 1)
                    map[parts[0]] = qp
                }
            }
            map
        }
    }

    private fun parseMap(raw: String?): MutableMap<String, String> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        val type = object : TypeToken<Map<String, String>>() {}.type
        return runCatching { gson.fromJson<Map<String, String>>(raw, type).toMutableMap() }.getOrElse { mutableMapOf() }
    }

    private fun parseCooldowns(raw: String?): MutableMap<String, QuestCooldown> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        val type = object : TypeToken<Map<String, QuestCooldown>>() {}.type
        return runCatching { gson.fromJson<Map<String, QuestCooldown>>(raw, type).toMutableMap() }.getOrElse { mutableMapOf() }
    }

    private fun parsePools(raw: String?): QuestPoolsState {
        if (raw.isNullOrBlank()) return QuestPoolsState()
        return runCatching { gson.fromJson(raw, QuestPoolsState::class.java) }.getOrElse { QuestPoolsState() }
    }
}
