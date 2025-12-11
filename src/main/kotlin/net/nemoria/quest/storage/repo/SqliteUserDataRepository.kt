package net.nemoria.quest.storage.repo

import com.zaxxer.hikari.HikariDataSource
import net.nemoria.quest.data.repo.UserDataRepository
import net.nemoria.quest.data.user.*
import java.sql.ResultSet
import java.util.UUID
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SqliteUserDataRepository(private val dataSource: HikariDataSource) : UserDataRepository {
    private val gson = Gson()

    override fun load(uuid: UUID): UserData {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT active, completed, progress, user_vars, cooldowns FROM user_data WHERE uuid = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val progressRaw = rs.getString("progress")
                        val userVarsRaw = rs.getString("user_vars")
                        return UserData(
                            uuid = uuid,
                            activeQuests = rs.getString("active").toSetMutable(),
                            completedQuests = rs.getString("completed").toSetMutable(),
                            progress = parseProgress(progressRaw),
                            userVariables = parseMap(userVarsRaw),
                            cooldowns = parseCooldowns(rs.getString("cooldowns"))
                        )
                    }
                }
            }
            // Not found -> insert empty
            val empty = UserData(uuid)
            save(empty)
            return empty
        }
    }

    override fun save(data: UserData) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO user_data(uuid, active, completed, progress, user_vars, cooldowns)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET active = excluded.active, completed = excluded.completed, progress = excluded.progress, user_vars = excluded.user_vars, cooldowns = excluded.cooldowns
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.setString(2, data.activeQuests.joinToString(";"))
                ps.setString(3, data.completedQuests.joinToString(";"))
                ps.setString(4, gson.toJson(data.progress))
                ps.setString(5, gson.toJson(data.userVariables))
                ps.setString(6, gson.toJson(data.cooldowns))
                ps.executeUpdate()
            }
        }
    }

    private fun String.toSetMutable(): MutableSet<String> =
        if (isBlank()) mutableSetOf() else split(";").filter { it.isNotBlank() }.toMutableSet()

    private fun parseProgress(raw: String?): MutableMap<String, QuestProgress> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        val type = object : TypeToken<Map<String, QuestProgress>>() {}.type
        return runCatching { gson.fromJson<Map<String, QuestProgress>>(raw, type).toMutableMap() }.getOrElse {
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
}
